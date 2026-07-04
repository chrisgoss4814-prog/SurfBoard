// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.aicommand

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
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

        val viewLogButton = Button(this).apply {
            text = "View last run log"
            setOnClickListener {
                statusText.text = prefs.getString(PREF_LOG, "No log yet. Run a task first.")
            }
        }
        root.addView(viewLogButton)

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
                runAutonomousLoop(apiKey, baseUrl, token, casualText, appContext)
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "AI Command error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private data class AiStep(val action: PortalAction, val done: Boolean, val stuck: Boolean, val reason: String)

    /**
     * Autonomous loop: read screen -> ask Grok for the next single action -> dispatch -> repeat.
     * Auto-continues through long tasks. Stops only when Grok says the task is done, or when it
     * detects it is stuck (same action repeated with no screen change). A high ceiling guards
     * against runaway credit use without cutting off legitimately long tasks.
     */
    private fun runAutonomousLoop(
        apiKey: String,
        baseUrl: String,
        token: String,
        goal: String,
        ctx: android.content.Context
    ) {
        val maxSteps = 40
        val stuckLimit = 3
        var stuckCount = 0
        var lastActionKey = ""
        var lastScreenSig = ""
        val history = StringBuilder()
        val log = StringBuilder()
        val main = Handler(Looper.getMainLooper())

        fun saveLog() { prefs.edit().putString(PREF_LOG, log.toString()).apply() }
        log.append("GOAL: ").append(goal).append("\n\n")
        saveLog()

        var step = 0
        while (step < maxSteps) {
            step++

            // 1. Read the current screen state from Portal.
            val screen = try {
                portalGet(baseUrl, token, "/state")
            } catch (e: Exception) {
                try { portalGet(baseUrl, token, "/a11y_tree") } catch (e2: Exception) { "" }
            }
            val screenSig = screen.hashCode().toString()
            log.append("--- Step ").append(step).append(" ---\n")
            log.append("screen(").append(screen.length).append(" chars): ").append(screen.take(200)).append("\n")
            saveLog()

            // 2. Ask Grok what to do next.
            val stepPlan = try {
                planNextStep(apiKey, goal, screen, history.toString())
            } catch (e: Exception) {
                log.append("AI ERROR: ").append(e.message).append("\n"); saveLog()
                main.post { Toast.makeText(ctx, "AI error on step $step: ${e.message}".take(200), Toast.LENGTH_LONG).show() }
                return
            }

            log.append("AI plan: ").append(stepPlan.action.method).append(" ").append(stepPlan.action.endpoint)
               .append(" body=").append(stepPlan.action.body?.toString() ?: "null")
               .append(" done=").append(stepPlan.done).append(" stuck=").append(stepPlan.stuck)
               .append(" reason=").append(stepPlan.reason).append("\n")
            saveLog()
            if (stepPlan.done) {
                log.append("RESULT: task reported done.\n"); saveLog()
                main.post { Toast.makeText(ctx, "Done ($step steps): ${stepPlan.reason}".take(200), Toast.LENGTH_LONG).show() }
                return
            }

            // 3. Stuck detection: same action AND screen unchanged, or Grok flags stuck.
            val actionKey = "${stepPlan.action.method} ${stepPlan.action.endpoint} ${stepPlan.action.body}"
            val noProgress = (actionKey == lastActionKey && screenSig == lastScreenSig)
            if (stepPlan.stuck || noProgress) {
                stuckCount++
                if (stuckCount >= stuckLimit) {
                    log.append("STOPPED: stuck (no progress) at step ").append(step).append(".\n"); saveLog()
                    main.post { Toast.makeText(ctx, "Stopped - stuck at step $step: ${stepPlan.reason}".take(200), Toast.LENGTH_LONG).show() }
                    return
                }
            } else {
                stuckCount = 0
            }
            lastActionKey = actionKey
            lastScreenSig = screenSig

            // 4. Dispatch the action.
            val result = dispatchToPortal(baseUrl, token, stepPlan.action)
            log.append("Portal response: ").append(result.take(300)).append("\n\n"); saveLog()
            val stepNum = step
            main.post { Toast.makeText(ctx, "Step $stepNum: ${stepPlan.action.endpoint}".take(120), Toast.LENGTH_SHORT).show() }
            history.append("Step $step: ${stepPlan.action.method} ${stepPlan.action.endpoint} -> ${result.take(120)}\n")

            // 5. Let the UI settle before reading again.
            try { Thread.sleep(1200) } catch (e: InterruptedException) { return }
        }

        log.append("ENDED: reached ").append(maxSteps).append(" steps without a done signal.\n"); saveLog()
        main.post { Toast.makeText(ctx, "Reached $maxSteps steps without a done signal. Run again to continue.", Toast.LENGTH_LONG).show() }
    }

    /** Reads a GET endpoint from Portal and returns the raw body (used to feed screen state to Grok). */
    private fun portalGet(baseUrl: String, token: String, endpoint: String): String {
        val url = URL(baseUrl + endpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-Auth-Token", token)
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    /** Asks Grok for the next single step toward the goal, given the current screen + history. */
    private fun planNextStep(apiKey: String, goal: String, screen: String, history: String): AiStep {
        val url = URL("https://api.x.ai/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 40000

        val systemPrompt = """
            You drive an Android phone one step at a time through the Mobilerun Portal REST API,
            to accomplish the user's GOAL. Each turn you get the current screen state and the
            history of steps already taken. Respond with ONLY one JSON object, no markdown:

            {"method":"GET|POST","endpoint":"/path","body":{...}|null,"done":false,"stuck":false,"reason":"short"}

            Endpoints:
              GET  /a11y_tree      inspect current screen elements
              GET  /state          screen + phone state
              GET  /screenshot     capture screen
              POST /keyboard/input body {"text":"<text to type>"}   (app encodes it for Portal)
              POST /keyboard/clear body {}                            clears the focused field
              POST /keyboard/key   body {"key":"ENTER|BACKSPACE|TAB|BACK|HOME"}  (app maps to key_code)

            Rules:
            - Return the SINGLE best next action toward the goal.
            - Set "done":true only when the goal is fully accomplished.
            - Set "stuck":true if the screen shows you cannot proceed (e.g. nothing actionable).
            - Keep "reason" under 12 words.
        """.trimIndent()

        val userContent = buildString {
            append("GOAL: ").append(goal).append("\n\n")
            append("SCREEN STATE:\n").append(screen.take(6000)).append("\n\n")
            if (history.isNotEmpty()) { append("STEPS SO FAR:\n").append(history.takeLast(1500)).append("\n\n") }
            append("What is the single next action?")
        }

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

        var contentStr = JSONObject(responseText)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        if (contentStr.startsWith("```")) {
            contentStr = contentStr.substringAfter("\n").substringBeforeLast("```").trim()
        }
        val j = JSONObject(contentStr)
        val action = PortalAction(
            j.getString("method").uppercase(),
            j.getString("endpoint"),
            if (j.isNull("body")) null else j.optJSONObject("body")
        )
        return AiStep(action, j.optBoolean("done", false), j.optBoolean("stuck", false), j.optString("reason", ""))
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
    /**
     * Converts an action body into the exact shape Mobilerun Portal expects, so typing and key
     * presses actually execute. Portal wants base64-encoded text for /keyboard/input and a numeric
     * key_code for /keyboard/key. Grok may phrase these as {"text":...} or {"key":"ENTER"}; we
     * translate here so the autonomous plan works regardless of how the action was phrased.
     */
    private fun normalizePortalBody(endpoint: String, body: JSONObject?): JSONObject {
        val b = body ?: JSONObject()
        when {
            endpoint.contains("/keyboard/input") -> {
                // Already correct?
                if (b.has("base64_text")) return b
                val text = b.optString("text", b.optString("base64_text", ""))
                val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val out = JSONObject()
                out.put("base64_text", encoded)
                // preserve clear flag if Grok set one
                if (b.has("clear")) out.put("clear", b.optBoolean("clear", true))
                return out
            }
            endpoint.contains("/keyboard/key") -> {
                if (b.has("key_code")) return b
                val keyName = b.optString("key", "").uppercase()
                val code = when (keyName) {
                    "ENTER" -> 66
                    "BACKSPACE", "DELETE", "DEL" -> 67
                    "TAB" -> 61
                    "SPACE" -> 62
                    "BACK" -> 4
                    "HOME" -> 3
                    "DPAD_DOWN", "DOWN" -> 20
                    "DPAD_UP", "UP" -> 19
                    "DPAD_LEFT", "LEFT" -> 21
                    "DPAD_RIGHT", "RIGHT" -> 22
                    "SEARCH" -> 84
                    else -> b.optInt("key_code", 66)
                }
                val out = JSONObject()
                out.put("key_code", code)
                return out
            }
            else -> return b
        }
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
                val outBody = normalizePortalBody(action.endpoint, action.body)
                OutputStreamWriter(conn.outputStream).use { it.write(outBody.toString()) }
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
        private const val PREF_LOG = "last_run_log"
    }
}
