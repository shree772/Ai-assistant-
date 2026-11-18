package com.example.phoneagent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var goalBox: EditText
    private lateinit var keyBox: EditText

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40) }
        keyBox = EditText(this).apply { hint = "Anthropic API key"; setText(prefs.getString("key", "")) }
        goalBox = EditText(this).apply { hint = "Kaam batao (jaise: WhatsApp kholo)" }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 30, 0, 0) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        col.addView(keyBox, LinearLayout.LayoutParams(MATCH_PARENT, -2))
        col.addView(btn("1. Accessibility ON karo") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        col.addView(goalBox, LinearLayout.LayoutParams(MATCH_PARENT, -2))
        col.addView(btn("🎤 Bolo") {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            try { startActivityForResult(i, 1) } catch (e: Exception) { status.text = "Voice input nahi mila, type karo." }
        })
        col.addView(btn("Shuru karo") { run(prefs) })
        col.addView(status)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    @Deprecated("simple")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK) {
            goalBox.setText(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: "")
            run(getSharedPreferences("p", MODE_PRIVATE))
        }
    }

    private fun run(prefs: android.content.SharedPreferences) {
        val key = keyBox.text.toString().trim(); val goal = goalBox.text.toString().trim()
        prefs.edit().putString("key", key).apply()
        val svc = PhoneAgentService.instance
        when {
            key.isEmpty() || goal.isEmpty() -> status.text = "API key aur kaam dono daalo."
            svc == null -> status.text = "Pehle Accessibility ON karo (Installed apps > Phone Agent)."
            else -> { status.text = "Chal raha hai…"; svc.runGoal(goal, key) { status.text = it } }
        }
    }
}
