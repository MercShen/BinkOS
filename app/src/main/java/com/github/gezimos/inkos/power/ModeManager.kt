package com.github.gezimos.inkos.power

/** The two states the toggle switches between. */
enum class PowerMode { NORMAL, PURE_READ }

/**
 * One reversible device-state operation.
 *
 * [probe] reads the current value before [pure] runs; [normalFromProbe] turns
 * that value back into the command that restores it. Keeping the restore command
 * in the journal means a revert never has to guess a default - it replays exactly
 * what the device had.
 */
data class PowerOp(
    val key: String,
    val pure: String? = null,
    val normal: String? = null,
    val probe: String? = null,
    val requiresRoot: Boolean = false,
    val normalFromProbe: ((String?) -> String?)? = null
) {
    /** Restore command for a probe captured before [pure] ran. */
    fun normalCommand(before: String?): String? =
        normalFromProbe?.invoke(before) ?: normal
}

/** One applied change: what it was, and the exact command that undoes it. */
data class Change(val key: String, val before: String?, val undo: String?)

/** Persists the mode flag and the journal. */
interface PowerStore {
    fun loadMode(): PowerMode
    fun saveMode(mode: PowerMode)
    fun loadJournal(): List<Change>
    fun saveJournal(changes: List<Change>)
}

/**
 * Applies a target mode by walking the strategy table.
 *
 * Rules, in order of importance:
 *  1. Never half-apply. On the first failed command, revert everything applied in
 *     this transition and leave the mode flag alone.
 *  2. Never lose the user's original values: the journal carries the restore
 *     command, derived from a probe taken just before the change.
 *  3. Never touch what this capability cannot reach - filtered up front, not
 *     discovered halfway through.
 */
class ModeManager(
    private val store: PowerStore,
    private var executor: PowerExecutor
) {

    fun setExecutor(next: PowerExecutor) {
        executor = next
    }

    fun currentMode(): PowerMode = store.loadMode()

    /** True when the bound executor can actually run commands. */
    fun isExecutable(): Boolean = executor.available()

    fun supports(op: PowerOp): Boolean = !op.requiresRoot || executor.capability.supportsDeepControls

    /** Transition to [target]. Returns true when the device reached it. */
    fun apply(target: PowerMode, ops: List<PowerOp>): Boolean {
        if (!executor.available()) return false
        val journal = store.loadJournal()
        if (store.loadMode() == target && journal.isEmpty()) return true

        return if (target == PowerMode.PURE_READ) enter(ops) else leave(journal)
    }

    private fun enter(ops: List<PowerOp>): Boolean {
        val applied = mutableListOf<Change>()
        for (op in ops) {
            if (!supports(op)) continue
            val command = op.pure?.takeIf { it.isNotBlank() } ?: continue
            val before = op.probe?.let { executor.run(it).output }
            val undo = op.normalCommand(before)

            val result = executor.run(command)
            if (!result.ok) {
                android.util.Log.w("ModeManager", "enter failed at " + op.key + " exit=" + result.exitCode + " out=" + result.output)
                revert(applied)
                return false
            }
            applied += Change(op.key, before, undo)
        }
        store.saveJournal(applied)
        store.saveMode(PowerMode.PURE_READ)
        return true
    }

    private fun leave(journal: List<Change>): Boolean {
        if (journal.isEmpty()) {
            store.saveMode(PowerMode.NORMAL)
            return true
        }
        revert(journal)
        store.saveMode(PowerMode.NORMAL)
        return true
    }

    /** Undo a journal, newest first. Used on failed enter and on leaving. */
    private fun revert(changes: List<Change>) {
        for (change in changes.asReversed()) {
            change.undo?.takeIf { it.isNotBlank() }?.let { executor.run(it) }
        }
        store.saveJournal(emptyList())
    }

    /**
     * Called once at launcher start. A journal left behind means a transition was
     * interrupted, so the safe action is to undo it rather than assume pure mode
     * was reached.
     */
    fun reconcileOnStart() {
        val journal = store.loadJournal()
        if (journal.isNotEmpty()) {
            revert(journal)
            store.saveMode(PowerMode.NORMAL)
        }
    }
}
