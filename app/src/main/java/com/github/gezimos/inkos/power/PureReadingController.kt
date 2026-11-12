package com.github.gezimos.inkos.power

import android.content.Context
import android.content.pm.ApplicationInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Façade the launcher talks to.
 *
 * Everything here is blocking (it spawns shells), so callers must use a
 * background dispatcher. The class owns no UI state and no views: the toggle,
 * the warning dialog and the capability badge are the launcher's business.
 */
class PureReadingController(context: Context) {

    private val appContext = context.applicationContext
    private val store = PrefsPowerStore(appContext)
    private val manager = ModeManager(store, NoopExecutor)
    private val busy = AtomicBoolean(false)

    /** Cached tier; [PowerCapability.NONE] until the Shizuku bridge reports in. */
    fun capability(): PowerCapability = PowerCapabilityDetector.current()

    /** Re-reads the cached tier. Cheap: this build never probes `su`. */
    fun detectCapability(): PowerCapability = PowerCapabilityDetector.detect()

    /**
     * Supplies the executor for this session - the Shizuku bridge hands over a
     * [ShellExecutor] once it has bound, and falls back to [NoopExecutor] when
     * the permission is revoked or the service dies.
     */
    fun useExecutor(executor: PowerExecutor) = manager.setExecutor(executor)

    /** True when an elevated executor is bound and the toggle can take effect. */
    fun canToggle(): Boolean = manager.isExecutable()

    fun isActive(): Boolean = store.loadMode() == PowerMode.PURE_READ

    /** Whether the user has confirmed the consequences of pure-reading mode. */
    fun hasAcceptedWarning(): Boolean = store.loadWarningAccepted()

    fun acceptWarning() = store.saveWarningAccepted()

    fun whitelist(): Set<String> = store.loadWhitelist()

    fun setWhitelist(packages: Set<String>) = store.saveWhitelist(packages)

    /**
     * Operations in the table that this build cannot perform. The UI uses this to
     * explain the missing deep layer instead of hiding it.
     */
    fun gatedKeys(): List<String> = table()
        .filter { it.requiresRoot }
        .map { it.key }

    fun supportedKeyCount(): Int = table().count { it.requiresRoot.not() }

    /**
     * Flip the mode. Returns true when the device reached the new state; on
     * failure everything already applied in this transition is rolled back.
     */
    fun toggle(): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        return try {
            if (isActive()) {
                manager.apply(PowerMode.NORMAL, emptyList())
            } else {
                manager.apply(PowerMode.PURE_READ, table())
            }
        } finally {
            busy.set(false)
        }
    }

    /**
     * Finishes a rollback that could not run at boot because the shell was not up
     * yet: a journal left behind while the persisted mode is already NORMAL means
     * "pending undo", never a live session (a live one keeps mode = PURE_READ).
     */
    fun reconcilePendingRollback(): Boolean {
        if (store.loadMode() != PowerMode.NORMAL) return false
        if (store.loadJournal().isEmpty()) return false
        manager.reconcileOnStart()
        return true
    }

    /** Undo a transition interrupted by a crash or a reboot. */
    fun reconcileOnStart() = manager.reconcileOnStart()

    private fun table(): List<PowerOp> = BasePowerOps.build(targets().toList(), whitelist())

    /**
     * Third-party packages are the only ones worth restricting: system apps are
     * either required (telephony, systemui) or already exempt. PackageManager
     * enumeration is not free, hence the background-thread requirement.
     */
    private fun targets(): Set<String> {
        val result = mutableSetOf<String>()
        for (info in appContext.packageManager.getInstalledPackages(0)) {
            val app = info.applicationInfo ?: continue
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            if (app.packageName == appContext.packageName) continue
            result.add(app.packageName)
        }
        return result
    }
}
