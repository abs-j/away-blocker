package app.away

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors

/**
 * While blocking is active: suppress every notification, decide if it is urgent, re-post urgent
 * ones as a distinct alert and put the rest in the digest.
 */
class TriageListener : NotificationListenerService() {
    private val pool = Executors.newSingleThreadExecutor()

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
        "(?i)\\b(otp|verification code|security code|one[- ]time (pass)?code|passcode|2fa|login code)\\b\\D{0,30}\\b\\d{4,8}\\b"
    )
    private val promoWords = Regex("(?i)promo|\\bsale\\b|% off|coupon|discount")
    private val lastTextByKey = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName || sbn.packageName in ignoredPackages) return
        if (!Blocking.isActive() || Blocking.isPaused()) return
        if (sbn.isOngoing || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        if (n.category in passThroughCategories) return
        // Group summaries duplicate their children; just remove them.
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            cancelNotification(sbn.key)
            return
        }
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val pkg = sbn.packageName
        val category = n.category
        val contentIntent = n.contentIntent
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        // Messaging apps re-post the same key with unchanged text; skip duplicates.
        val sig = "$title|$text"
        synchronized(lastTextByKey) {
            if (lastTextByKey[sbn.key] == sig) { cancelNotification(sbn.key); return }
            lastTextByKey[sbn.key] = sig
            if (lastTextByKey.size > 500) lastTextByKey.clear()
        }
        cancelNotification(sbn.key)
        pool.execute { triage(sbn.key, pkg, app, title, text, category, contentIntent) }
    }

    private fun triage(
        key: String, pkg: String, app: String, title: String, text: String,
        category: String?, contentIntent: PendingIntent?,
    ) {
        val verdict = decide(pkg, app, title, text, category)
        Prefs.addDigest(DigestItem(app, title, text, System.currentTimeMillis(), verdict.urgent, verdict.reason))
        if (verdict.urgent) repost(key, pkg, app, title, text, verdict.reason, contentIntent)
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

    private fun repost(
        key: String, pkg: String, app: String, title: String, text: String,
        reason: String, contentIntent: PendingIntent?,
    ) {
        val intent = contentIntent ?: PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(pkg) ?: Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, AwayApp.CH_URGENT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$app: $title")
            .setContentText(text)
            .setSubText("Urgent: $reason")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        getSystemService(NotificationManager::class.java).notify(key.hashCode(), n)
    }
}
