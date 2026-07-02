// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aicommand

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * SurfBoard AI command mode.
 *
 * The user types a casual request. This activity sends it to xAI (Grok) with a system
 * prompt asking for a clean mobilerun instruction back, then POSTs that instruction to
 * the local mobilerun HTTP endpoint (default: http://127.0.0.1:8080/run) for execution.
 *
 * Kept fully decoupled from the keyboard's input pipeline: this is a standalone screen
 * reachable from a toolbar key, talking to mobilerun only over local HTTP.
 */
class AiCommandActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var commandInput: EditText
    private lateinit var statusText: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var endpointInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        prefs = getSharedPreferences("ai_command_prefs", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#1B1B1B"))
        }

        val title = TextView(this).apply {
            text = "AI Command"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        commandInput = EditText(this).apply {
            hint = "Type what you want done…"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        root.addView(commandInput)

        val runButton = Button(this).apply {
            text = "Rewrite & Run"
            setOnClickListener { onRunClicked() }
        }
        root.addView(runButton)

        statusText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, 24, 0, 24)
        }
        root.addView(statusText)

        val settingsTitle = TextView(this).apply {
            text = "Settings"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 8)
        }
        root.addView(settingsTitle)

        apiKeyInput = EditText(this).apply {
            hint = "xAI API key"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString(PREF_API_KEY, ""))
        }
        root.addView(apiKeyInput)

        endpointInput = EditText(this).apply {
            hint = "mobilerun endpoint"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(prefs.getString(PREF_ENDPOINT, DEFAULT_ENDPOINT))
        }
        root.addView(endpointInput)

        val saveButton = Button(this).apply {
            text = "Save settings"
            setOnClickListener {
                prefs.edit()
                    .putString(PREF_API_KEY, apiKeyInput.text.toString().trim())
                    .putString(PREF_ENDPOINT, endpointInput.text.toString().trim().ifEmpty { DEFAULT_ENDPOINT })
                    .apply()
                Toast.makeText(this@AiCommandActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(saveButton)

        val closeButton = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }
        root.addView(closeButton)

        val scroll = ScrollView(this).apply {
            addView(root)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(scroll)
    }

    private fun onRunClicked() {
        val casualText = commandInput.text.toString().trim()
        if (casualText.isEmpty()) {
            statusText.text = "Type a request first."
            return
        }
        val apiKey = prefs.getString(PREF_API_KEY, "")?.trim().orEmpty()
        val endpoint = prefs.getString(PREF_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        if (apiKey.isEmpty()) {
            statusText.text = "Set your xAI API key in Settings below first."
            return
        }
        statusText.text = "Rewriting with Grok…"
        Thread {
            try {
                val instruction = callXai(apiKey, casualText)
                runOnUiThread { statusText.text = "Sending to mobilerun…" }
                val dispatchResult = dispatchToMobilerun(endpoint, instruction)
                runOnUiThread {
                    statusText.text = "Instruction: $instruction\n\nmobilerun: $dispatchResult"
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Error: ${e.message}" }
            }
        }.start()
    }

    /** Calls xAI's OpenAI-compatible chat completions endpoint and returns the rewritten instruction text. */
    private fun callXai(apiKey: String, casualText: String): String {
        val url = URL("https://api.x.ai/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 30000

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "You rewrite a casual spoken/typed request into a single, precise, unambiguous " +
                        "instruction for the mobilerun Android automation agent. Output ONLY the rewritten " +
                        "instruction as plain text, no quotes, no preamble, no explanation."
                )
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", casualText)
            })
        }
        val body = JSONObject().apply {
            put("model", "grok-4")
            put("messages", messages)
            put("temperature", 0.2)
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseText = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("xAI HTTP $code: $responseText")

        val json = JSONObject(responseText)
        val content = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        return content.trim()
    }

    /** POSTs the rewritten instruction to the local mobilerun HTTP endpoint. */
    private fun dispatchToMobilerun(endpoint: String, instruction: String): String {
        return try {
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 15000

            val payload = JSONObject().apply { put("instruction", instruction) }
            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""
            "HTTP $code $responseText"
        } catch (e: Exception) {
            "not reachable (${e.message}) — instruction copied to status above, run it manually in mobilerun"
        }
    }

    companion object {
        private const val PREF_API_KEY = "xai_api_key"
        private const val PREF_ENDPOINT = "mobilerun_endpoint"
        private const val DEFAULT_ENDPOINT = "http://127.0.0.1:8080/run"
    }
}
