package com.smmpaysync.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Dedicated SMS job service.
 * The receiver only hands the received SMS to this foreground service; the service
 * immediately promotes itself to foreground and then saves + syncs the SMS.
 */
class SmsForegroundService : Service() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen immediately after the service starts.
        startForeground(Notifier.ID + 10, Notifier.build(this))

        val sender = intent?.getStringExtra(EXTRA_SENDER).orEmpty()
        val body = intent?.getStringExtra(EXTRA_BODY).orEmpty()

        executor.execute {
            try {
                val app = applicationContext
                val provider = Providers.byHeader(sender)
                if (provider == null || body.isBlank()) {
                    Prefs(app).note("FGS: SMS ignored (unknown sender or empty body)")
                } else {
                    // Save first so the event cannot be lost if the network is unavailable.
                    val saved = SmsIngest.handle(app, provider, body, sendInline = true)
                    Prefs(app).note(if (saved) "FGS: SMS processed" else "FGS: SMS already known/ignored")
                }
            } catch (e: Exception) {
                Prefs(applicationContext).note("FGS error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SENDER = "sender"
        private const val EXTRA_BODY = "body"

        fun start(ctx: Context, sender: String, body: String) {
            val i = Intent(ctx.applicationContext, SmsForegroundService::class.java)
                .putExtra(EXTRA_SENDER, sender)
                .putExtra(EXTRA_BODY, body)
            ContextCompat.startForegroundService(ctx.applicationContext, i)
        }
    }
}
