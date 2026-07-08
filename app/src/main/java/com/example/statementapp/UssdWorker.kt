package com.example.statementapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UssdWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = context.getSharedPreferences("MetricsPrefs", Context.MODE_PRIVATE)
        val securePrefs = context.getSharedPreferences("SecurePrefs", Context.MODE_PRIVATE)
        val currentCount = prefs.getInt("request_count", 0)
        val currentTime = System.currentTimeMillis()

        prefs.edit()
            .putInt("request_count", currentCount + 1)
            .putLong("last_request_time_ms", currentTime)
            .apply()

        // ── Activate accessibility session SYNCHRONOUSLY (no broadcast timing race) ──
        MpesaUssdService.startSession(context, CoroutineScope(Dispatchers.Main))

        // ── Dial USSD on SIM 1 / Safaricom account ────────────────────────────
        val ussdCode = "*334#"
        val encodedHash = Uri.encode("#")
        val ussdString = ussdCode.replace("#", encodedHash)
        var launched = false

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                val phoneAccounts = telecomManager.callCapablePhoneAccounts
                if (phoneAccounts.isNotEmpty()) {
                    val simAccount = phoneAccounts.firstOrNull { handle ->
                        val acc = telecomManager.getPhoneAccount(handle)
                        acc?.label?.toString()?.contains("safaricom", ignoreCase = true) == true ||
                        acc?.label?.toString()?.contains("saf", ignoreCase = true) == true
                    } ?: phoneAccounts[0]   // always SIM 1 if no label match

                    val extras = android.os.Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, simAccount)
                        putInt("com.android.phone.extra.slot", 0)
                        putInt("simSlot", 0)
                    }
                    @Suppress("DEPRECATION")
                    telecomManager.placeCall(Uri.parse("tel:$ussdString"), extras)
                    launched = true
                    android.util.Log.d("UssdWorker", "Launched via TelecomManager on: ${simAccount.id}")
                }
            } catch (e: Exception) {
                android.util.Log.e("UssdWorker", "TelecomManager.placeCall failed: ${e.message}")
            }
        }

        // Fallback: Intent-based call (accessibility service handles SIM picker)
        if (!launched) {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$ussdString")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("com.android.phone.extra.slot", 0)
                putExtra("simSlot", 0)
            }
            try {
                context.startActivity(intent)
            } catch (e: SecurityException) {
                MpesaUssdService.endSession(context)
                return Result.failure()
            }
        }

        val email   = securePrefs.getString("email", "") ?: ""
        val appPass = securePrefs.getString("app_password", "") ?: ""

        // ── SMS Inbox Polling — primary detection for Huawei/EMUI ────────────
        // Huawei EMUI blocks SMS_RECEIVED broadcasts to third-party apps.
        // Polling content://sms/inbox is reliable on ALL devices.
        withContext(Dispatchers.IO) {
            var elapsed = 0
            var handled = false

            while (elapsed < 300 && !isStopped && !handled) {
                delay(5000)
                elapsed += 5

                // Check if SmsReceiver already handled it (it clears last_request_time_ms to 0)
                val updatedPrefs = context.getSharedPreferences("MetricsPrefs", Context.MODE_PRIVATE)
                if (updatedPrefs.getLong("last_request_time_ms", -1L) == 0L) {
                    android.util.Log.d("UssdWorker", "SMS handled by SmsReceiver — stopping poll")
                    MpesaUssdService.endSession(context)
                    handled = true
                    break
                }

                // Poll SMS inbox for a Safaricom message newer than our dial time
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                    == PackageManager.PERMISSION_GRANTED) {
                    try {
                        val cursor = context.contentResolver.query(
                            Uri.parse("content://sms/inbox"),
                            arrayOf("address", "body", "date"),
                            "date > ?",
                            arrayOf((currentTime - 120_000L).toString()),
                            "date DESC"
                        )
                        cursor?.use {
                            while (it.moveToNext() && !handled) {
                                val sender = it.getString(it.getColumnIndexOrThrow("address")) ?: ""
                                val body   = it.getString(it.getColumnIndexOrThrow("body")) ?: ""
                                val isSafaricom = sender.contains("MPESA", ignoreCase = true) ||
                                    sender.contains("M-PESA", ignoreCase = true) ||
                                    sender.contains("Safaricom", ignoreCase = true) ||
                                    sender.contains("SAF", ignoreCase = true) ||
                                    sender == "22006" || sender == "22141"

                                if (isSafaricom) {
                                    android.util.Log.d("UssdWorker", "Inbox poll hit: $sender — ${body.take(60)}")
                                    when {
                                        body.contains("unable to process", ignoreCase = true) ||
                                        body.contains("currently underway", ignoreCase = true) -> {
                                            FailureHandler.handleFailure(context, "Rate Limited: $body")
                                            handled = true
                                        }
                                        body.contains("statement", ignoreCase = true) ||
                                        body.contains("requested", ignoreCase = true) ||
                                        body.contains("sent to", ignoreCase = true) -> {
                                            SuccessHandler.handleSuccess(context, "SMS Inbox Poll")
                                            handled = true
                                        }
                                    }
                                }
                            }
                        }
                        if (handled) MpesaUssdService.endSession(context)
                    } catch (e: Exception) {
                        android.util.Log.e("UssdWorker", "SMS inbox poll error: ${e.message}")
                    }
                }
            }

            // Timeout — no SMS after 5 minutes
            if (!handled && !isStopped) {
                val updatedPrefs = context.getSharedPreferences("MetricsPrefs", Context.MODE_PRIVATE)
                if (updatedPrefs.getLong("last_request_time_ms", 0L) == currentTime) {
                    DatabaseHelper(context).insertLog(
                        RequestLog(timestamp = System.currentTimeMillis(), latencyMs = 300_000L, status = "Timeout")
                    )
                    updatedPrefs.edit().putLong("last_request_time_ms", 0L).apply()
                    if (email.isNotEmpty() && appPass.isNotEmpty()) {
                        EmailSender.sendEmail(email, appPass, email,
                            "⚠️ M-Pesa Statement: Timeout",
                            "No response received after 5 minutes. Safaricom services may be degraded."
                        )
                    }
                    NotificationHelper.showNotification(context, "⏱ Safaricom Timeout", "No response after 5 minutes.")
                }
                MpesaUssdService.endSession(context)
            }
        }

        return Result.success()
    }
}
