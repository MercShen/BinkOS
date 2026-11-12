package com.github.gezimos.inkos.power

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process

/**
 * Runs inside a Shizuku UserService process, i.e. as uid 2000 (`adb shell`).
 *
 * This is the supported way to execute shell commands through Shizuku:
 * `Shizuku.newProcess` is private in the public API, so the work is delegated to
 * this bound service instead. Nothing here runs in the launcher process, and it
 * only exists while Shizuku keeps it bound.
 */
class RemoteShellService : Service() {

    private val binder = object : IRemoteShell.Stub() {

        override fun run(command: String): String {
            val result = try {
                val process = Runtime.getRuntime()
                    .exec(arrayOf("sh", "-c", command))
                    .also { it.waitFor(COMMAND_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                "${process.exitValue()}\n$output"
            } catch (t: Throwable) {
                "-1\n${t.message ?: t.javaClass.simpleName}"
            }
            return result
        }

        override fun getUid(): Int = Process.myUid()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 15L
    }
}
