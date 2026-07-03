// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aicommand

import android.app.Activity
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
 * The user types a casual request. xAI (Grok) rewrites it into a structured action matching
 * Mobilerun Portal's real REST API, and that action is dispatched directly to the Portal app
 * running on-device — no mobilerun CLI process required.
 *
 * Portal's actual surface (confirmed from the app's Connection Details screen):
 *   GET  /a11y_tree  /a11y_tree_full  /phone_state  /state  /ping  /packages  /screenshot
 *   POST /keyboard/input  /keyboard/clear  /keyboard/key  /overlay_offset
 * All requests are sent with the Portal's pairing token.
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

        baseUrlInput = EditText(this).apply {
            hint = "Mobilerun Portal base URL"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL))
        }
        root.addView(baseUrlInput)

        tokenInput = EditText(this).apply {
            hint = "Portal pairing token (from Connection Details)"
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
        val casualText = commandInput.text.toString().trim()
        if (casualText.isEmpty()) { statusText.text = "Type a request first."; return }
        val apiKey = prefs.getString(PREF_API_KEY, "")?.trim().orEmpty()
        val baseUrl = (prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL).trimEnd('/')
        val token = prefs.getString(PREF_TOKEN, "")?.trim().orEmpty()
        if (apiKey.isEmpty()) { statusText.text = "Set your xAI API key first."; return }
        if (token.isEmpty()) { statusText.text = "Set the Portal pairing token first."; return }
        val appContext = applicationContext
        Toast.makeText(appContext, "Running…", Toast.LENGTH_SHORT).show()
        finish()
        Thread {
            try {
                Thread.sleep(700)
                autonomousLoop(apiKey, baseUrl, token, casualText, appContext)
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "Error: ${e.message?.take(150)}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun autonomousLoop(apiKey: String, baseUrl: String, token: String, goal: String, ctx: android.content.Context) {
        val MAX_STEPS = 12
        val MAX_STUCK = 3
        var stuckCount = 0
        var lastActionKey = ""
        val history = StringBuilder()
        for (step in 1..MAX_STEPS) {
            val screen = try { portalGet(baseUrl, token, "/a11y_tree") } catch (e: Exception) { "unavailable" }
            val decision = callXai(apiKey, goal, screen, history.toString())
            if (decision.done) {
                Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, "Done: ${decision.reason}", Toast.LENGTH_LONG).show() }
                return
            }
            val key = "${decision.action.method}${decision.action.endpoint}${decision.action.body}"
            if (key == lastActionKey || decision.stuck) {
                stuckCount++
                if (stuckCount >= MAX_STUCK) {
                    Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, "Stopped: ${decision.reason}", Toast.LENGTH_LONG).show() }
                    return
                }
            } else { stuckCount = 0 }
            lastActionKey = key
            try { dispatchToPortal(baseUrl, token, decision.action) } catch (e: Exception) { }
            history.append("Step $step: ${decision.action.method} ${decision.action.endpoint}
")
            Thread.sleep(800)
        }
        Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, "Reached max steps", Toast.LENGTH_LONG).show() }
    }

        private data class PortalAction(val method: String, val endpoint: String, val body: JSONObject?)
    private data class AiDecision(val action: PortalAction, val done: Boolean, val stuck: Boolean, val reason: String)

    private fun callXai(apiKey: String, goal: String, screenState: String, history: String): AiDecision {
        val url = URL("https://api.x.ai/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 30000

        val systemPrompt = "You are an autonomous Android controller. Respond ONLY with JSON.
" +
            "Available endpoints: GET /a11y_tree, POST /keyboard/input {text}, POST /keyboard/clear, POST /keyboard/key {key:ENTER|BACKSPACE|BACK|HOME}
" +
            "JSON format: {"method":"POST","endpoint":"/keyboard/input","body":{"text":"hello"},"done":false,"stuck":false,"reason":"typing"}
" +
            "Set done=true when task complete. Set stuck=true if cannot progress."

        val userContent = "Goal: $goal
Screen:
${screenState.take(2000)}
" +
            (if (history.isNotEmpty()) "History:
$history
" else "") +
            "What is the next single action?"

        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            put(JSONObject().apply { put("role", "user"); put("content", userContent) })
        }
        val bodyObj = JSONObject().apply {
            put("model", "grok-4")
            put("messages", messages)
            put("temperature", 0.1)
        }
        OutputStreamWriter(conn.outputStream).use { it.write(bodyObj.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseText = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("xAI HTTP $code")

        var content = JSONObject(responseText)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        if (content.startsWith("```")) content = content.substringAfter("
").substringBeforeLast("```").trim()

        val j = JSONObject(content)
        val action = PortalAction(
            method = j.getString("method").uppercase(),
            endpoint = j.getString("endpoint"),
            body = if (j.isNull("body")) null else j.optJSONObject("body")
        )
        return AiDecision(action, j.optBoolean("done", false), j.optBoolean("stuck", false), j.optString("reason", ""))
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
        return (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
    }

    
