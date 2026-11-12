package app.away

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private lateinit var sp: SharedPreferences
    private lateinit var appCtx: Context
    private val secure: SharedPreferences by lazy { createSecure() }

    var lastError: String
        get() = sp.getString("lastError", "") ?: ""
        set(v) = sp.edit().putString("lastError", v).apply()

    private fun createSecure(): SharedPreferences {
        fun make(): SharedPreferences {
            val key = MasterKey.Builder(appCtx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                appCtx, "away_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
        return try { make() } catch (e: Exception) {
            appCtx.deleteSharedPreferences("away_secure")
            runCatching {
                java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
            make()
        }
    }

    val DEFAULT_BLOCKED = setOf(
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.twitter.android",
        "com.google.android.youtube",
        "com.reddit.frontpage",
        "com.snapchat.android",
        "com.facebook.katana",
    )

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        val app = ctx.applicationContext
        sp = app.getSharedPreferences("away", Context.MODE_PRIVATE)
        appCtx = app
        if (!sp.contains("install")) sp.edit().putLong("install", System.currentTimeMillis()).apply()
    }

    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var enabled: Boolean
        get() = bool("enabled", false)
        set(v) = putBool("enabled", v)

    var scheduleEnabled: Boolean
        get() = bool("scheduleEnabled", false)
        set(v) = putBool("scheduleEnabled", v)

    /** Minutes after midnight. */
    var startMin: Int
        get() = sp.getInt("startMin", 9 * 60)
        set(v) = sp.edit().putInt("startMin", v).apply()

    var endMin: Int
        get() = sp.getInt("endMin", 17 * 60)
        set(v) = sp.edit().putInt("endMin", v).apply()

    // Notification holding is independent of app blocking. Until first changed, each setting
    // falls back to the matching blocking setting so existing installs keep their behaviour.
    var holdEnabled: Boolean
        get() = if (sp.contains("holdEnabled")) bool("holdEnabled", false) else enabled
        set(v) = putBool("holdEnabled", v)

    var holdScheduleEnabled: Boolean
        get() = if (sp.contains("holdScheduleEnabled")) bool("holdScheduleEnabled", false) else scheduleEnabled
        set(v) = putBool("holdScheduleEnabled", v)

    var holdStartMin: Int
        get() = if (sp.contains("holdStartMin")) sp.getInt("holdStartMin", 0) else startMin
        set(v) = sp.edit().putInt("holdStartMin", v).apply()

    var holdEndMin: Int
        get() = if (sp.contains("holdEndMin")) sp.getInt("holdEndMin", 0) else endMin
        set(v) = sp.edit().putInt("holdEndMin", v).apply()

    var unlockUntil: Long
        get() = sp.getLong("unlockUntil", 0L)
        set(v) = sp.edit().putLong("unlockUntil", v).apply()

    var lastUse: Long
        get() = sp.getLong("lastUse", 0L)
        set(v) = sp.edit().putLong("lastUse", v).apply()

    val installTime: Long get() = sp.getLong("install", System.currentTimeMillis())

    var blocked: Set<String>
        get() = HashSet(sp.getStringSet("blocked", DEFAULT_BLOCKED) ?: DEFAULT_BLOCKED)
        set(v) = sp.edit().putStringSet("blocked", HashSet(v)).apply()

    var triageEnabled: Boolean
        get() = bool("triageEnabled", false)
        set(v) = putBool("triageEnabled", v)

    var apiKey: String
        get() = secure.getString("apiKey", "") ?: ""
        set(v) = secure.edit().putString("apiKey", v.trim()).apply()

    /** Optional model override. Blank means the provider default. */
    var model: String
        get() = sp.getString("model", "") ?: ""
        set(v) = sp.edit().putString("model", v.trim()).apply()

    /** OpenRouter keys start with sk-or-; anything else is treated as an Anthropic key. */
    val isOpenRouter: Boolean get() = apiKey.startsWith("sk-or-")

    /** Comma separated names. */
    var vips: String
        get() = sp.getString("vips", "") ?: ""
        set(v) = sp.edit().putString("vips", v).apply()

    /** Comma separated package names never sent to the AI. */
    var privateApps: String
        get() = sp.getString("privateApps", "") ?: ""
        set(v) = sp.edit().putString("privateApps", v).apply()

    fun vipList(): List<String> = vips.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun privateSet(): Set<String> = privateApps.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    const val DAILY_CAP = 200

    fun callsToday(): Int = if (sp.getString("callsDay", "") == today()) sp.getInt("calls", 0) else 0

    fun incCalls() {
        val n = callsToday() + 1
        sp.edit().putString("callsDay", today()).putInt("calls", n).apply()
    }

    // Launcher
    private fun getList(k: String): List<String> {
        val a = JSONArray(sp.getString(k, "[]"))
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun putList(k: String, v: Collection<String>) =
        sp.edit().putString(k, JSONArray(v.toList()).toString()).apply()

    private fun getMap(k: String): Map<String, String> {
        val o = JSONObject(sp.getString(k, "{}"))
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    private fun putMap(k: String, v: Map<String, String>) =
        sp.edit().putString(k, JSONObject(v).toString()).apply()

    /** Ordered package names shown as text on the launcher home. */
    var pinned: List<String>
        get() = getList("pinned")
        set(v) = putList("pinned", v)

    /** Packages hidden from the launcher drawer. */
    var hidden: Set<String>
        get() = getList("hidden").toSet()
        set(v) = putList("hidden", v)

    var renames: Map<String, String>
        get() = getMap("renames")
        set(v) = putMap("renames", v)

    /** Package to seconds of pause before launching. */
    var delays: Map<String, Int>
        get() = getMap("delays").mapValues { it.value.toIntOrNull() ?: 0 }
        set(v) = putMap("delays", v.mapValues { it.value.toString() })

    /** Package launched by swiping left / right on home. Blank means camera / phone. */
    var swipeLeftApp: String
        get() = sp.getString("swipeLeftApp", "") ?: ""
        set(v) = sp.edit().putString("swipeLeftApp", v).apply()

    var swipeRightApp: String
        get() = sp.getString("swipeRightApp", "") ?: ""
        set(v) = sp.edit().putString("swipeRightApp", v).apply()

    // Digest storage
    val digestVersion = mutableIntStateOf(0)

    @Synchronized
    fun digest(): List<DigestItem> {
        val arr = JSONArray(sp.getString("digest", "[]"))
        return (0 until arr.length()).map { DigestItem.from(arr.getJSONObject(it)) }
    }

    @Synchronized
    fun addDigest(item: DigestItem) {
        val list = (listOf(item) + digest()).take(200)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("digest", arr.toString()).apply()
        digestVersion.intValue++
    }

    @Synchronized
    fun clearDigest() {
        sp.edit().putString("digest", "[]").apply()
        digestVersion.intValue++
    }
}

data class DigestItem(
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
    val urgent: Boolean,
    val reason: String,
) {
    fun toJson() = JSONObject()
        .put("app", app).put("title", title).put("text", text)
        .put("time", time).put("urgent", urgent).put("reason", reason)

    companion object {
        fun from(o: JSONObject) = DigestItem(
            o.optString("app"), o.optString("title"), o.optString("text"),
            o.optLong("time"), o.optBoolean("urgent"), o.optString("reason"),
        )
    }
}

object Blocking {
    /** True when the blocker should currently block (toggle on, inside schedule if any). */
    fun isActive(now: Long = System.currentTimeMillis()): Boolean {
        if (!Prefs.enabled) return false
        if (!Prefs.scheduleEnabled) return true
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val s = Prefs.startMin
        val e = Prefs.endMin
        return if (s <= e) m in s until e else (m >= s || m < e)
    }

    /** True when notifications should currently be held. Separate from app blocking and its pause. */
    fun isHoldActive(now: Long = System.currentTimeMillis()): Boolean {
        if (!Prefs.holdEnabled) return false
        if (!Prefs.holdScheduleEnabled) return true
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val s = Prefs.holdStartMin
        val e = Prefs.holdEndMin
        return if (s <= e) m in s until e else (m >= s || m < e)
    }

    fun isPaused(now: Long = System.currentTimeMillis()) = now < Prefs.unlockUntil

    fun shouldBlock(pkg: String?, now: Long = System.currentTimeMillis()): Boolean =
        pkg != null && pkg in Prefs.blocked && isActive(now) && !isPaused(now)

    fun formatDuration(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun awayMillis(now: Long = System.currentTimeMillis()): Long {
        val ref = maxOf(Prefs.lastUse, Prefs.installTime)
        return (now - ref).coerceAtLeast(0)
    }
}
