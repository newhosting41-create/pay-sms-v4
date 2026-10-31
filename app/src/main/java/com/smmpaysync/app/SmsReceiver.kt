package com.smmpaysync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Receives SMS immediately and hands it to the dedicated foreground service. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (msgs.isNullOrEmpty()) return

        val sender = msgs.first().originatingAddress.orEmpty()
        val body = msgs.joinToString("") { it.messageBody.orEmpty() }
        val provider = Providers.byHeader(sender)
        if (provider == null) return

        try {
            // Same core pattern as the reference APK: SMS_RECEIVED -> dedicated FGS -> HTTP sync.
            SmsForegroundService.start(context.applicationContext, sender, body)
        } catch (e: Exception) {
            // Last-resort fallback: persist first and let WorkManager retry later.
            val pending = goAsync()
            Thread {
                try {
                    SmsIngest.handle(context.applicationContext, provider, body, sendInline = false)
                    SyncWorker.enqueue(context.applicationContext)
                    Prefs(context.applicationContext).note("SMS FGS start failed; queued for retry: ${e.javaClass.simpleName}")
                } catch (x: Exception) {
                    Prefs(context.applicationContext).note("SMS fallback failed: ${x.message}")
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}
