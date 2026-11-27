#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

#include "error.h"
#include "main.h"

extern int server_fd;
extern int __real_socket(int domain, int type, int protocol);

static pthread_mutex_t proxy_mutex = PTHREAD_MUTEX_INITIALIZER;
static int proxy_started;
static int proxy_running;
static JavaVM *java_vm;
static jclass protector_class;
static jmethodID protect_socket_method;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    java_vm = vm;

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK && env != NULL) {
        jclass local_class = (*env)->FindClass(
            env,
            "app/jacket/mobile/byedpi/core/ByedpiVpnSocketProtector"
        );
        if (local_class != NULL) {
            protector_class = (jclass) (*env)->NewGlobalRef(env, local_class);
            protect_socket_method = (*env)->GetStaticMethodID(
                env,
                protector_class,
                "protectSocket",
                "(I)V"
            );
            (*env)->DeleteLocalRef(env, local_class);
        }
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    }
    return JNI_VERSION_1_6;
}

static void protect_socket_with_vpn(int fd) {
    if (java_vm == NULL || fd < 0) return;

    JNIEnv *env = NULL;
    int attached_here = 0;
    jint state = (*java_vm)->GetEnv(java_vm, (void **) &env, JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if ((*java_vm)->AttachCurrentThread(java_vm, &env, NULL) != JNI_OK) return;
        attached_here = 1;
    } else if (state != JNI_OK || env == NULL) {
        return;
    }

    if (protector_class != NULL && protect_socket_method != NULL) {
        (*env)->CallStaticVoidMethod(env, protector_class, protect_socket_method, (jint) fd);
    }

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }

    if (attached_here) {
        (*java_vm)->DetachCurrentThread(java_vm);
    }
}

/**
 * ByeDPI's outbound sockets must be protected from Android's VPN TUN device.
 * Otherwise the SOCKS proxy would recursively route its own upstream traffic
 * back into itself. The app's ordinary sockets remain inside the VPN.
 */
int __wrap_socket(int domain, int type, int protocol) {
    int fd = __real_socket(domain, type, protocol);
    if (fd >= 0) protect_socket_with_vpn(fd);
    return fd;
}

int __wrap_daemon(__attribute__((unused)) int nochdir, __attribute__((unused)) int noclose) {
    LOG(LOG_S, "daemon mode is not supported in the Android native worker");
    errno = EPERM;
    return -1;
}

JNIEXPORT jint JNICALL
Java_app_jacket_mobile_byedpi_core_ByeDpiNative_jniStartProxy(
    JNIEnv *env,
    __attribute__((unused)) jobject thiz,
    jobjectArray args
) {
    pthread_mutex_lock(&proxy_mutex);
    if (proxy_started) {
        pthread_mutex_unlock(&proxy_mutex);
        LOG(LOG_S, "proxy process already used");
        return -1;
    }
    proxy_started = 1;
    pthread_mutex_unlock(&proxy_mutex);

    int argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc((size_t) argc + 1, sizeof(char *));
    int result = -1;
    if (argv == NULL) return result;

    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (arg == NULL) goto cleanup;

        const char *arg_str = (*env)->GetStringUTFChars(env, arg, NULL);
        if (arg_str != NULL) {
            argv[i] = strdup(arg_str);
            (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        }
        (*env)->DeleteLocalRef(env, arg);
        if (argv[i] == NULL) goto cleanup;
    }

    LOG(LOG_S, "starting ByeDPI proxy with %d arguments", argc);
    pthread_mutex_lock(&proxy_mutex);
    server_fd = -1;
    proxy_running = 1;
    pthread_mutex_unlock(&proxy_mutex);

    result = main(argc, argv);

    pthread_mutex_lock(&proxy_mutex);
    proxy_running = 0;
    pthread_mutex_unlock(&proxy_mutex);
    LOG(LOG_S, "ByeDPI proxy returned %d", result);

cleanup:
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    return result;
}

JNIEXPORT jint JNICALL
Java_app_jacket_mobile_byedpi_core_ByeDpiNative_jniStopProxy(
    __attribute__((unused)) JNIEnv *env,
    __attribute__((unused)) jobject thiz
) {
    LOG(LOG_S, "sending shutdown to ByeDPI proxy");
    pthread_mutex_lock(&proxy_mutex);
    int result = proxy_running && server_fd >= 0 ? shutdown(server_fd, SHUT_RDWR) : -1;
    pthread_mutex_unlock(&proxy_mutex);
    return result;
}
