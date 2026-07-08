package com.example.statementapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val sender = sms.displayOriginatingAddress ?: ""
                val body = sms.displayMessageBody ?: ""
                val isSafaricom = sender.contains("MPESA", ignoreCase = true) || sender.contains("M-PESA", ignoreCase = true) || sender.contains("Safaricom", ignoreCase = true)
                
                if (isSafaricom) {
                    if (body.contains("unable to process", ignoreCase = true) || body.contains("currently underway", ignoreCase = true)) {
                        FailureHandler.handleFailure(context, "Rate Limited: $body")
                    } else if (body.contains("statement", ignoreCase = true) || body.contains("requested", ignoreCase = true) || body.contains("received", ignoreCase = true)) {
                        SuccessHandler.handleSuccess(context, "SMS")
                    }
                }
            }
        }
    }
}

object SuccessHandler {
    fun handleSuccess(context: Context, source: String) {
        val metricsPrefs = context.getSharedPreferences("MetricsPrefs", Context.MODE_PRIVATE)
        val securePrefs = context.getSharedPreferences("SecurePrefs", Context.MODE_PRIVATE)
        
        val requestTime = metricsPrefs.getLong("last_request_time_ms", 0L)
        if (requestTime == 0L) {
            Log.d("SuccessHandler", "Ignored duplicate success from $source")
            return // Already handled
        }
        
        val currentTime = System.currentTimeMillis()
        val latency = currentTime - requestTime
        
        val successCount = metricsPrefs.getInt("success_count", 0) + 1
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timeStr = sdf.format(Date(currentTime))
        
        metricsPrefs.edit()
            .putInt("success_count", successCount)
            .putString("last_success_time", timeStr)
            .putLong("last_latency_ms", latency)
            .putLong("last_request_time_ms", 0L) // Reset to prevent double processing
            .apply()
            
        DatabaseHelper(context).insertLog(RequestLog(timestamp = currentTime, latencyMs = latency, status = "Success"))
        
        val email = securePrefs.getString("email", "") ?: ""
        val appPassword = securePrefs.getString("app_password", "") ?: ""
        if (email.isNotEmpty() && appPassword.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                EmailSender.sendEmail(
                    senderEmail = email,
                    appPassword = appPassword,
                    recipientEmail = email,
                    subject = "✅ M-Pesa Statement: Healthy",
                    body = "The automated request was successful via $source.\n\nLatency: ${latency}ms\nTime: $timeStr"
                )
            }
        }
        // End session guard
        MpesaUssdService.endSession(context)
        NotificationHelper.showNotification(context, "✅ Safaricom is UP!", "Statement received successfully.\nLatency: ${latency}ms")
        Log.d("SuccessHandler", "Statement received via $source! Latency: ${latency}ms")
    }
}

object FailureHandler {
    fun handleFailure(context: Context, reason: String) {
        val securePrefs = context.getSharedPreferences("SecurePrefs", Context.MODE_PRIVATE)
        val metricsPrefs = context.getSharedPreferences("MetricsPrefs", Context.MODE_PRIVATE)
        
        val currentTime = System.currentTimeMillis()
        DatabaseHelper(context).insertLog(RequestLog(timestamp = currentTime, latencyMs = 0L, status = "Rate Limited"))
        
        // Reset pending status
        metricsPrefs.edit().putLong("last_request_time_ms", 0L).apply()
        
        val email = securePrefs.getString("email", "") ?: ""
        val appPassword = securePrefs.getString("app_password", "") ?: ""
        if (email.isNotEmpty() && appPassword.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                EmailSender.sendEmail(
                    senderEmail = email,
                    appPassword = appPassword,
                    recipientEmail = email,
                    subject = "⚠️ M-Pesa Statement: Rate Limited",
                    body = "Safaricom rejected the statement request.\n\nReason: $reason"
                )
            }
        }
        // End session guard
        MpesaUssdService.endSession(context)
        NotificationHelper.showNotification(context, "⚠️ Safaricom Rate Limited", "Request rejected: $reason")
        Log.e("FailureHandler", "Statement request failed: $reason")
    }
}