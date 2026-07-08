package com.example.statementapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MpesaUssdService : AccessibilityService() {

    private val TAG = "MpesaUssdService"
    private var lastDialogText = ""
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    // SIM picker keywords — Kenya carriers + generic OEM labels
    private val simPickerKeywords = listOf(
        "sim 1", "sim 2", "sim1", "sim2",
        "card 1", "card 2", "slot 1", "slot 2",
        "choose sim", "select sim", "choose a sim", "select a sim",
        "call with", "make call with", "via sim",
        "safaricom", "airtel", "telkom", "faiba"
    )

    companion object {
        private var sessionTimeoutJob: kotlinx.coroutines.Job? = null

        fun startSession(context: android.content.Context, scope: CoroutineScope) {
            context.getSharedPreferences("UssdPrefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("isSessionActive", true).apply()
            sessionTimeoutJob?.cancel()
            sessionTimeoutJob = scope.launch {
                delay(300_000L) // 5-minute safety timeout
                endSession(context)
                android.util.Log.w("MpesaUssdService", "Session timed out — going dormant")
            }
        }

        fun endSession(context: android.content.Context) {
            context.getSharedPreferences("UssdPrefs", android.content.Context.MODE_PRIVATE).edit().putBoolean("isSessionActive", false).apply()
            sessionTimeoutJob?.cancel()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected — dormant until USSD session starts")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Guard 1: Only act during an active USSD session
        val prefs = getSharedPreferences("UssdPrefs", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("isSessionActive", false)) return

        // Guard 2: Only react to window-state/content changes
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val pkg = event.packageName?.toString() ?: ""
        Log.d(TAG, "onAccessibilityEvent: type=${event.eventType}, pkg=$pkg")
        serviceScope.launch {
            delay(1000) // Give the normal USSD dialog time to fully render on screen

            val rootNode = rootInActiveWindow ?: event?.source
            if (rootNode == null) {
                Log.d(TAG, "rootNode is null")
                return@launch
            }

            var inputNode: AccessibilityNodeInfo? = null
            var buttonNode: AccessibilityNodeInfo? = null
            val allText = java.lang.StringBuilder()
            val clickableNodes = mutableListOf<AccessibilityNodeInfo>()

            // Find EditText (Input Field), Button (Send/Reply/OK), clickable items, and collect all text
            findNodes(rootNode, onInputFound = { inputNode = it }, onButtonFound = { buttonNode = it }, allText = allText, clickableNodes = clickableNodes)

            val dialogText = allText.toString().lowercase()
            if (dialogText.trim().isEmpty()) return@launch
            
            // Guard against Screen Recorders capturing our own UI and causing rogue clicks
            if (dialogText.contains("automator active") || dialogText.contains("start automator")) {
                Log.d(TAG, "Screen contains Statement App UI. Ignoring to prevent rogue clicks.")
                return@launch
            }
            
            Log.d(TAG, "USSD Dialog text: $dialogText")
            android.util.Log.d(TAG, "RAW TEXT SEEN: $dialogText")

            if (dialogText == lastDialogText) {
                android.util.Log.d(TAG, "Skipping identical dialog text to avoid loop.")
                return@launch // Avoid looping on the same dialog
            }

            // Intentional visual delay so the user can see what's happening
            delay(1500)

            var actionTaken = true

            if (inputNode != null && buttonNode != null) {
                // STANDARD USSD (Input field + Send Button)
                when {
                    dialogText.contains("success") || dialogText.contains("received") || dialogText.contains("sent to") || dialogText.contains("accepted") || dialogText.contains("processed") || dialogText.contains("failed") -> {
                        Log.d(TAG, "Final USSD status received on standard dialog: $dialogText")
                        clickNode(buttonNode!!)
                    }
                    dialogText.contains("enter m-pesa pin") || dialogText.contains("enter pin") || (dialogText.contains("pin") && !dialogText.contains("pin manager") && !dialogText.contains("unlock m-pesa pin")) -> {
                        val prefs = getSharedPreferences("SecurePrefs", MODE_PRIVATE)
                        val pin = prefs.getString("mpesa_pin", "") ?: ""
                        if (pin.isNotEmpty()) {
                            inputText(inputNode!!, pin)
                            clickNode(buttonNode!!)
                        } else {
                            Log.e(TAG, "PIN not found in storage!")
                            actionTaken = false
                        }
                    }
                    dialogText.contains("email") || dialogText.contains("e-mail") || dialogText.contains("enter new") -> {
                        val prefs = getSharedPreferences("SecurePrefs", MODE_PRIVATE)
                        val email = prefs.getString("email", "") ?: ""
                        
                        // Safaricom masks email like "mwas***@gmail.com"
                        // Check if the first few chars of the email appear in the dialog
                        val emailPrefix = if (email.length > 4) email.substring(0, 4).lowercase() else email.lowercase()
                        val hasMaskedEmail = emailPrefix.isNotEmpty() && dialogText.contains(emailPrefix) && dialogText.contains("***")

                        if (hasMaskedEmail) {
                            // Find the option number for the masked email. e.g. "1. mwas***@gmail.com"
                            val opt = getOption(dialogText, emailPrefix) ?: "1"
                            android.widget.Toast.makeText(applicationContext, "Email step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, opt)
                            kotlinx.coroutines.delay(200)
                            clickNode(buttonNode!!)
                        } else if (dialogText.contains("enter new")) {
                            val opt = getOption(dialogText, "enter new") ?: "2"
                            android.widget.Toast.makeText(applicationContext, "Enter New Email step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, opt)
                            kotlinx.coroutines.delay(200)
                            clickNode(buttonNode!!)
                        } else if (email.isNotEmpty()) {
                            android.widget.Toast.makeText(applicationContext, "Email step. Typing: $email", android.widget.Toast.LENGTH_SHORT).show()
                            inputText(inputNode!!, email)
                            kotlinx.coroutines.delay(200)
                            clickNode(buttonNode!!)
                        } else {
                            Log.e(TAG, "Email not found in storage!")
                            actionTaken = false
                        }
                    }
                    dialogText.contains("12 months") || dialogText.contains("12 month") || dialogText.contains("1 year") -> {
                        val opt = getOption(dialogText, "12") ?: getOption(dialogText, "year") ?: "6"
                        android.widget.Toast.makeText(applicationContext, "12 Months step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                        inputText(inputNode!!, opt)
                        kotlinx.coroutines.delay(200)
                        clickNode(buttonNode!!)
                    }
                    dialogText.contains("full") -> {
                        val opt = getOption(dialogText, "full") ?: "1"
                        android.widget.Toast.makeText(applicationContext, "Full Statement step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                        inputText(inputNode!!, opt)
                        kotlinx.coroutines.delay(200)
                        clickNode(buttonNode!!)
                    }
                    dialogText.contains("request") -> {
                        val opt = getOption(dialogText, "request") ?: "1"
                        android.widget.Toast.makeText(applicationContext, "Request Statement step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                        inputText(inputNode!!, opt)
                        kotlinx.coroutines.delay(200)
                        clickNode(buttonNode!!)
                    }
                    dialogText.contains("mpesa statement") || dialogText.contains("m-pesa statement") || dialogText.contains("statements") || dialogText.contains("statement") -> {
                        val opt = getOption(dialogText, "m-pesa statement") ?: getOption(dialogText, "mpesa statement") ?: getOption(dialogText, "statement") ?: "3"
                        android.widget.Toast.makeText(applicationContext, "M-PESA Statement step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                        inputText(inputNode!!, opt)
                        kotlinx.coroutines.delay(200)
                        clickNode(buttonNode!!)
                    }
                    dialogText.contains("my account") || dialogText.contains("account") -> {
                        val opt = getOption(dialogText, "my account") ?: getOption(dialogText, "account") ?: "7"
                        android.widget.Toast.makeText(applicationContext, "My Account step. Typing: $opt", android.widget.Toast.LENGTH_SHORT).show()
                        inputText(inputNode!!, opt)
                        kotlinx.coroutines.delay(200)
                        clickNode(buttonNode!!)
                    }
                    dialogText.contains("pin") -> {
                        val prefs = getSharedPreferences("SecurePrefs", MODE_PRIVATE)
                        val pin = prefs.getString("pin", "") ?: ""
                        android.widget.Toast.makeText(applicationContext, "PIN step. Typing PIN", android.widget.Toast.LENGTH_SHORT).show()
                        inputText(inputNode!!, pin)
                        kotlinx.coroutines.delay(200)
                        clickNode(buttonNode!!)
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
                if (!clicked && buttonNode != null && (dialogText.contains("success") || dialogText.contains("received") || dialogText.contains("failed") || dialogText.contains("accept") || dialogText.contains("email"))) {
                    Log.d(TAG, "Final USSD status received: $dialogText")
                    clickNode(buttonNode!!)
                } else if (!clicked) {
                    actionTaken = false
                }
            } else if (buttonNode != null) {
                if (dialogText.contains("success") || dialogText.contains("received") || dialogText.contains("failed") || dialogText.contains("accept") || dialogText.contains("email")) {
                    Log.d(TAG, "Final USSD status received: $dialogText")
                    clickNode(buttonNode!!)
                } else {
                    actionTaken = false
                }
            } else {
                Log.d(TAG, "Input or button node missing. Input: $inputNode, Button: $buttonNode, Clickable Options: ${clickableNodes.size}")
                actionTaken = false
            }
            
            if (actionTaken) {
                lastDialogText = dialogText
            }
        }
    }

    private fun getOption(dialogText: String, keyword: String): String? {
        val regex = Regex("(\\d+)[^0-9a-zA-Z]*$keyword", RegexOption.IGNORE_CASE)
        return regex.find(dialogText)?.groupValues?.get(1)
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
        onButtonFound: (AccessibilityNodeInfo) -> Unit,
        allText: java.lang.StringBuilder,
        clickableNodes: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.text != null) {
            allText.append(node.text).append(" ")
        }
        if (node.contentDescription != null) {
            allText.append(node.contentDescription).append(" ")
        }
        
        if (node.isClickable) {
            clickableNodes.add(node)
        }
        
        if (node.className?.toString() == "android.widget.EditText" || node.isEditable) {
            onInputFound(node)
        } else if (node.className?.toString() == "android.widget.Button") {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val isSend = text.contains("send") || text.contains("ok") || text.contains("reply") || text.contains("yes") || text.contains("submit") ||
                         desc.contains("send") || desc.contains("ok") || desc.contains("reply") || desc.contains("yes") || desc.contains("submit")
            
            if (isSend) {
                onButtonFound(node)
            } else {
                // If it's explicitly a Button but has no recognizable text, store it anyway.
                // We'll use the last one found (usually the positive button on the right).
                onButtonFound(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findNodes(child, onInputFound, onButtonFound, allText, clickableNodes)
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
        MpesaUssdService.endSession(this)
        serviceScope.cancel()
    }
}
