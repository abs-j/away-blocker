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
        migrateToFocus()
        Stats.init(app)
        UsageTracker.init(app)
    }

    /**
     * One-time move from separate Blocking and Hold settings to a single Focus mode. Focus reuses the
     * old blocking keys (enabled, schedule); the old hold settings decide the two Focus checkboxes.
     */
    private fun migrateToFocus() {
        if (sp.getBoolean("focusMigrated", false)) return
        val oldBlock = enabled
        val oldHold = holdEnabled
        val on = oldBlock || oldHold
        val e = sp.edit()
            .putBoolean("enabled", on)
            .putBoolean("focusBlocksApps", oldBlock || !on)
            .putBoolean("focusHoldsNotifs", oldHold || !on)
        if (!oldBlock && oldHold) {
            // Only the hold was on, so its schedule becomes the Focus schedule.
            e.putBoolean("scheduleEnabled", holdScheduleEnabled)
                .putInt("startMin", holdStartMin).putInt("endMin", holdEndMin)
        }
        e.putBoolean("focusMigrated", true).apply()
    }

    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    /** True once first-run setup has been finished. */
    var onboarded: Boolean
        get() = bool("onboarded", false)
        set(v) = putBool("onboarded", v)

    /** False until onboarding has been completed or reset at least once (distinguishes upgrades). */
    val onboardedKnown: Boolean get() = sp.contains("onboarded")

    /** Focus mode on/off. Stored under the original blocking key. */
    var enabled: Boolean
        get() = bool("enabled", false)
        set(v) = putBool("enabled", v)

    /** While Focus is active: hide and block distracting apps. */
    var focusBlocksApps: Boolean
        get() = bool("focusBlocksApps", true)
        set(v) = putBool("focusBlocksApps", v)

    /** While Focus is active: hold notifications (urgent ones still break through). */
    var focusHoldsNotifs: Boolean
        get() = bool("focusHoldsNotifs", true)
        set(v) = putBool("focusHoldsNotifs", v)

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

    // Legacy separate hold settings, read only by migrateToFocus().
    private var holdEnabled: Boolean
        get() = if (sp.contains("holdEnabled")) bool("holdEnabled", false) else enabled
        set(v) = putBool("holdEnabled", v)

    private var holdScheduleEnabled: Boolean
        get() = if (sp.contains("holdScheduleEnabled")) bool("holdScheduleEnabled", false) else scheduleEnabled
        set(v) = putBool("holdScheduleEnabled", v)

    private var holdStartMin: Int
        get() = if (sp.contains("holdStartMin")) sp.getInt("holdStartMin", 0) else startMin
        set(v) = sp.edit().putInt("holdStartMin", v).apply()

    private var holdEndMin: Int
        get() = if (sp.contains("holdEndMin")) sp.getInt("holdEndMin", 0) else endMin
        set(v) = sp.edit().putInt("holdEndMin", v).apply()

    var unlockUntil: Long
        get() = sp.getLong("unlockUntil", 0L)
        set(v) = sp.edit().putLong("unlockUntil", v).apply()

    var lastUse: Long
        get() = sp.getLong("lastUse", 0L)
        set(v) = sp.edit().putLong("lastUse", v).apply()

    /** Last moment the phone was in use: updated when the screen turns off after an unlocked session. */
    var lastPhoneUse: Long
        get() = sp.getLong("lastPhoneUse", 0L)
        set(v) = sp.edit().putLong("lastPhoneUse", v).apply()

    /** Minutes untouched before the lock screen timer appears. 0 shows it always. */
    var indicatorAfterMin: Int
        get() = sp.getInt("indicatorAfterMin", 60)
        set(v) = sp.edit().putInt("indicatorAfterMin", v.coerceIn(0, 24 * 60)).apply()

    /** Package to daily limit in minutes. Reaching it blocks the app until midnight. */
    var limits: Map<String, Int>
        get() = getMap("limits").mapValues { it.value.toIntOrNull() ?: 0 }.filterValues { it > 0 }
        set(v) = putMap("limits", v.mapValues { it.value.toString() })

    /** Package to the time (ms) until which its notifications are not held. [PASS_ALWAYS] means no end. */
    private fun passMap(): Map<String, Long> =
        getMap("passRules").mapValues { it.value.toLongOrNull() ?: 0L }

    const val PASS_ALWAYS = Long.MAX_VALUE

    /** Apps whose notifications currently go straight through Focus, with when that ends. */
    fun passRules(now: Long = System.currentTimeMillis()): Map<String, Long> =
        passMap().filterValues { it > now }

    fun isPassing(pkg: String, now: Long = System.currentTimeMillis()): Boolean =
        (passMap()[pkg] ?: 0L) > now

    @Synchronized
    fun setPass(pkg: String, until: Long) {
        putMap("passRules", (passRules() + (pkg to until)).mapValues { it.value.toString() })
        digestVersion.intValue++
    }

    @Synchronized
    fun clearPass(pkg: String) {
        putMap("passRules", (passRules() - pkg).mapValues { it.value.toString() })
        digestVersion.intValue++
    }

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

    /** Digest entries older than this are dropped, so the list stays about today. */
    private const val DIGEST_KEEP_MS = 24 * 60 * 60_000L

    @Synchronized
    fun digest(): List<DigestItem> {
        val arr = JSONArray(sp.getString("digest", "[]"))
        val cutoff = System.currentTimeMillis() - DIGEST_KEEP_MS
        return (0 until arr.length()).map { DigestItem.from(arr.getJSONObject(it)) }.filter { it.time >= cutoff }
    }

    private fun saveDigest(list: List<DigestItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("digest", arr.toString()).apply()
        digestVersion.intValue++
    }

    @Synchronized
    fun addDigest(item: DigestItem) = saveDigest((listOf(item) + digest()).take(200))

    @Synchronized
    fun removeDigest(item: DigestItem) {
        val list = digest().toMutableList()
        if (list.remove(item)) saveDigest(list)
    }

    /** Held notifications saved as JSON, so a restart does not lose them. Written by TriageListener. */
    var heldStore: String
        get() = sp.getString("heldStore", "[]") ?: "[]"
        set(v) = sp.edit().putString("heldStore", v).apply()

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
    /** Source app package, for opening it from the digest. Blank on entries saved before it existed. */
    val pkg: String = "",
) {
    fun toJson() = JSONObject()
        .put("app", app).put("title", title).put("text", text)
        .put("time", time).put("urgent", urgent).put("reason", reason).put("pkg", pkg)

    companion object {
        fun from(o: JSONObject) = DigestItem(
            o.optString("app"), o.optString("title"), o.optString("text"),
            o.optLong("time"), o.optBoolean("urgent"), o.optString("reason"), o.optString("pkg"),
        )
    }
}

object Blocking {
    /** True when Focus is on and inside its schedule (if any). */
    fun isFocusOn(now: Long = System.currentTimeMillis()): Boolean {
        if (!Prefs.enabled) return false
        if (!Prefs.scheduleEnabled) return true
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val s = Prefs.startMin
        val e = Prefs.endMin
        return if (s <= e) m in s until e else (m >= s || m < e)
    }

    /** Focus is on and set to hide distracting apps. The unlock pause is checked separately. */
    fun isActive(now: Long = System.currentTimeMillis()) = isFocusOn(now) && Prefs.focusBlocksApps

    /** Focus is on and set to hold notifications. Not affected by the unlock pause. */
    fun isHoldActive(now: Long = System.currentTimeMillis()) = isFocusOn(now) && Prefs.focusHoldsNotifs

    fun isPaused(now: Long = System.currentTimeMillis()) = now < Prefs.unlockUntil

    fun shouldBlock(pkg: String?, now: Long = System.currentTimeMillis()): Boolean =
        pkg != null && ((pkg in Prefs.blocked && isActive(now) && !isPaused(now)) || UsageTracker.overLimit(pkg, now))

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
