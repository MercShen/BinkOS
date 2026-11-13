package com.github.gezimos.inkos.power

import java.util.concurrent.Executors

/** Result of one shell command. */
data class ExecResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Executes device-state commands at whatever privilege level this build has.
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

    /**
     * Runs several commands and returns one result per command, in order.
     *
     * The default is a loop; backends that can reach a shell override it to run
     * the whole list inside a single shell process. That matters here: one
     * transition touches every installed package and turns ~400 IPC round trips
     * into two.
     */
    fun runBatch(commands: List<String>): List<ExecResult> = commands.map { run(it) }

    /**
     * Runs commands strictly in order, in one shell, without chunking or
     * parallelism. Needed for steps whose order is part of their correctness -
     * e.g. Bluetooth must be switched off *before* airplane mode goes on, and
     * back on only *after* it goes off, because this ROM's Bluetooth service
     * throws inside enable()/disable() while airplane mode is active.
     */
    fun runOrdered(commands: List<String>): List<ExecResult> = commands.map { run(it) }
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
 * Shizuku dependency: the bridge owns binding, permission and process spawn, and
 * this class only sees "run this script, give me the exit code".
 */
class ShellExecutor(
    private val runner: (String) -> ExecResult
) : PowerExecutor {

    override val capability = PowerCapability.SHELL

    override fun available() = PowerCapabilityDetector.current() == PowerCapability.SHELL

    override fun run(command: String) = runner(command)

    override fun runOrdered(commands: List<String>): List<ExecResult> =
        if (commands.isEmpty()) emptyList() else runChunk(commands)

    /**
     * Wraps every command in `{ ... ; } 2>&1` and follows it with a marker line
     * carrying its exit code, then runs the whole script in one shell. Output
     * between two markers belongs to the command before the first of them.
     */
    override fun runBatch(commands: List<String>): List<ExecResult> {
        if (commands.size <= 1) return commands.map { run(it) }

        // Chunked so one script stays small, then a few chunks run at once.
        // Each command is still a process spawn inside the shell (~40ms on the
        // HiBreak), so batching alone barely helps - parallelism is the win.
        val chunks = commands.chunked(CHUNK)
        val perChunk = arrayOfNulls<List<ExecResult>>(chunks.size)
        val pool = Executors.newFixedThreadPool(minOf(PARALLEL, chunks.size))
        try {
            val futures = chunks.mapIndexed { index, chunk ->
                pool.submit { perChunk[index] = runChunk(chunk) }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        return chunks.indices.flatMap { perChunk[it] ?: emptyList() }
    }

    private fun runChunk(commands: List<String>): List<ExecResult> {
        val script = StringBuilder()
        for (command in commands) {
            script.append("{ ").append(command).append(" ; } 2>&1").append('\n')
            script.append("echo \"").append(MARKER).append("\"$?").append('\n')
        }

        val batch = runner(script.toString())
        val parsed = parse(batch.output, commands.size)
        // A batch that never produced markers means the shell itself failed;
        // report that against every command so the caller rolls the whole
        // transition back instead of half-applying it.
        if (parsed.all { it == null }) {
            val failure = ExecResult(batch.exitCode, batch.output)
            return List(commands.size) { failure }
        }
        return commands.indices.map { index ->
            parsed[index] ?: ExecResult(-1, "missing result for batch item $index")
        }
    }

    /** One entry per command; null when that command produced no marker. */
    private fun parse(output: String, count: Int): List<ExecResult?> {
        val results = arrayOfNulls<ExecResult>(count)
        val pending = StringBuilder()
        var index = 0
        for (line in output.lineSequence()) {
            val marker = line.indexOf(MARKER)
            if (marker >= 0 && index < count) {
                // A command without a trailing newline leaves the marker glued
                // to its last output line, so split at the marker instead of
                // assuming it starts the line.
                pending.append(line.substring(0, marker))
                val code = line.substring(marker + MARKER.length).trim().toIntOrNull() ?: -1
                results[index] = ExecResult(code, pending.toString().trim())
                pending.setLength(0)
                index++
            } else if (index < count) {
                pending.append(line).append('\n')
            }
        }
        return results.toList()
    }

    private companion object {
        /** Unlikely to appear in `cmd appops get` / `dumpsys` output. */
        const val MARKER = "__HBZ_EXIT__"
        const val CHUNK = 32
        const val PARALLEL = 8
    }
}
