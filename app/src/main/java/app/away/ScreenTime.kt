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
        // Package to the time its activity came to the foreground; an app can have several activities.
        val open = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            if (pkg in skip) continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // Another app coming forward ends any app whose pause event was never delivered.
                    val stale = open.filterKeys { it != pkg }
                    stale.forEach { (p, s) ->
                        if (e.timeStamp > s) totals[p] = (totals[p] ?: 0L) + (e.timeStamp - s)
                        open.remove(p)
                    }
                    if (pkg !in open) open[pkg] = e.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val s = open.remove(pkg)
                    if (s != null && e.timeStamp > s) totals[pkg] = (totals[pkg] ?: 0L) + (e.timeStamp - s)
                }
            }
        }
        // Still in the foreground right now.
        open.forEach { (pkg, s) -> if (to > s) totals[pkg] = (totals[pkg] ?: 0L) + (to - s) }
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

    /** True when [pkg] has a daily limit and today's use has reached it. */
    fun overLimit(pkg: String, now: Long = System.currentTimeMillis()): Boolean {
        val limit = Prefs.limits[pkg] ?: return false
        return (todayCached(now)[pkg] ?: 0L) >= limit * 60_000L
    }
}
