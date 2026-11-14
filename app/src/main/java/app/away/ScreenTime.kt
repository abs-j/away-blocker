package app.away

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import java.util.Calendar

/**
 * Per-app foreground time from the system's usage events. Needs Usage access; without it every
 * query returns nothing and limits are simply not enforced. Nothing leaves the phone.
 */
object UsageTracker {
    private lateinit var app: Context

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
    }

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun dayStart(now: Long, daysAgo: Int = 0): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -daysAgo)
    }.timeInMillis

    private fun ignored(ctx: Context): Set<String> {
        val home = ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
        )?.activityInfo?.packageName
        return setOfNotNull(ctx.packageName, home, "com.android.systemui", "android")
    }

    /** Foreground milliseconds per package between [from] and [to]. */
    fun usage(ctx: Context, from: Long, to: Long): Map<String, Long> {
        if (!hasAccess(ctx)) return emptyMap()
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val events = runCatching { usm.queryEvents(from, to) }.getOrNull() ?: return emptyMap()
        val skip = ignored(ctx)
        val totals = HashMap<String, Long>()
        // An app is in use while any of its activities is resumed. Track them per class: during a
        // screen change the old activity's pause can arrive after the new one's resume, and a plain
        // per-app flag would then lose the whole stretch (this dropped a 40 minute video).
        val classes = HashMap<String, MutableSet<String>>()
        val since = HashMap<String, Long>()
        fun close(pkg: String, at: Long) {
            classes.remove(pkg)
            val s0 = since.remove(pkg) ?: return
            if (at > s0) totals[pkg] = (totals[pkg] ?: 0L) + (at - s0)
        }
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            if (pkg in skip) continue
            val cls = e.className ?: ""
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // Another app coming forward ends any app whose pause event was never delivered.
                    since.keys.filter { it != pkg }.forEach { close(it, e.timeStamp) }
                    classes.getOrPut(pkg) { HashSet() }.add(cls)
                    since.putIfAbsent(pkg, e.timeStamp)
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val set = classes[pkg] ?: continue
                    set.remove(cls)
                    if (set.isEmpty()) close(pkg, e.timeStamp)
                }
            }
        }
        // Still in the foreground right now.
        since.keys.toList().forEach { close(it, to) }
        return totals
    }

    fun today(ctx: Context, now: Long = System.currentTimeMillis()) = usage(ctx, dayStart(now), now)

    /** Total screen time for each of the last [n] days, oldest first. */
    fun dailyTotals(ctx: Context, n: Int, now: Long = System.currentTimeMillis()): List<Long> =
        (n - 1 downTo 0).map { ago ->
            val from = dayStart(now, ago)
            val to = if (ago == 0) now else dayStart(now, ago - 1)
            usage(ctx, from, to).values.sum()
        }

    // Cached for limit checks, which run every few seconds.
    @Volatile private var cache: Map<String, Long> = emptyMap()
    @Volatile private var cachedAt = 0L

    fun todayCached(now: Long = System.currentTimeMillis()): Map<String, Long> {
        if (!::app.isInitialized) return emptyMap()
        if (now - cachedAt > 15_000) {
            cachedAt = now
            cache = today(app, now)
        }
        return cache
    }

    @Volatile private var neverCache: Set<String>? = null

    /**
     * Apps that are never limited or blocked, so Away can never lock you out of calling, texting,
     * maps or Settings (where Away itself is switched off). Found by what the phone uses for each job.
     */
    fun neverBlock(ctx: Context): Set<String> {
        neverCache?.let { return it }
        val pm = ctx.packageManager
        fun handlers(i: Intent) = pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }
        val found = HashSet<String>()
        found += handlers(Intent(Intent.ACTION_DIAL))
        found += handlers(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")))
        found += handlers(Intent(android.provider.Settings.ACTION_SETTINGS))
        found += handlers(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0")))
        found += listOf(
            "com.android.settings", "com.android.phone", "com.android.contacts", "com.android.emergency",
            "com.google.android.dialer", "com.google.android.apps.messaging", "com.google.android.apps.maps",
            "com.samsung.android.dialer", "com.samsung.android.messaging", "com.samsung.android.app.contacts",
            "com.android.vending", ctx.packageName,
        )
        return found.also { neverCache = it }
    }

    fun isNeverBlock(pkg: String): Boolean = ::app.isInitialized && pkg in neverBlock(app)

    /** True when [pkg] has a daily limit and today's use has reached it. */
    fun overLimit(pkg: String, now: Long = System.currentTimeMillis()): Boolean {
        if (isNeverBlock(pkg)) return false
        val limit = Prefs.limits[pkg] ?: return false
        return (todayCached(now)[pkg] ?: 0L) >= limit * 60_000L
    }
}
