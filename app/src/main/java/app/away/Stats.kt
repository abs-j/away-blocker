package app.away

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Daily counters for the weekly stats card, stored per calendar day for the last three weeks.
 * Written by BlockerService as things happen; nothing leaves the phone.
 */
object Stats {
    private lateinit var sp: SharedPreferences

    /** Bumped on every write so the stats card refreshes. */
    val version = mutableIntStateOf(0)

    data class Day(
        val start: Long,
        /** Times a distracting app was opened and not blocked. */
        val opens: Int,
        /** Times Away blocked a distracting app. */
        val blocked: Int,
        /** Times the phone was unlocked. */
        val pickups: Int,
        /** Sum of the gaps between putting the phone down and picking it up again. */
        val gapSum: Long,
        /** Longest stretch without a distracting app that ended this day. */
        val longest: Long,
    )

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.getSharedPreferences("away_stats", Context.MODE_PRIVATE)
    }

    private fun key(t: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(t))

    private fun all(): JSONObject = runCatching { JSONObject(sp.getString("days", "{}")) }.getOrDefault(JSONObject())

    @Synchronized
    private fun update(now: Long, change: (JSONObject) -> Unit) {
        val days = all()
        val k = key(now)
        val d = days.optJSONObject(k) ?: JSONObject()
        change(d)
        days.put(k, d)
        // Keep three weeks; yyyyMMdd keys sort as dates.
        val oldest = key(now - 21L * 24 * 60 * 60_000)
        days.keys().asSequence().toList().filter { it < oldest }.forEach { days.remove(it) }
        sp.edit().putString("days", days.toString()).apply()
        version.intValue++
    }

    /** Longest stretch without a distracting app, ever. */
    var best: Long
        get() = sp.getLong("best", 0L)
        private set(v) = sp.edit().putLong("best", v).apply()

    /** A distracting app was opened after [gap] ms away from distracting apps. */
    fun recordOpen(now: Long, gap: Long) {
        update(now) { d ->
            d.put("opens", d.optInt("opens") + 1)
            if (gap > d.optLong("longest")) d.put("longest", gap)
        }
        if (gap > best) best = gap
    }

    fun recordBlocked(now: Long) = update(now) { d -> d.put("blocked", d.optInt("blocked") + 1) }

    /** The phone was unlocked after [gap] ms untouched. */
    fun recordPickup(now: Long, gap: Long) = update(now) { d ->
        d.put("pickups", d.optInt("pickups") + 1)
        d.put("gapSum", d.optLong("gapSum") + gap)
    }

    /** The last [n] calendar days, oldest first, today last. Days with no data are zero. */
    fun lastDays(n: Int, now: Long = System.currentTimeMillis()): List<Day> {
        val days = all()
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(n - 1))
        }
        return (0 until n).map {
            val start = cal.timeInMillis
            val d = days.optJSONObject(key(start)) ?: JSONObject()
            cal.add(Calendar.DAY_OF_YEAR, 1)
            Day(start, d.optInt("opens"), d.optInt("blocked"), d.optInt("pickups"), d.optLong("gapSum"), d.optLong("longest"))
        }
    }
}

/** Short encouragement for the time since a distracting app, varied so it does not go stale. */
object Motivation {
    private val lines = listOf(
        1 * 60_000L to listOf("Fresh start. You've got this.", "New streak starts now.", "Clean slate."),
        15 * 60_000L to listOf("Good start. Keep it going.", "Off to a strong start.", "Momentum is building."),
        60 * 60_000L to listOf("Nice and focused.", "Your attention is yours.", "Staying present."),
        3 * 60 * 60_000L to listOf("You're on a roll.", "Hours reclaimed.", "Deep focus. Well done."),
        8 * 60 * 60_000L to listOf("Big stretch. Proud of this one.", "Serious discipline.", "Look at you go."),
        Long.MAX_VALUE to listOf("A whole day reclaimed.", "Legendary focus.", "Untouchable."),
    )

    fun line(awayMs: Long, best: Long, streakStart: Long): String {
        if (best >= 30 * 60_000L && awayMs > best) return "New personal best."
        if (best >= 30 * 60_000L && awayMs >= best * 8 / 10) return "Close to your best of ${Blocking.formatDuration(best)}."
        val options = lines.first { awayMs < it.first }.second
        // Same line for the whole streak, different next time.
        return options[((streakStart / 60_000L) % options.size).toInt()]
    }
}
