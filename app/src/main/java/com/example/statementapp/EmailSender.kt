package com.example.statementapp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object EmailSender {

    suspend fun sendEmail(senderEmail: String, appPassword: String, recipientEmail: String, subject: String, body: String) {
        withContext(Dispatchers.IO) {
            try {
                val properties = Properties().apply {
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.host", "smtp.gmail.com")
                    put("mail.smtp.port", "587")
                }

                val session = Session.getInstance(properties, object : javax.mail.Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(senderEmail, appPassword)
                    }
                })

                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(senderEmail))
                    addRecipient(Message.RecipientType.TO, InternetAddress(recipientEmail))
                    this.subject = subject
                    setText(body)
                }

                Transport.send(message)
                Log.d("EmailSender", "Email sent successfully!")
            } catch (e: Exception) {
                Log.e("EmailSender", "Failed to send email: ${e.message}")
            }
        }
    }
}
