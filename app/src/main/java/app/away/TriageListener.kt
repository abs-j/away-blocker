package app.away

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors

/**
 * While the hold is active: remove every notification from the bar (new ones and ones already
 * showing), classify it for the digest, and keep a copy in memory. When the hold ends, post the
 * held notifications back so nothing is lost.
 */
class TriageListener : NotificationListenerService() {
    private val pool = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    /** A removed notification, kept so it can be shown again when the hold ends. */
    private class Held(
        val key: String, val pkg: String, val app: String, val title: String, val text: String,
        val time: Long, val icon: Icon?, val contentIntent: PendingIntent?,
    )

    private val held = LinkedHashMap<String, Held>()
    private var holding = false

    private val tick = object : Runnable {
        override fun run() {
            syncHold()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onListenerConnected() {
        instance = this
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(tick)
        if (instance === this) instance = null
    }

    /** Starts or ends the hold when the setting or schedule says so. Safe to call often. */
    fun syncHold() {
        val want = Blocking.isHoldActive()
        if (want && !holding) {
            holding = true
            runCatching { activeNotifications }.getOrNull()?.forEach { process(it) }
        } else if (!want && holding) {
            holding = false
            restoreHeld()
        }
    }

    private fun restoreHeld() {
        val items = synchronized(held) { held.values.toList().also { held.clear() } }
        val nm = getSystemService(NotificationManager::class.java)
        items.forEach { h ->
            val b = Notification.Builder(this, AwayApp.CH_RESTORED)
                .setSmallIcon(h.icon ?: Icon.createWithResource(this, android.R.drawable.stat_notify_chat))
                .setContentTitle(h.title.ifBlank { h.app })
                .setContentText(h.text)
                .setSubText(h.app)
                .setStyle(Notification.BigTextStyle().bigText(h.text))
                .setWhen(h.time)
                .setShowWhen(true)
                .setAutoCancel(true)
            h.contentIntent?.let { b.setContentIntent(it) }
            nm.notify("held", h.key.hashCode(), b.build())
        }
    }

    private val passThroughCategories = setOf(
        Notification.CATEGORY_CALL,
        Notification.CATEGORY_ALARM,
        Notification.CATEGORY_NAVIGATION,
        Notification.CATEGORY_TRANSPORT,
        Notification.CATEGORY_PROGRESS,
        Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_SYSTEM,
        Notification.CATEGORY_REMINDER,
    )
    private val lowValueCategories = setOf(
        Notification.CATEGORY_PROMO,
        Notification.CATEGORY_SOCIAL,
        Notification.CATEGORY_RECOMMENDATION,
    )
    private val ignoredPackages = setOf("android", "com.android.systemui", "com.android.providers.downloads")
    private val otp = Regex(
        "(?i)\\b(otp|verification code|security code|one[- ]time (pass)?code|passcode|2fa|login code|code)\\b\\D{0,30}\\b\\d{4,8}\\b"
    )
    private val promoWords = Regex("(?i)promo|\\bsale\\b|% off|coupon|discount")
    private val lastTextByKey = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        syncHold()
        if (holding) process(sbn)
    }

    /** Removes one notification and records it. Returns false if it was left alone. */
    private fun process(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (sbn.packageName == packageName || sbn.packageName in ignoredPackages) return false
        if (sbn.isOngoing || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return false
        if (n.category in passThroughCategories) return false
        // Group summaries duplicate their children; just remove them.
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            cancelNotification(sbn.key)
            return true
        }
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val pkg = sbn.packageName
        val category = n.category
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        synchronized(held) {
            held[sbn.key] = Held(sbn.key, pkg, app, title, text, sbn.postTime, n.smallIcon, n.contentIntent)
        }
        // Messaging apps re-post the same key with unchanged text; only classify once.
        val sig = "$title|$text"
        val isNew = synchronized(lastTextByKey) {
            val same = lastTextByKey[sbn.key] == sig
            lastTextByKey[sbn.key] = sig
            if (lastTextByKey.size > 500) lastTextByKey.clear()
            !same
        }
        cancelNotification(sbn.key)
        if (isNew) pool.execute { triage(pkg, app, title, text, category) }
        return true
    }

    private fun triage(pkg: String, app: String, title: String, text: String, category: String?) {
        val verdict = decide(pkg, app, title, text, category)
        Prefs.addDigest(DigestItem(app, title, text, System.currentTimeMillis(), verdict.urgent, verdict.reason))
    }

    private fun decide(pkg: String, app: String, title: String, text: String, category: String?): Verdict {
        val combined = "$title $text"
        if (category in lowValueCategories) return Verdict(false, "social or promo category")
        if (otp.containsMatchIn(combined) && !promoWords.containsMatchIn(combined)) {
            return Verdict(true, "verification code")
        }

        val vips = Prefs.vipList()
        val vipHit = vips.any { combined.contains(it, ignoreCase = true) }
        val key = Prefs.apiKey
        if (!Prefs.triageEnabled || key.isBlank()) return Verdict(vipHit, if (vipHit) "VIP contact" else "AI off")
        if (pkg in Prefs.privateSet()) return Verdict(vipHit, if (vipHit) "VIP contact" else "private app, not sent to AI")

        val v = Haiku.classify(app, title, text, vips, key)
        if (v != null) return v
        // Fail-safe: AI unavailable.
        return Verdict(vipHit, if (vipHit) "VIP contact (AI unavailable)" else "AI unavailable")
    }

    companion object {
        @Volatile private var instance: TriageListener? = null

        /** Applies a hold setting change right away instead of waiting for the next tick. */
        fun syncNow() { instance?.let { l -> l.handler.post { l.syncHold() } } }
    }
}
