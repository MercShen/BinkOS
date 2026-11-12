package com.github.gezimos.inkos.power

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Binds the Shizuku service and turns it into a [PowerExecutor].
 *
 * Shizuku is the only elevated tier this branch supports: it grants `adb shell`
 * (uid 2000) rights without root and without unlocking the bootloader, at the
 * cost of a one-time authorisation the user grants in the Shizuku app.
 *
 * Command execution goes through `Shizuku.newProcess`, which the service runs as
 * uid 2000. That method is private in the published API, so it is reached by
 * reflection; the alternative - a Shizuku UserService - does not start on this
 * vendor ROM (its LoadedApk throws an NPE inside makeApplicationInner before the
 * service class is even touched, so it is not something the app can fix).
 *
 * Everything here is defensive: if the service is absent, the permission was
 * revoked, or a call fails, the capability drops back to
 * [PowerCapability.NONE] so the UI can grey the toggle out instead of pretending
 * it worked.
 */
class ShizukuBridge private constructor(private val appContext: Context) {

    /** Everything the settings UI needs to describe the Shizuku state. */
    data class State(
        val installed: Boolean = false,
        val granted: Boolean = false,
        val ready: Boolean = false
    )

    private val _state = MutableStateFlow(State())

    /** Observable snapshot; updated on every binder/permission change. */
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var started = false

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { refreshCapability() }

    private val onBinderDead = Shizuku.OnBinderDeadListener { refreshCapability() }

    private val onPermissionResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshCapability()
    }

    /**
     * `Shizuku.newProcess(String[] cmd, String[] env, String dir)`.
     *
     * Private in the shipped artifact, and `isAccessible` on a non-framework
     * class is allowed, so this is a stable-enough seam: if a future Shizuku
     * changes the signature the lookup fails and the capability simply stays
     * NONE instead of crashing.
     */
    private val newProcessMethod: Method? = try {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    } catch (_: Throwable) {
        null
    }

    /** Registers the service listeners; idempotent. */
    fun start() {
        if (started) return
        started = true
        try {
            Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
            Shizuku.addBinderDeadListener(onBinderDead)
            Shizuku.addRequestPermissionResultListener(onPermissionResult)
        } catch (_: Throwable) {
            // Shizuku not installed: capability stays NONE, which is correct.
        }
        refreshCapability()
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            Shizuku.removeBinderReceivedListener(onBinderReceived)
            Shizuku.removeBinderDeadListener(onBinderDead)
            Shizuku.removeRequestPermissionResultListener(onPermissionResult)
        } catch (_: Throwable) {
            // listener removal is best effort
        }
    }

    fun isServiceAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isServiceAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Shows the Shizuku permission dialog; the result arrives via the listener. */
    fun requestPermission(requestCode: Int) {
        if (!isServiceAvailable()) {
            android.util.Log.w(TAG, "requestPermission: service not available")
            return
        }
        try {
            Shizuku.requestPermission(requestCode)
            android.util.Log.i(TAG, "requestPermission: sent, code=$requestCode")
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "requestPermission failed", t)
        }
    }

    /** Re-reads the service state. Call when the settings screen becomes visible. */
    fun refresh() = refreshCapability()

    /** Call after the user answers the Shizuku permission dialog. */
    fun onPermissionResult() = refreshCapability()

    /** The executor this bridge can offer right now. */
    fun executor(): PowerExecutor =
        if (hasPermission() && newProcessMethod != null) ShellExecutor(::runCommand) else NoopExecutor

    /** True when commands would actually reach a shell process. */
    fun isReady(): Boolean = hasPermission() && newProcessMethod != null

    private fun refreshCapability() {
        val installed = isServiceAvailable()
        val granted = hasPermission()
        val ready = granted && newProcessMethod != null
        _state.value = State(installed, granted, ready)
        if (ready) {
            PowerCapabilityDetector.reportShellAvailable()
        } else {
            PowerCapabilityDetector.reportShellUnavailable()
        }
    }

    /** Executes one command as shell and returns its exit code and output. */
    private fun runCommand(command: String): ExecResult {
        val method = newProcessMethod ?: return ExecResult(-1, "newProcess unavailable")
        return try {
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as? Process ?: return ExecResult(-1, "no process returned")

            val buffer = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { buffer.append(it.readText()) }
                } catch (_: Throwable) {
                    // output is best effort; the exit code still matters
                }
            }
            reader.isDaemon = true
            reader.start()

            // ShizukuRemoteProcess throws from exitValue() while the process is still
            // running, and Process.waitFor(timeout) calls exactly that on the
            // timeout path - so poll instead of using the timed wait.
            val deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000
            var code: Int? = null
            while (System.currentTimeMillis() < deadline) {
                code = try {
                    process.exitValue()
                } catch (_: Throwable) {
                    null
                }
                if (code != null) break
                Thread.sleep(50)
            }
            if (code != null) {
                reader.join(1_000)
                android.util.Log.i(TAG, "cmd exit=" + code + " :: " + command)
                ExecResult(code, buffer.toString().trim())
            } else {
                process.destroyForcibly()
                android.util.Log.w(TAG, "cmd timeout :: " + command)
                ExecResult(-1, "timeout after ${TIMEOUT_SECONDS}s")
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "runCommand failed: $command", t)
            ExecResult(-1, t.message ?: t.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "ShizukuBridge"
        private const val TIMEOUT_SECONDS = 60L

        @Volatile
        private var instance: ShizukuBridge? = null

        fun get(context: Context): ShizukuBridge =
            instance ?: synchronized(this) {
                instance ?: ShizukuBridge(context.applicationContext).also { instance = it }
            }
    }
}
