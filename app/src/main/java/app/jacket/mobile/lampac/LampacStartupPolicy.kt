package app.jacket.mobile.lampac

/**
 * Lampac compiles enabled provider modules during cold startup.
 * On an Android phone this can take minutes; a short watchdog sends Ctrl+C
 * and makes a healthy first launch look like exit code 130.
 */
internal object LampacStartupPolicy {
    const val PROBE_INTERVAL_MS = 500L
    const val STARTUP_TIMEOUT_MS = 5 * 60 * 1_000L

    /** Use elapsedRealtime values so wall-clock changes cannot shorten startup. */
    fun hasTimedOut(startedAtMs: Long, nowMs: Long): Boolean =
        nowMs >= startedAtMs && nowMs - startedAtMs >= STARTUP_TIMEOUT_MS
}
