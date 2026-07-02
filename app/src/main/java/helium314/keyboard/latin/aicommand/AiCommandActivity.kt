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
        if (casualText.isEmpty()) {
            statusText.text = "Type a request first."
            return
        }
        val apiKey = prefs.getString(PREF_API_KEY, "")?.trim().orEmpty()
        val baseUrl = (prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL).trimEnd('/')
        val token = prefs.getString(PREF_TOKEN, "")?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            statusText.text = "Set your xAI API key in Settings below first."
            return
        }
        if (token.isEmpty()) {
            statusText.text = "Set the Portal pairing token in Settings below first (copy it from Mobilerun Portal > Connection Details)."
            return
        }

        // Close this screen FIRST so the real target app/field regains window and input
        // focus before we dispatch to Portal. Otherwise Portal sees THIS screen as focused
        // (that was the earlier bug: it typed into the AI Command screen's own field).
        val appContext = applicationContext
        Toast.makeText(appContext, "Running…", Toast.LENGTH_SHORT).show()
        finish()

        Thread {
            try {
                Thread.sleep(700) // let the target app's window regain input focus after we close
                val action = callXai(apiKey, casualText)
                val result = dispatchToPortal(baseUrl, token, action)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "${action.method} ${action.endpoint} -> $result".take(200), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "AI Command error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private data class PortalAction(val method: String, val endpoint: String, val body: JSONObject?)

    /** Calls xAI's OpenAI-compatible chat completions endpoint and returns a structured Portal action. */
    private fun callXai(apiKey: String, casualText: String): PortalAction {
        val url = URL("https://api.x.ai/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 30000

        val systemPrompt = """
            You control an Android device through the Mobilerun Portal REST API. Given a casual
            request, output ONLY a single JSON object (no markdown, no explanation) describing the
            one API call to make. Choose method and endpoint from this exact set:

            GET  /a11y_tree        - inspect the current accessibility tree
            GET  /a11y_tree_full   - full accessibility tree with extra detail
            GET  /phone_state      - general phone state
            GET  /state            - portal state
            GET  /ping             - connectivity check
            GET  /packages         - list installed packages
            GET  /screenshot       - capture the current screen
            POST /keyboard/input   - body: {"text": "<string to type into the focused field>"}
            POST /keyboard/clear   - body: {} (clears the focused text field)
            POST /keyboard/key     - body: {"key": "<key name, e.g. ENTER, BACKSPACE, TAB>"}
            POST /overlay_offset   - body: {"offset": <integer>}

            Output format: {"method":"GET"|"POST","endpoint":"/path","body":{...}|null}
            If the request is to type or say something, use POST /keyboard/input with that text.
            If the request asks about current screen/app/state, use the closest matching GET.
            Pick the single best-fitting call. Output nothing but the JSON object.
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
            put(JSONObject().apply { put("role", "user"); put("content", casualText) })
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

        val json = JSONObject(responseText)
        var content = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
        // strip accidental markdown fences
        if (content.startsWith("```")) {
            content = content.substringAfter("\n").substringBeforeLast("```").trim()
        }
        val actionJson = JSONObject(content)
        val method = actionJson.getString("method").uppercase()
        val endpoint = actionJson.getString("endpoint")
        val actionBody = if (actionJson.isNull("body")) null else actionJson.optJSONObject("body")
        return PortalAction(method, endpoint, actionBody)
    }

    /** Sends the structured action directly to Mobilerun Portal, authenticated with the pairing token. */
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
            "HTTP $code\n$responseText"
        } catch (e: Exception) {
            "Could not reach Portal at $baseUrl (${e.message}). Make sure Mobilerun Portal is open and its Socket Status isn't 'Stopped'."
        }
    }

    companion object {
        private const val PREF_API_KEY = "xai_api_key"
        private const val PREF_BASE_URL = "portal_base_url"
        private const val PREF_TOKEN = "portal_token"
        private const val DEFAULT_BASE_URL = "http://127.0.0.1:8080"
    }
}
