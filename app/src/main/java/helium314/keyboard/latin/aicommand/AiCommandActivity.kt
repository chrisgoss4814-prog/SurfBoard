// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aicommand

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
 * The user types a natural language goal. xAI (Grok) reads the current screen state
 * and returns a multi-step action plan. Each step is executed against Mobilerun Portal.
 * After each step the screen state is re-read so the AI can decide what to do next.
 * Execution stops when the AI says the task is done OR when it detects it is stuck.
 *
 * Portal endpoints used:
 *   GET  /a11y_tree  /a11y_tree_full  /phone_state  /state  /ping  /packages  /screenshot
 *   POST /keyboard/input  /keyboard/clear  /keyboard/key  /overlay_offset
 */
class AiCommandActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var commandInput: EditText
    private lateinit var statusText: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var baseUrlInput: EditText
    private lateinit var tokenInput: EditText

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
            text = "Run"
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

        baseUrlInput = EditText(this).apply {
            hint = "Mobilerun Portal base URL"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL))
        }
        root.addView(baseUrlInput)

        tokenInput = EditText(this).apply {
            hint = "Portal pairing token"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(prefs.getString(PREF_TOKEN, ""))
        }
        root.addView(tokenInput)

        val saveButton = Button(this).apply {
            text = "Save settings"
            setOnClickListener {
                prefs.edit()
                    .putString(PREF_API_KEY, apiKeyInput.text.toString().trim())
                    .putString(PREF_BASE_URL, baseUrlInput.text.toString().trim().ifEmpty { DEFAULT_BASE_URL })
                    .putString(PREF_TOKEN, tokenInput.text.toString().trim())
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
        val goal = commandInput.text.toString().trim()
        if (goal.isEmpty()) { statusText.text = "Type a request first."; return }
        val apiKey = prefs.getString(PREF_API_KEY, "")?.trim().orEmpty()
        val baseUrl = (prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL).trimEnd('/')
        val token = prefs.getString(PREF_TOKEN, "")?.trim().orEmpty()
        if (apiKey.isEmpty()) { statusText.text = "Set your xAI API key first."; return }
        if (token.isEmpty()) { statusText.text = "Set the Portal pairing token first."; return }

        val appContext = applicationContext
        Toast.makeText(appContext, "Running…", Toast.LENGTH_SHORT).show()
        finish()

        Thread {
            val log = StringBuilder()
            try {
                Thread.sleep(700)
                runAutonomousLoop(apiKey, baseUrl, token, goal, appContext, log)
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "Error: ${e.message?.take(150)}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun runAutonomousLoop(
        apiKey: String,
        baseUrl: String,
        token: String,
        goal: String,
        appContext: Context,
        log: StringBuilder
    ) {
        val MAX_STEPS = 12
        val MAX_STUCK = 3
        var stepCount = 0
        var stuckCount = 0
        var lastAction = ""
        val history = StringBuilder()

        while (stepCount < MAX_STEPS) {
            stepCount++

            // Read current screen state
            val screenState = try {
                portalGet(baseUrl, token, "/a11y_tree")
            } catch (e: Exception) {
                "Screen read failed: ${e.message}"
            }

            // Ask AI what to do next
            val decision = callXai(apiKey, goal, screenState, history.toString())

            if (decision.done) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "Done: ${decision.reason}", Toast.LENGTH_LONG).show()
                }
                return
            }

            if (decision.stuck) {
                stuckCount++
                if (stuckCount >= MAX_STUCK) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(appContext, "Stopped: ${decision.reason}", Toast.LENGTH_LONG).show()
                    }
                    return
                }
            } else {
                stuckCount = 0
            }

            // Detect repeated action (stuck loop)
            val actionKey = "${decision.action.method}${decision.action.endpoint}${decision.action.body}"
            if (actionKey == lastAction) {
                stuckCount++
                if (stuckCount >= MAX_STUCK) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(appContext, "Stopped: repeating same action with no progress", Toast.LENGTH_LONG).show()
                    }
                    return
                }
            }
            lastAction = actionKey

            // Execute the action
            val result = try {
                dispatchToPortal(baseUrl, token, decision.action)
            } catch (e: Exception) {
                "dispatch error: ${e.message}"
            }

            history.append("Step $stepCount: ${decision.action.method} ${decision.action.endpoint} -> ${result.take(200)}
")

            // Brief pause between steps so UI can settle
            Thread.sleep(800)
        }

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "Reached max steps ($MAX_STEPS) without finishing", Toast.LENGTH_LONG).show()
        }
    }

    private data class PortalAction(val method: String, val endpoint: String, val body: JSONObject?)
    private data class AiDecision(
        val action: PortalAction,
        val done: Boolean,
        val stuck: Boolean,
        val reason: String
    )

    private fun callXai(apiKey: String, goal: String, screenState: String, history: String): AiDecision {
        val url = URL("https://api.x.ai/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 30000

        val systemPrompt = """
You are an autonomous Android controller. You see the current screen state and decide the next single action to take toward completing the user's goal.

Portal API endpoints available:
  GET  /a11y_tree        - current accessibility tree
  GET  /a11y_tree_full   - full accessibility tree
  GET  /phone_state      - phone state
  GET  /packages         - installed packages
  GET  /screenshot       - capture screen
  POST /keyboard/input   - body: {"text": "<text to type>"}
  POST /keyboard/clear   - body: {} clears focused field
  POST /keyboard/key     - body: {"key": "ENTER"|"BACKSPACE"|"TAB"|"BACK"|"HOME"|"APP_SWITCH"}

Respond with ONLY a JSON object in this exact format:
{
  "method": "GET" or "POST",
  "endpoint": "/path",
  "body": null or {...},
  "done": true or false,
  "stuck": true or false,
  "reason": "brief explanation of what you are doing or why you stopped"
}

Set done=true when the goal is fully complete.
Set stuck=true when the screen is not responding or you cannot make progress.
Set done=false and stuck=false to continue to the next step.
        """.trimIndent()

        val userContent = """
Goal: $goal

Screen state:
${screenState.take(3000)}

${if (history.isNotEmpty()) "Steps taken so far:
$history" else ""}

What is the single best next action?
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            put(JSONObject().apply { put("role", "user"); put("content", userContent) })
        }
        val body = JSONObject().apply {
            put("model", "grok-4")
            put("messages", messages)
            put("temperature", 0.1)
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseText = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("xAI HTTP $code: $responseText")

        var content = JSONObject(responseText)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
        if (content.startsWith("```")) {
            content = content.substringAfter("
").substringBeforeLast("```").trim()
        }

        val j = JSONObject(content)
        val action = PortalAction(
            method = j.getString("method").uppercase(),
            endpoint = j.getString("endpoint"),
            body = if (j.isNull("body")) null else j.optJSONObject("body")
        )
        return AiDecision(
            action = action,
            done = j.optBoolean("done", false),
            stuck = j.optBoolean("stuck", false),
            reason = j.optString("reason", "")
        )
    }

    private fun portalGet(baseUrl: String, token: String, endpoint: String): String {
        val url = URL(baseUrl + endpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-Auth-Token", token)
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun dispatchToPortal(baseUrl: String, token: String, action: PortalAction): String {
        return try {
            val url = URL(baseUrl + action.endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = action.method
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("X-Auth-Token", token)
            conn.connectTimeout = 5000
            conn.readTimeout = 15000

            if (action.method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(conn.outputStream).use { it.write((action.body ?: JSONObject()).toString()) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""
            "HTTP $code $responseText"
        } catch (e: Exception) {
            "Could not reach Portal at $baseUrl: ${e.message}"
        }
    }

    companion object {
        private const val PREF_API_KEY = "xai_api_key"
        private const val PREF_BASE_URL = "portal_base_url"
        private const val PREF_TOKEN = "portal_token"
        private const val DEFAULT_BASE_URL = "http://127.0.0.1:8080"
    }
}
