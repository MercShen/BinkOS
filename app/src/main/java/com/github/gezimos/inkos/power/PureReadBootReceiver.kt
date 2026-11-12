package com.github.gezimos.inkos.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reverts pure-reading mode after a reboot.
 *
 * The mode is deliberately *not* replayed across a restart: the journal that
 * describes how to undo every change is replayed instead, so the device always
 * comes back to normal behaviour. Without this receiver the rollback could only
 * happen when the launcher happened to start, which on this device it does not -
 * Bink OS is not the only home app, and the debug build never starts by itself.
 *
 * The rollback needs a shell, and Shizuku may not be up this early in boot, so:
 *  - the persisted mode is set to NORMAL immediately, so the UI never claims a
 *    mode the device is not in;
 *  - the journal is kept when the shell is unavailable, and
 *    [PureReadingController.reconcileOnStart] retries the next time the app runs.
 */
class PureReadBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                val store = PrefsPowerStore(appContext)
                if (store.loadJournal().isEmpty()) return@Thread

                val controller = PureReadingController(appContext)
                // Truthful UI first: the mode is over, whether or not the undo
                // commands can run right now.
                store.saveMode(PowerMode.NORMAL)

                val bridge = ShizukuBridge.get(appContext)
                bridge.start()

                // Shizuku is started by the user's own service; give it a short
                // window rather than the full boot, because a broadcast receiver
                // is only allowed a few seconds of background execution.
                var waited = 0L
                while (!bridge.isReady() && waited < GRACE_MILLIS) {
                    Thread.sleep(POLL_MILLIS)
                    waited += POLL_MILLIS
                }

                if (bridge.isReady()) {
                    controller.useExecutor(bridge.executor())
                    controller.reconcileOnStart()
                } else {
                    android.util.Log.i(
                        TAG,
                        "shell unavailable after reboot; journal kept for the next app start"
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "boot rollback failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "PureReadBoot"
        const val GRACE_MILLIS = 5_000L
        const val POLL_MILLIS = 250L
    }
}
