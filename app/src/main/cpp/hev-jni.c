#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>

#include "error.h"

static pthread_once_t tunnel_once = PTHREAD_ONCE_INIT;
static int (*tunnel_main)(const unsigned char *, unsigned int, int);
static void (*tunnel_quit)(void);

static void load_tunnel(void) {
    void *library = dlopen("libhev-socks5-tunnel.so", RTLD_NOW | RTLD_LOCAL);
    if (library == NULL) {
        LOG(LOG_S, "failed to load HEV tunnel: %s", dlerror());
        return;
    }

    tunnel_main = (int (*)(const unsigned char *, unsigned int, int))
        dlsym(library, "hev_socks5_tunnel_main_from_str");
    tunnel_quit = (void (*)(void)) dlsym(library, "hev_socks5_tunnel_quit");
}

JNIEXPORT jint JNICALL
Java_app_jacket_mobile_byedpi_core_ByeDpiNative_jniStartTunnel(
    JNIEnv *env,
    __attribute__((unused)) jobject thiz,
    jstring config,
    jint fd
) {
    pthread_once(&tunnel_once, load_tunnel);
    if (tunnel_main == NULL || tunnel_quit == NULL) {
        LOG(LOG_S, "HEV tunnel entry points are unavailable");
        return -1;
    }

    const char *config_str = (*env)->GetStringUTFChars(env, config, NULL);
    if (config_str == NULL) return -1;

    unsigned int config_len = (unsigned int) (*env)->GetStringUTFLength(env, config);
    int result = tunnel_main((const unsigned char *) config_str, config_len, fd);
    (*env)->ReleaseStringUTFChars(env, config, config_str);
    return result;
}

JNIEXPORT void JNICALL
Java_app_jacket_mobile_byedpi_core_ByeDpiNative_jniStopTunnel(
    __attribute__((unused)) JNIEnv *env,
    __attribute__((unused)) jobject thiz
) {
    pthread_once(&tunnel_once, load_tunnel);
    if (tunnel_quit != NULL) tunnel_quit();
}
