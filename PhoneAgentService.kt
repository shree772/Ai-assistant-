package com.example.phoneagent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PhoneAgentService : AccessibilityService() {
    companion object { var instance: PhoneAgentService? = null }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val history = JSONArray()
    private var job: Job? = null
    private var nodes = listOf<AccessibilityNodeInfo>()

    private val system = """You control an Android phone through its screen. Each turn you get the visible screen as a numbered list.
Reply with ONLY one JSON object, no other text. Actions:
{"action":"open_app","name":"WhatsApp"}
{"action":"click","index":3}
{"action":"type","text":"hello"}   (types into the focused or first text field)
{"action":"scroll"}
{"action":"back"} {"action":"home"}
{"action":"ask","message":"..."}   (ask the user a question and wait for the answer)
{"action":"done","message":"short result in the user's language"}
Rules: take one small step at a time. Before sending any message, making a payment, buying, deleting, or changing settings, use "ask" first and only proceed after the user says yes. Reply in the user's language (Hindi/Hinglish/English)."""

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; scope.cancel(); super.onDestroy() }

    fun runGoal(goal: String, apiKey: String, onStatus: (String) -> Unit) {
        job?.cancel()
        job = scope.launch {
            var prefix = "User: $goal\n\n"
            try {
                for (step in 1..15) {
                    delay(1200)
                    history.put(msg("user", prefix + "Screen:\n" + describeScreen())); prefix = ""
                    while (history.length() > 12) { history.remove(0); history.remove(0) }
                    val reply = withContext(Dispatchers.IO) { callClaude(apiKey) }
                    history.put(msg("assistant", reply))
                    val a = JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1))
                    onStatus("Step $step: ${a.optString("action")}")
                    when (a.optString("action")) {
                        "click" -> click(a.getInt("index"))
                        "type" -> type(a.getString("text"))
                        "scroll" -> firstScrollable(rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                        "open_app" -> openApp(a.getString("name"))
                        "ask", "done" -> {
                            val m = a.optString("message"); onStatus(m)
                            Toast.makeText(this@PhoneAgentService, m, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                    }
                }
                onStatus("15 step ho gaye, ruk gaya.")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { onStatus("Error: ${e.message}") }
        }
    }

    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    private fun callClaude(key: String): String {
        val body = JSONObject().put("model", "claude-sonnet-5-5").put("max_tokens", 300)
            .put("system", system).put("messages", history)
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = (if (c.responseCode == 200) c.inputStream else c.errorStream).bufferedReader().readText()
        if (c.responseCode != 200) throw Exception("API ${c.responseCode}: $text")
        return JSONObject(text).getJSONArray("content").getJSONObject(0).getString("text")
    }

    private fun describeScreen(): String {
        val root = rootInActiveWindow ?: return "(screen khaali)"
        val list = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            if (list.size >= 80) return
            val label = (n.text ?: n.contentDescription)?.toString().orEmpty()
            if (n.isVisibleToUser && (n.isClickable || n.isEditable || label.isNotBlank())) list.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root); nodes = list
        return "App: ${root.packageName}\n" + list.mapIndexed { i, n ->
            val label = (n.text ?: n.contentDescription)?.toString().orEmpty().take(80)
            "$i ${if (n.isEditable) "[input]" else if (n.isClickable) "[button]" else "[text]"} $label"
        }.joinToString("\n")
    }

    private fun click(i: Int) {
        var n = nodes.getOrNull(i)
        while (n != null && !n.isClickable) n = n.parent
        n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun type(text: String) {
        val t = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: nodes.firstOrNull { it.isEditable }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        t?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun firstScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) firstScrollable(n.getChild(i))?.let { return it }
        return null
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val hit = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) } ?: return
        pm.getLaunchIntentForPackage(hit.activityInfo.packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
