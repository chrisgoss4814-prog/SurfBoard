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
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * SurfBoard AI Command mode — thin relay.
 *
 * SurfBoard does NOT talk to Mobilerun Portal directly and does NOT reimplement
 * Portal's tap/gesture protocol. Instead it sends the user's goal to a small
 * always-on listener running in Termux (mobilerun_listener.py), which runs the
 * already-working `mobilerun` CLI agent. Mobilerun's own tested engine handles
 * screen reading, planning, tapping, typing, and navigation.
 *
 * Listener contract (see mobilerun_listener.py):
 *   POST {base}/run     body {"goal": "<text>"}   -> starts the task
 *   GET  {base}/status                             -> {"goal","output","running"}
 *   GET  {base}/ping                                -> liveness check
 */
class AiCommandActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var commandInput: EditText
    private lateinit var statusText: TextView
    private lateinit var listenerUrlInput: EditText

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

        val viewLogButton = Button(this).apply {
            text = "View last run log"
            setOnClickListener {
                statusText.text = prefs.getString(PREF_LOG, "No log yet. Run a task first.")
            }
        }
        root.addView(viewLogButton)

        val copyLogButton = Button(this).apply {
            text = "Copy log"
            setOnClickListener {
                val logText = prefs.getString(PREF_LOG, "") ?: ""
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("SurfBoard log", logText))
                Toast.makeText(this@AiCommandActivity, "Log copied", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(copyLogButton)

        val settingsTitle = TextView(this).apply {
            text = "Settings"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 8)
        }
        root.addView(settingsTitle)

        listenerUrlInput = EditText(this).apply {
            hint = "Mobilerun listener URL (Termux)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(prefs.getString(PREF_LISTENER_URL, DEFAULT_LISTENER_URL))
        }
        root.addView(listenerUrlInput)

        val saveButton = Button(this).apply {
            text = "Save settings"
            setOnClickListener {
                prefs.edit()
                    .putString(PREF_LISTENER_URL, listenerUrlInput.text.toString().trim().ifEmpty { DEFAULT_LISTENER_URL })
                    .apply()
                Toast.makeText(this@AiCommandActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(saveButton)

        val checkButton = Button(this).apply {
            text = "Check Termux connection"
            setOnClickListener { checkConnection() }
        }
        root.addView(checkButton)

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

    private fun listenerBase(): String =
        (prefs.getString(PREF_LISTENER_URL, DEFAULT_LISTENER_URL) ?: DEFAULT_LISTENER_URL).trimEnd('/')

    private fun checkConnection() {
        Thread {
            val result = httpGet(listenerBase() + "/ping")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, result.take(150), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun onRunClicked() {
        val goal = commandInput.text.toString().trim()
        if (goal.isEmpty()) { statusText.text = "Type a request first."; return }

        val base = listenerBase()
        val appContext = applicationContext
        Toast.makeText(appContext, "Sending to Mobilerun…", Toast.LENGTH_SHORT).show()
        finish()

        Thread {
            val log = StringBuilder()
            fun saveLog() { prefs.edit().putString(PREF_LOG, log.toString()).apply() }

            log.append("GOAL: ").append(goal).append("\n\n")
            saveLog()

            val startResp = httpPost(base + "/run", JSONObject().apply { put("goal", goal) })
            log.append("Start response: ").append(startResp).append("\n\n")
            saveLog()

            if (startResp.contains("\"error\"")) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "Mobilerun error: ${startResp.take(150)}", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            // Poll /status until the task finishes.
            var elapsedMs = 0
            val pollEveryMs = 2000
            val maxWaitMs = 10 * 60 * 1000 // 10 minutes, matches listener's own timeout
            while (elapsedMs < maxWaitMs) {
                Thread.sleep(pollEveryMs.toLong())
                elapsedMs += pollEveryMs
                val statusResp = httpGet(base + "/status")
                log.append("[+").append(elapsedMs / 1000).append("s] ").append(statusResp).append("\n")
                saveLog()

                val stillRunning = try { JSONObject(statusResp).optBoolean("running", false) } catch (e: Exception) { false }
                if (!stillRunning) {
                    val output = try { JSONObject(statusResp).optString("output", "") } catch (e: Exception) { statusResp }
                    log.append("\nFINISHED:\n").append(output).append("\n")
                    saveLog()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(appContext, "Done: ${output.take(150)}", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
            }

            log.append("\nGave up waiting after ${maxWaitMs / 1000}s. Check View last run log.\n")
            saveLog()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, "Still running — check the log later.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun httpGet(urlStr: String): String {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            "HTTP $code " + (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: Exception) {
            "Could not reach Termux listener at $urlStr (${e.message}). Make sure Termux is running mobilerun_listener.py."
        }
    }

    private fun httpPost(urlStr: String, body: JSONObject): String {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 15000
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            "HTTP $code " + (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (e: Exception) {
            "Could not reach Termux listener at $urlStr (${e.message}). Make sure Termux is running mobilerun_listener.py."
        }
    }

    companion object {
        private const val PREF_LISTENER_URL = "mobilerun_listener_url"
        private const val PREF_LOG = "last_run_log"
        private const val DEFAULT_LISTENER_URL = "http://127.0.0.1:8765"
    }
}
