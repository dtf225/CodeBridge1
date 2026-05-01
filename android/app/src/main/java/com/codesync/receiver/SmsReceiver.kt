package com.codesync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import com.codesync.service.WebSocketService
import com.codesync.util.CodeExtractor

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        for (message in messages) {
            val sender = message.originatingAddress ?: "未知号码"
            val body = message.messageBody ?: continue

            Log.d(TAG, "收到短信来自 $sender: ${body.take(30)}...")

            val code = CodeExtractor.extract(body)
            if (code != null) {
                Log.d(TAG, "提取到验证码: $code")

                val serviceIntent = Intent(context, WebSocketService::class.java).apply {
                    action = WebSocketService.ACTION_SEND_SMS
                    putExtra(WebSocketService.EXTRA_CODE, code)
                    putExtra(WebSocketService.EXTRA_SOURCE, sender)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
