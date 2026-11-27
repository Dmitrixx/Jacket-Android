package app.jacket.mobile.byedpi.core

import android.net.VpnService
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Called from the ByeDPI native socket wrapper. Only ByeDPI's upstream
 * connections are protected; Lampa and other app traffic still enters the TUN.
 */
internal object ByedpiVpnSocketProtector {
    @Volatile
    private var activeService = WeakReference<VpnService>(null)

    @JvmStatic
    fun attach(service: VpnService) {
        activeService = WeakReference(service)
    }

    @JvmStatic
    fun detach(service: VpnService) {
        if (activeService.get() === service) {
            activeService.clear()
        }
    }

    @JvmStatic
    fun protectSocket(fd: Int) {
        val service = activeService.get() ?: return
        try {
            if (!service.protect(fd)) {
                Log.w("ByeDPI", "VpnService.protect($fd) returned false")
            }
        } catch (error: Exception) {
            Log.w("ByeDPI", "Could not protect native socket $fd", error)
        }
    }
}
