package com.github.gezimos.inkos.power

/** Result of one shell command. */
data class ExecResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Executes one device-state command at whatever privilege level this build has.
 *
 * T1 scope: the only elevated tier is [PowerCapability.SHELL] through Shizuku
 * (uid 2000). The root tier is deferred - no implementation here spawns `su` -
 * because the system-level work (PPM, cpufreq, charge ceiling) is on hold, and a
 * superuser prompt in a launcher is not an acceptable side effect of flipping a
 * reading toggle.
 *
 * Every implementation is best-effort: a command that fails must report failure
 * rather than throw, because the mode manager rolls back on the first failure
 * and a thrown exception would skip the rollback.
 */
interface PowerExecutor {
    val capability: PowerCapability
    fun available(): Boolean
    fun run(command: String): ExecResult
}

/** Used when no elevated executor is bound; every command reports failure. */
object NoopExecutor : PowerExecutor {
    override val capability = PowerCapability.NONE
    override fun available() = false
    override fun run(command: String) = ExecResult(-1, "no privileged executor")
}

/**
 * Runs commands with `adb shell` (uid 2000) rights supplied by the Shizuku
 * bridge.
 *
 * The runner is injected rather than imported so this package stays free of the
 * Shizuku dependency: the bridge owns binding, permission and lifetime, and this
 * class only sees "run this string, give me the exit code".
 */
class ShellExecutor(
    private val runner: (String) -> ExecResult
) : PowerExecutor {

    override val capability = PowerCapability.SHELL

    override fun available() = PowerCapabilityDetector.current() == PowerCapability.SHELL

    override fun run(command: String) = runner(command)
}
