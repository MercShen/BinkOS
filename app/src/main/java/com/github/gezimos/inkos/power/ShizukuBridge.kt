package com.github.gezimos.inkos.power

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Binds the Shizuku service and turns it into a [PowerExecutor].
 *
 * Shizuku is the only elevated tier this branch supports: it grants `adb shell`
 * (uid 2000) rights without root and without unlocking the bootloader, at the
 * cost of a one-time authorisation the user grants in the Shizuku app.
 *
 * Shell commands do not run through `Shizuku.newProcess` - that API is private -
 * but through a bound [RemoteShellService] that Shizuku starts as uid 2000.
 *
 * Everything here is defensive: if the service is absent, the permission was
 * revoked, or the bind fails, the capability drops back to
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

    /** Observable snapshot; updated on every binder/permission/bind change. */
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile
    private var remote: IRemoteShell? = null

    @Volatile
    private var started = false

    @Volatile
    private var bindRequested = false

    private val onPermissionResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        onPermissionResult()
    }

    private val onBinderReceived = Shizuku.OnBinderReceivedListener {
        bindShellService()
        refreshCapability()
    }

    private val onBinderDead = Shizuku.OnBinderDeadListener {
        remote = null
        bindRequested = false
        refreshCapability()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = IRemoteShell.Stub.asInterface(binder)
            refreshCapability()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            refreshCapability()
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, RemoteShellService::class.java.name)
    )
        .daemon(true)
        .version(SERVICE_VERSION)
        .processNameSuffix("shell")
        .debuggable(false)

    /** Registers the service listeners and binds the shell service; idempotent. */
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
        bindShellService()
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

    /** Shows the Shizuku permission dialog; the result is not needed here. */
    fun requestPermission(requestCode: Int) {
        if (!isServiceAvailable()) return
        try {
            Shizuku.requestPermission(requestCode)
        } catch (_: Throwable) {
            // The service can die between the check and the call.
        }
    }

    /** Call after the user answers the Shizuku permission dialog. */
    fun onPermissionResult() {
        bindShellService()
        refreshCapability()
    }

    /** The executor this bridge can offer right now. */
    fun executor(): PowerExecutor =
        if (remote != null) ShellExecutor(::runCommand) else NoopExecutor

    /** True when commands would actually reach a shell process. */
    fun isReady(): Boolean = remote != null

    private fun bindShellService() {
        if (bindRequested || !hasPermission()) return
        bindRequested = true
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (_: Throwable) {
            bindRequested = false
        }
    }

    private fun refreshCapability() {
        _state.value = State(isServiceAvailable(), hasPermission(), remote != null)
        if (remote != null) {
            PowerCapabilityDetector.reportShellAvailable()
        } else {
            PowerCapabilityDetector.reportShellUnavailable()
        }
    }

    /** Executes one command as shell and returns its exit code and output. */
    private fun runCommand(command: String): ExecResult {
        val shell = remote ?: return ExecResult(-1, "shell service not bound")
        return try {
            val reply = shell.run(command) ?: return ExecResult(-1, "empty reply")
            val newline = reply.indexOf('\n')
            if (newline < 0) {
                ExecResult(reply.trim().toIntOrNull() ?: -1, "")
            } else {
                val code = reply.substring(0, newline).trim().toIntOrNull() ?: -1
                ExecResult(code, reply.substring(newline + 1).trim())
            }
        } catch (t: Throwable) {
            ExecResult(-1, t.message ?: t.javaClass.simpleName)
        }
    }

    companion object {
        private const val SERVICE_VERSION = 1

        @Volatile
        private var instance: ShizukuBridge? = null

        fun get(context: Context): ShizukuBridge =
            instance ?: synchronized(this) {
                instance ?: ShizukuBridge(context.applicationContext).also { instance = it }
            }
    }
}
