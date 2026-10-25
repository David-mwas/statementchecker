package com.example.statementapp

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MpesaUssdService : AccessibilityService() {

    private val TAG = "MpesaUssdService"
    @Volatile private var lastDialogText = ""
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val eventMutex = Mutex()

    private val sessionClaimReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CLAIM_USSD_SESSION) return
            val owner = intent.getStringExtra("owner") ?: return
            if (owner != packageName) {
                Log.d(TAG, "Yielding USSD session to $owner")
                endSession(this@MpesaUssdService)
            }
        }
    }

    companion object {
        const val ACTION_CLAIM_USSD_SESSION = "com.dmwas.ussd.ACTION_CLAIM_USSD_SESSION"
        private const val PEER_AUTOSENDER_PKG = "com.dmwas.autosendapp"

        @Volatile private var instance: MpesaUssdService? = null
        @Volatile private var isSessionActiveMem: Boolean = false
        private val companionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var sessionTimeoutJob: kotlinx.coroutines.Job? = null

        private fun notifyPeerSessionClaimed(context: Context) {
            try {
                context.sendBroadcast(
                    Intent(ACTION_CLAIM_USSD_SESSION).apply {
                        setPackage(PEER_AUTOSENDER_PKG)
                        putExtra("owner", context.packageName)
                    }
                )
            } catch (e: Exception) {
                Log.w("MpesaUssdService", "Failed to broadcast session claim", e)
            }
        }

        fun startSession(context: Context, scope: CoroutineScope = companionScope) {
            instance?.lastDialogText = ""
            isSessionActiveMem = true
            context.getSharedPreferences("UssdPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("isSessionActive", true)
                .apply()
            notifyPeerSessionClaimed(context)
            sessionTimeoutJob?.cancel()
            sessionTimeoutJob = companionScope.launch {
                delay(60_000L) // 60-second safety timeout for USSD dialog navigation
                endSession(context)
                Log.w("MpesaUssdService", "Session timed out — going dormant")
            }
        }

        fun endSession(context: Context) {
            isSessionActiveMem = false
            instance?.lastDialogText = ""
            context.getSharedPreferences("UssdPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("isSessionActive", false)
                .apply()
            sessionTimeoutJob?.cancel()
        }

        private fun isSessionActive(context: Context): Boolean {
            return isSessionActiveMem ||
                context.getSharedPreferences("UssdPrefs", Context.MODE_PRIVATE).getBoolean("isSessionActive", false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            ContextCompat.registerReceiver(
                this,
                sessionClaimReceiver,
                IntentFilter(ACTION_CLAIM_USSD_SESSION),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register sessionClaimReceiver", e)
        }
        Log.d(TAG, "Service connected — dormant until USSD session starts")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Guard 1: Only act during an active USSD session
        if (!isSessionActive(this)) return

        // Guard 2: Only react to window-state/content changes
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val pkg = event.packageName?.toString() ?: ""
        Log.d(TAG, "onAccessibilityEvent: type=${event.eventType}, pkg=$pkg")
        serviceScope.launch {
            eventMutex.withLock {
                delay(350) // Give the USSD dialog time to fully render on screen

                if (!isSessionActive(this@MpesaUssdService)) return@withLock

                val rootNode = rootInActiveWindow ?: event?.source ?: return@withLock

                var inputNode: AccessibilityNodeInfo? = null
                var positiveButtonNode: AccessibilityNodeInfo? = null
                var fallbackButtonNode: AccessibilityNodeInfo? = null
                var dismissButtonNode: AccessibilityNodeInfo? = null
                val allText = java.lang.StringBuilder()
                val clickableNodes = mutableListOf<AccessibilityNodeInfo>()

                // Find EditText, positive/dismiss buttons, clickable items, and collect non-editable text
                findNodes(
                    node = rootNode,
                    onInputFound = { inputNode = it },
                    onPositiveButtonFound = { positiveButtonNode = it },
                    onFallbackButtonFound = { if (fallbackButtonNode == null) fallbackButtonNode = it },
                    onDismissButtonFound = { if (dismissButtonNode == null) dismissButtonNode = it },
                    allText = allText,
                    clickableNodes = clickableNodes
                )
                val sendButtonNode = positiveButtonNode ?: fallbackButtonNode
                val okOrDismissNode = positiveButtonNode ?: fallbackButtonNode ?: dismissButtonNode

                val dialogText = allText.toString().lowercase()
                if (dialogText.trim().isEmpty()) return@withLock

                // Guard against Screen Recorders or our own / AutoSender's UI causing rogue clicks
                if (dialogText.contains("automator active") ||
                    dialogText.contains("start automator") ||
                    dialogText.contains("autosender")
                ) {
                    Log.d(TAG, "Screen contains App UI. Ignoring to prevent rogue clicks.")
                    return@withLock
                }

                Log.d(TAG, "USSD Dialog text: $dialogText")

                if (dialogText == lastDialogText) {
                    Log.d(TAG, "Skipping identical dialog text to avoid loop.")
                    return@withLock
                }

                delay(300)

                val isFinalMessage = dialogText.contains("success") ||
                    dialogText.contains("received") ||
                    dialogText.contains("sent to") ||
                    dialogText.contains("accepted") ||
                    dialogText.contains("processed") ||
                    dialogText.contains("failed") ||
                    dialogText.contains("unable to process") ||
                    dialogText.contains("currently underway") ||
                    dialogText.contains("wrong pin") ||
                    dialogText.contains("invalid")

                var actionTaken = true

                if (inputNode != null && sendButtonNode != null) {
                    // STANDARD USSD (Input field + Send Button)
                    when {
                        isFinalMessage -> {
                            Log.d(TAG, "Final USSD status received on standard dialog: $dialogText")
                            lastDialogText = dialogText
                            clickNode(okOrDismissNode ?: sendButtonNode)
                            endSession(this@MpesaUssdService)
                            return@withLock
                        }
                        dialogText.contains("enter m-pesa pin") || dialogText.contains("enter pin") || (dialogText.contains("pin") && !dialogText.contains("pin manager") && !dialogText.contains("unlock m-pesa pin")) -> {
                            val prefs = getSharedPreferences("SecurePrefs", MODE_PRIVATE)
                            val pin = prefs.getString("mpesa_pin", "") ?: ""
                            if (pin.isNotEmpty()) {
                                inputText(inputNode!!, pin)
                                delay(50)
                                clickNode(sendButtonNode)
                            } else {
                                Log.e(TAG, "PIN not found in storage!")
                                actionTaken = false
                            }
                        }
                        dialogText.contains("email") || dialogText.contains("e-mail") || dialogText.contains("enter new") -> {
                            val prefs = getSharedPreferences("SecurePrefs", MODE_PRIVATE)
                            val email = prefs.getString("email", "") ?: ""

                            // Safaricom masks email like "mwas***@gmail.com"
                            val emailPrefix = if (email.length > 4) email.substring(0, 4).lowercase() else email.lowercase()
                            val hasMaskedEmail = emailPrefix.isNotEmpty() && dialogText.contains(emailPrefix) && dialogText.contains("***")

                            if (hasMaskedEmail) {
                                val opt = getOption(dialogText, emailPrefix) ?: "1"
                                android.widget.Toast.makeText(applicationContext, "Email step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                                inputText(inputNode!!, opt)
                                delay(50)
                                clickNode(sendButtonNode)
                            } else if (dialogText.contains("enter new")) {
                                val opt = getOption(dialogText, "enter new") ?: "2"
                                android.widget.Toast.makeText(applicationContext, "Enter New Email step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                                inputText(inputNode!!, opt)
                                delay(50)
                                clickNode(sendButtonNode)
                            } else if (email.isNotEmpty()) {
                                android.widget.Toast.makeText(applicationContext, "Email step. Typing: $email", android.widget.Toast.LENGTH_SHORT).show()
                                inputText(inputNode!!, email)
                                delay(50)
                                clickNode(sendButtonNode)
                            } else {
                                Log.e(TAG, "Email not found in storage!")
                                actionTaken = false
                            }
                        }
                        dialogText.contains("12 months") || dialogText.contains("12 month") || dialogText.contains("1 year") -> {
                            val opt = getOption(dialogText, "12") ?: getOption(dialogText, "year") ?: "6"
                            android.widget.Toast.makeText(applicationContext, "12 Months step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, opt)
                            delay(50)
                            clickNode(sendButtonNode)
                        }
                        dialogText.contains("full") -> {
                            val opt = getOption(dialogText, "full") ?: "1"
                            android.widget.Toast.makeText(applicationContext, "Full Statement step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, opt)
                            delay(50)
                            clickNode(sendButtonNode)
                        }
                        dialogText.contains("request") -> {
                            val opt = getOption(dialogText, "request") ?: "1"
                            android.widget.Toast.makeText(applicationContext, "Request Statement step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, opt)
                            delay(50)
                            clickNode(sendButtonNode)
                        }
                        dialogText.contains("mpesa statement") || dialogText.contains("m-pesa statement") || dialogText.contains("statements") || dialogText.contains("statement") -> {
                            val opt = getOption(dialogText, "m-pesa statement") ?: getOption(dialogText, "mpesa statement") ?: getOption(dialogText, "statement") ?: "3"
                            android.widget.Toast.makeText(applicationContext, "M-PESA Statement step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, opt)
                            delay(50)
                            clickNode(sendButtonNode)
                        }
                        dialogText.contains("my account") || dialogText.contains("account") -> {
                            val opt = getOption(dialogText, "my account") ?: getOption(dialogText, "account") ?: "7"
                            android.widget.Toast.makeText(applicationContext, "My Account step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, opt)
                            delay(50)
                            clickNode(sendButtonNode)
                        }
                        dialogText.contains("pin") -> {
                            val prefs = getSharedPreferences("SecurePrefs", MODE_PRIVATE)
                            val pin = prefs.getString("mpesa_pin", "") ?: ""
                            if (pin.isNotEmpty()) {
                                android.widget.Toast.makeText(applicationContext, "PIN step. Typing PIN", android.widget.Toast.LENGTH_SHORT).show()
                                inputText(inputNode!!, pin)
                                delay(50)
                                clickNode(sendButtonNode)
                            } else {
                                actionTaken = false
                            }
                        }
                        else -> {
                            actionTaken = false
                        }
                    }
                } else if (clickableNodes.isNotEmpty()) {
                    // LIST-BASED USSD (Options are clickable lists)
                    val prefs = getSharedPreferences("SecurePrefs", MODE_PRIVATE)
                    val email = prefs.getString("email", "") ?: ""
                    val emailPrefix = if (email.length > 4) email.substring(0, 4).lowercase() else email.lowercase()
                    val hasMaskedEmail = emailPrefix.isNotEmpty() && dialogText.contains(emailPrefix) && dialogText.contains("***")

                    val clicked = when {
                        dialogText.contains("call *334#") || dialogText.contains("call ") || dialogText.contains("select sim") || dialogText.contains("choose sim") -> {
                            clickFirstSimOption(rootNode)
                        }
                        dialogText.contains("email") || dialogText.contains("e-mail") || dialogText.contains("enter m-pesa pin") || dialogText.contains("enter pin") || (dialogText.contains("pin") && !dialogText.contains("pin manager") && !dialogText.contains("unlock m-pesa pin")) -> {
                            Log.e(TAG, "Found email/pin prompt but no input field on a list-based menu!")
                            false
                        }
                        hasMaskedEmail -> clickMatchingNode(clickableNodes, emailPrefix)
                        dialogText.contains("enter new") -> clickMatchingNode(clickableNodes, "enter new")
                        dialogText.contains("12 months") || dialogText.contains("1 year") -> {
                            clickMatchingNode(clickableNodes, "12 months") || clickMatchingNode(clickableNodes, "1 year")
                        }
                        dialogText.contains("full statement") || dialogText.contains("full") -> {
                            clickMatchingNode(clickableNodes, "full statement") || clickMatchingNode(clickableNodes, "full")
                        }
                        dialogText.contains("request statement") || dialogText.contains("request") -> {
                            clickMatchingNode(clickableNodes, "request statement") || clickMatchingNode(clickableNodes, "request")
                        }
                        dialogText.contains("mpesa statement") || dialogText.contains("m-pesa statement") || dialogText.contains("statements") -> {
                            clickMatchingNode(clickableNodes, "m-pesa statement") || clickMatchingNode(clickableNodes, "mpesa statement") || clickMatchingNode(clickableNodes, "statement")
                        }
                        dialogText.contains("my account") || dialogText.contains("account") -> {
                            clickMatchingNode(clickableNodes, "my account") || clickMatchingNode(clickableNodes, "account")
                        }
                        else -> false
                    }

                    // If it's a final message (success/fail) with just an OK button
                    if (!clicked && okOrDismissNode != null && (isFinalMessage || dialogText.contains("accept") || dialogText.contains("email"))) {
                        Log.d(TAG, "Final USSD status received: $dialogText")
                        lastDialogText = dialogText
                        clickNode(okOrDismissNode)
                        endSession(this@MpesaUssdService)
                        return@withLock
                    } else if (!clicked) {
                        actionTaken = false
                    }
                } else if (okOrDismissNode != null) {
                    if (isFinalMessage || dialogText.contains("accept") || dialogText.contains("email")) {
                        Log.d(TAG, "Final USSD status received: $dialogText")
                        lastDialogText = dialogText
                        clickNode(okOrDismissNode)
                        endSession(this@MpesaUssdService)
                        return@withLock
                    } else {
                        actionTaken = false
                    }
                } else {
                    Log.d(TAG, "Input or button node missing. Input: $inputNode, Button: $sendButtonNode, Clickable Options: ${clickableNodes.size}")
                    actionTaken = false
                }

                if (actionTaken) {
                    lastDialogText = dialogText
                }
            }
        }
    }

    private fun getOption(dialogText: String, keyword: String): String? {
        val escaped = Regex.escape(keyword)
        val strictRegex = Regex("(?:^|\\s)([1-9])\\s*[.:)\\-]\\s*[^\\n]*?$escaped", RegexOption.IGNORE_CASE)
        strictRegex.find(dialogText)?.groupValues?.get(1)?.let { return it }

        val fallbackRegex = Regex("(?:^|\\D)([1-9])[^0-9a-zA-Z]+$escaped", RegexOption.IGNORE_CASE)
        return fallbackRegex.find(dialogText)?.groupValues?.get(1)
    }
    
    private fun clickMatchingNode(nodes: List<AccessibilityNodeInfo>, keyword: String): Boolean {
        for (node in nodes) {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            if (text.contains(keyword) || desc.contains(keyword)) {
                clickNode(node)
                return true
            }
        }
        return false
    }

    private fun inputText(node: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNodes(
        node: AccessibilityNodeInfo,
        onInputFound: (AccessibilityNodeInfo) -> Unit,
        onPositiveButtonFound: (AccessibilityNodeInfo) -> Unit,
        onFallbackButtonFound: (AccessibilityNodeInfo) -> Unit,
        onDismissButtonFound: (AccessibilityNodeInfo) -> Unit,
        allText: java.lang.StringBuilder,
        clickableNodes: MutableList<AccessibilityNodeInfo>
    ) {
        val isEditText = node.className?.toString() == "android.widget.EditText" || node.isEditable
        if (!isEditText) {
            if (node.text != null) {
                allText.append(node.text).append(" ")
            }
            if (node.contentDescription != null) {
                allText.append(node.contentDescription).append(" ")
            }
        }

        if (node.isClickable) {
            clickableNodes.add(node)
        }

        if (isEditText) {
            onInputFound(node)
        } else if (node.className?.toString() == "android.widget.Button") {
            val text = node.text?.toString()?.lowercase()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
            val isPositive = text.contains("send") || text.contains("ok") || text.contains("reply") ||
                text.contains("yes") || text.contains("submit") || text.contains("accept") ||
                desc.contains("send") || desc.contains("ok") || desc.contains("reply") ||
                desc.contains("yes") || desc.contains("submit") || desc.contains("accept")
            val isCancel = text.contains("cancel") || text.contains("dismiss") || text.contains("no") ||
                desc.contains("cancel") || desc.contains("dismiss") || desc.contains("no")

            when {
                isPositive -> onPositiveButtonFound(node)
                isCancel -> onDismissButtonFound(node)
                else -> onFallbackButtonFound(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findNodes(
                    node = child,
                    onInputFound = onInputFound,
                    onPositiveButtonFound = onPositiveButtonFound,
                    onFallbackButtonFound = onFallbackButtonFound,
                    onDismissButtonFound = onDismissButtonFound,
                    allText = allText,
                    clickableNodes = clickableNodes
                )
            }
        }
    }



    /**
     * Click the FIRST clickable option by vertical screen position.
     * On all OEMs SIM 1 is always the topmost item in the picker list.
     */
    private fun clickFirstSimOption(root: AccessibilityNodeInfo): Boolean {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, candidates)

        // Filter out the dialog/container itself — we want leaf-level rows
        val leafCandidates = candidates.filter { node ->
            val txt = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val label = txt.ifEmpty { desc }
            // Skip blank nodes and Cancel-type buttons
            label.isNotEmpty() && !label.contains("cancel") && !label.contains("dismiss")
        }

        if (leafCandidates.isEmpty()) {
            // Last resort: search entire tree for Safaricom or SIM 1
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            fun collectAll(n: AccessibilityNodeInfo) {
                allNodes.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { collectAll(it) }
            }
            collectAll(root)

            val safaricomNode = allNodes.firstOrNull { 
                val label = (it.text?.toString() ?: it.contentDescription?.toString() ?: "").lowercase()
                label.contains("safaricom") || label.contains("sim 1") || label.contains("sim1") 
            }

            if (safaricomNode != null) {
                var target: AccessibilityNodeInfo? = safaricomNode
                while (target != null && !target.isClickable) target = target.parent
                if (target != null) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }

            return candidates.firstOrNull { it != root }?.let {
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                true
            } ?: false
        }

        // Sort by Y position on screen — topmost = SIM 1
        val sorted = leafCandidates.sortedBy { node ->
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            rect.top
        }

        sorted.firstOrNull()?.let { node ->
            // Walk up to find the actual clickable ancestor if needed
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        return false
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString() == "android.widget.EditText" || node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findEditText(node.getChild(i) ?: continue)
            if (found != null) return found
        }
        return null
    }

    private fun collectClickable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable) out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectClickable(it, out) }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
        lastDialogText = ""
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(sessionClaimReceiver)
        } catch (_: Exception) {}
        instance = null
        MpesaUssdService.endSession(this)
        serviceScope.cancel()
    }
}
