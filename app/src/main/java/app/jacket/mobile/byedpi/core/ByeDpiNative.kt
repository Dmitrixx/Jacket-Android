package app.jacket.mobile.byedpi.core

/**
 * JNI facade for hufrea/ByeDPI and HEV tun2socks.
 *
 * The native sources are permissively licensed MIT dependencies. The Android
 * control screen and VPN service are implemented in this application rather
 * than embedding the separate GPL-3.0-only ByeByeDPI Android UI.
 */
internal class ByeDpiNative {
    companion object {
        init {
            System.loadLibrary("byedpi")
        }
    }

    fun startProxy(args: Array<String>): Int = jniStartProxy(args)

    fun stopProxy(): Int = jniStopProxy()

    fun startTunnel(config: String, fd: Int): Int = jniStartTunnel(config, fd)

    fun stopTunnel() = jniStopTunnel()

    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy(): Int
    private external fun jniStartTunnel(config: String, fd: Int): Int
    private external fun jniStopTunnel()
}
