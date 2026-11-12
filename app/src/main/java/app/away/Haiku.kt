package app.away

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap

data class Verdict(val urgent: Boolean, val reason: String)

object Haiku {
    private const val MODEL = "claude-haiku-5-5"

    private const val SYSTEM = """You triage phone notifications for someone who is trying to stay focused.
Decide if a notification is URGENT.
URGENT means: it needs a human response or action within about an hour, or it is time-sensitive money, safety, travel or health information (for example a person asking for help, a ride or delivery arriving, a fraud alert, a missed important call, a flight change).
NOT urgent: marketing, promotions, social media activity (likes, follows, new posts), news, newsletters, game or app engagement nudges, routine automated updates, casual chat with no question or deadline.
VIP names are people the user always wants to hear from: a message from a VIP asking something or needing action is urgent.
Reply with ONLY compact JSON: {"urgent": true|false, "reason": "<at most 8 words>"}"""

    private val cache = object : LinkedHashMap<String, Verdict>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Verdict>) = size > 200
    }

    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")
    private val URL_RE = Regex("https?://\\S+|www\\.\\S+")
    private val NUM = Regex("\\d{4,}")

    fun redact(s: String): String =
        s.replace(EMAIL, "[email]").replace(URL_RE, "[link]").replace(NUM, "[num]").take(300)

    private const val OR_DEFAULT_MODEL = "openai/gpt-4o-mini"

    private fun classifyOpenRouter(user: String, apiKey: String, cacheKey: String): Verdict? {
        val body = JSONObject()
            .put("model", Prefs.model.ifBlank { OR_DEFAULT_MODEL })
            .put("max_tokens", 128)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        return try {
            Prefs.incCalls()
            val c = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 3000
            c.readTimeout = 5000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $apiKey")
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("X-Title", "Away")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
                Prefs.lastError = "OpenRouter HTTP ${c.responseCode}: $err"
                return null
            }
            val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val choice = root.getJSONArray("choices").getJSONObject(0)
            val raw = choice.getJSONObject("message").optString("content", "")
            if (raw.isEmpty() || choice.optString("finish_reason") == "length") {
                Prefs.lastError = "OpenRouter: no usable reply"
                return null
            }
            val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            val v = Verdict(json.getBoolean("urgent"), json.optString("reason", "").take(60))
            synchronized(cache) { cache[cacheKey] = v }
            Prefs.lastError = ""
            v
        } catch (e: Exception) {
            Prefs.lastError = e.javaClass.simpleName + ": " + (e.message ?: "").take(100)
            null
        }
    }

    /** Returns null on any failure (timeout, no network, bad JSON, cap reached). */
    fun classify(app: String, title: String, text: String, vips: List<String>, apiKey: String): Verdict? {
        val t = redact(title)
        val x = redact(text)
        val cacheKey = "$app|$t|$x"
        synchronized(cache) { cache[cacheKey]?.let { return it } }
        if (Prefs.callsToday() >= Prefs.DAILY_CAP) return null

        val user = buildString {
            append("App: ").append(app).append('\n')
            append("Title/sender: ").append(t).append('\n')
            append("Text: ").append(x).append('\n')
            append("VIPs: ").append(if (vips.isEmpty()) "none" else vips.joinToString(", "))
        }
        if (apiKey.startsWith("sk-or-")) return classifyOpenRouter(user, apiKey, cacheKey)
        val body = JSONObject()
            .put("model", Prefs.model.ifBlank { MODEL })
            .put("max_tokens", 128)
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))

        return try {
            Prefs.incCalls()
            val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.doOutput = true
            c.setRequestProperty("x-api-key", apiKey)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
                Prefs.lastError = "HTTP ${c.responseCode}: $err"
                return null
            }
            val resp = c.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(resp)
            val blocks = root.getJSONArray("content")
            var raw = ""
            for (i in 0 until blocks.length()) {
                val b = blocks.getJSONObject(i)
                if (b.optString("type") == "text") { raw = b.getString("text"); break }
            }
            val stop = root.optString("stop_reason")
            if (raw.isEmpty() || stop == "refusal" || stop == "max_tokens") {
                Prefs.lastError = "No usable reply (stop_reason=$stop)"
                return null
            }
            val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            val v = Verdict(json.getBoolean("urgent"), json.optString("reason", "").take(60))
            synchronized(cache) { cache[cacheKey] = v }
            Prefs.lastError = ""
            v
        } catch (e: Exception) {
            Prefs.lastError = e.javaClass.simpleName + ": " + (e.message ?: "").take(100)
            null
        }
    }
}
