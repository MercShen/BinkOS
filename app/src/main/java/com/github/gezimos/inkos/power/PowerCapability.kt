package com.github.gezimos.inkos.power

import java.util.concurrent.TimeUnit

/**
 * How much of the device this build can actually change.
 *
 * The ladder is a permission ladder, not a feature ladder:
 *
 *  - [NONE]  ordinary app sandbox. The launcher can only change its own state.
 *  - [SHELL] `adb shell` (uid 2000) rights, obtained through Shizuku. Enough for
 *            app-ops / standby buckets / doze / airplane mode / settings, but NOT
 *            enough for PPM or cpufreq - both are SELinux-denied to uid 2000 on
 *            the HiBreak (measured on B651 CN 3.4.6).
 *  - [ROOT]  `su` available. Everything, including the MTK PPM policy unlock and
 *            the `/proc/mtk_battery_cmd` charge switch.
 *
 * Nothing in Bink OS may require more than [NONE]; the higher tiers only unlock
 * the optional deep power features. That is why detection has to be cheap,
 * cached and never fatal.
 */
enum class PowerCapability {
    NONE,
    SHELL,
    ROOT;

    /** Deep (frequency / kernel level) controls need [ROOT]. */
    val supportsDeepControls: Boolean get() = this == ROOT
}

/**
 * Probes the capability ladder once per process.
 *
 * `su` probing costs a process spawn, so it must never run on the main thread
 * and its result must be cached. A failed probe is not an error - it just means
 * we stay at the lower tier.
 */
object PowerCapabilityDetector {

    @Volatile
    private var cached: PowerCapability? = null

    /** Last detection result, or [PowerCapability.NONE] if it has not run yet. */
    fun current(): PowerCapability = cached ?: PowerCapability.NONE

    /**
     * Blocking probe - call off the main thread. Idempotent; a second call after
     * a successful probe returns the cached value.
     *
     * The root tier is deliberately not probed: the system-level work (PPM,
     * cpufreq, charge ceiling) is deferred, so this build never spawns `su` and
     * therefore never triggers a superuser prompt. SHELL comes from the Shizuku
     * bridge calling [reportShellAvailable] once it has bound.
     */
    fun detect(force: Boolean = false): PowerCapability {
        cached?.let { if (!force) return it }
        return cached ?: PowerCapability.NONE
    }

    /**
     * SHELL is deliberately *not* probed here: a normal app cannot tell whether
     * the Shizuku service is bound without the Shizuku dependency. The bridge
     * calls [reportShellAvailable] once it has bound, so this object stays free
     * of that dependency.
     */
    fun reportShellAvailable() {
        if (cached != PowerCapability.ROOT) cached = PowerCapability.SHELL
    }

    /** The bridge died or the permission was revoked: drop back to [NONE]. */
    fun reportShellUnavailable() {
        if (cached == PowerCapability.SHELL) cached = PowerCapability.NONE
    }

    private fun hasRoot(): Boolean = false // root tier deferred; see detect()
}
