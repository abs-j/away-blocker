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
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * While the hold is active: remove every notification from the bar (new ones and ones already
 * showing), classify it for the digest, and keep a copy in memory. When the hold ends, post the
 * held notifications back so nothing is lost. The held set is also saved to storage, so a process
 * restart (app update, Android killing Away) does not lose it.
 */
class TriageListener : NotificationListenerService() {
    private val pool = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    /** A removed notification, kept so it can be shown again when the hold ends. */
    private class Held(
        val key: String, val pkg: String, val app: String, val title: String, val text: String,
        val time: Long, val icon: Icon?, val contentIntent: PendingIntent?,
        /** The notification as the app posted it, kept in memory so reply buttons and style survive. */
        val orig: Notification? = null,
    )

    private val held = LinkedHashMap<String, Held>()
    @Volatile private var holding = false

    private val tick = object : Runnable {
        override fun run() {
            syncHold()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onListenerConnected() {
        instance = this
        loadPersisted()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** Saves what is held (without icons and tap actions, which cannot be stored). */
    private fun persist() {
        val size = synchronized(held) {
            val arr = JSONArray()
            held.values.forEach {
                arr.put(JSONObject().put("key", it.key).put("pkg", it.pkg).put("app", it.app)
                    .put("title", it.title).put("text", it.text).put("time", it.time))
            }
            Prefs.heldStore = arr.toString()
            held.size
        }
        heldCount.intValue = size
    }

    /** After a restart, brings back held notifications saved before the process died. */
    private fun loadPersisted() {
        val arr = runCatching { JSONArray(Prefs.heldStore) }.getOrNull() ?: return
        synchronized(held) {
            for (i in 0 until arr.length()) {
                runCatching {
                    val o = arr.getJSONObject(i)
                    val key = o.optString("key")
                    if (key.isNotBlank() && key !in held) {
                        held[key] = Held(key, o.optString("pkg"), o.optString("app"), o.optString("title"),
                            o.optString("text"), o.optLong("time"), null, null)
                    }
                }
            }
        }
        heldCount.intValue = synchronized(held) { held.size }
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(tick)
        if (instance === this) instance = null
        // Notification access was revoked: put everything back at once.
        if (!androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            holding = false
            restoreHeld()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pool.shutdown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** False when the user turned off Away's notifications; restored items would be dropped silently. */
    private fun canPost() = getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** Starts or ends the hold when the setting or schedule says so. Safe to call often. */
    fun syncHold() {
        val want = Blocking.isHoldActive()
        if (want && !holding) {
            holding = true
            // Notifications already showing were seen; hide them without alerting again.
            runCatching { activeNotifications }.getOrNull()?.forEach { process(it, fresh = false) }
        } else if (!want && holding) {
            holding = false
            restoreHeld()
        } else if (!want && synchronized(held) { held.isNotEmpty() }) {
            // Restarted after the hold ended: put back what was saved.
            restoreHeld()
        }
    }

    private fun restoreHeld(onlyPkg: String? = null, onlyTitle: String? = null) {
        if (!canPost()) return
        val items = synchronized(held) {
            val out = held.values.filter {
                onlyPkg == null || (it.pkg == onlyPkg &&
                    (onlyTitle.isNullOrBlank() || it.title.trim().equals(onlyTitle.trim(), ignoreCase = true)))
            }
            out.forEach { held.remove(it.key) }
            out
        }
        persist()
        val nm = getSystemService(NotificationManager::class.java)
        items.forEach { h ->
            // Best case: rebuild the app's own notification (buttons, reply, conversation style) in our
            // quiet channel. After a restart only the text survives, so fall back to a plain copy.
            val original = h.orig?.let { o ->
                runCatching {
                    Notification.Builder.recoverBuilder(this, o)
                        .setChannelId(AwayApp.CH_RESTORED)
                        .setGroup("held_${h.key}")
                        .setGroupSummary(false)
                        .setOngoing(false)
                        .setAutoCancel(true)
                        .setWhen(h.time)
                        .setShowWhen(true)
                        .build()
                }.getOrNull()
            }
            if (original != null) {
                nm.notify("held", h.key.hashCode(), original)
                return@forEach
            }
            val b = Notification.Builder(this, AwayApp.CH_RESTORED)
                .setSmallIcon(h.icon ?: Icon.createWithResource(this, R.drawable.ic_stat_away))
                .setContentTitle(h.title.ifBlank { h.app })
                .setContentText(h.text)
                .setSubText(h.app)
                .setStyle(Notification.BigTextStyle().bigText(h.text))
                .setWhen(h.time)
                .setShowWhen(true)
                .setAutoCancel(true)
                // A unique group per notification stops Android folding them under one Away bundle.
                .setGroup("held_${h.key}")
            (h.contentIntent ?: openAppIntent(h.pkg, h.key))?.let { b.setContentIntent(it) }
            nm.notify("held", h.key.hashCode(), b.build())
        }
    }

    /** Tap action for a notification restored from storage: open its app. */
    private fun openAppIntent(pkg: String, key: String): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return null
        return PendingIntent.getActivity(this, key.hashCode(), launch, PendingIntent.FLAG_IMMUTABLE)
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
    private val ignoredPackages = setOf(
        "android", "com.android.systemui", "com.android.providers.downloads",
        "com.abs.circletochatgpt", // reply drafts and agent status from Circle to ChatGPT must stay visible
    )
    private val lastTextByKey = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        syncHold()
        if (holding) process(sbn, fresh = true)
    }

    /**
     * Removes one notification and records it. Returns false if it was left alone. [fresh] is true
     * for a newly posted notification, which may break through the hold if it is urgent.
     */
    private fun process(sbn: StatusBarNotification, fresh: Boolean): Boolean {
        val n = sbn.notification ?: return false
        if (sbn.packageName == packageName || sbn.packageName in ignoredPackages) return false
        // The user chose to let this app, or this chat, through (from the digest): leave it untouched.
        if (Prefs.isPassing(sbn.packageName, sbn.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString())) return false
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
            held[sbn.key] = Held(sbn.key, pkg, app, title, text, sbn.postTime, n.smallIcon, n.contentIntent, n)
        }
        persist()
        // Messaging apps re-post the same key with unchanged text; only classify once.
        val sig = "$title|$text"
        val isNew = synchronized(lastTextByKey) {
            val same = lastTextByKey[sbn.key] == sig
            lastTextByKey[sbn.key] = sig
            if (lastTextByKey.size > 500) lastTextByKey.clear()
            !same
        }
        cancelNotification(sbn.key)
        if (isNew) pool.execute { triage(sbn.key, pkg, app, title, text, category, fresh) }
        return true
    }

    private fun triage(
        key: String, pkg: String, app: String, title: String, text: String, category: String?, fresh: Boolean,
    ) {
        val verdict = decide(pkg, app, title, text, category)
        Prefs.addDigest(DigestItem(app, title, text, System.currentTimeMillis(), verdict.urgent, verdict.reason, pkg))
        if (verdict.urgent && fresh && holding) breakThrough(key, verdict.reason)
    }

    /**
     * Urgent breakthrough: show an urgent notification once as a heads-up alert while the hold
     * continues. It is taken out of the held set so it is not shown a second time when the hold ends.
     */
    private fun breakThrough(key: String, reason: String) {
        if (!canPost()) return
        val h = synchronized(held) { held.remove(key) } ?: return
        persist()
        val b = Notification.Builder(this, AwayApp.CH_URGENT)
            .setSmallIcon(h.icon ?: Icon.createWithResource(this, R.drawable.ic_stat_away))
            .setContentTitle(h.title.ifBlank { h.app })
            .setContentText(h.text)
            .setSubText("${h.app} · urgent: $reason")
            .setStyle(Notification.BigTextStyle().bigText(h.text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setWhen(h.time)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setGroup("urgent_${h.key}")
        (h.contentIntent ?: openAppIntent(h.pkg, h.key))?.let { b.setContentIntent(it) }
        getSystemService(NotificationManager::class.java).notify("urgent", h.key.hashCode(), b.build())
    }

    private fun decide(pkg: String, app: String, title: String, text: String, category: String?): Verdict {
        val vips = Prefs.vipList()
        // A rule match is decided on the phone and never sent to the AI.
        TriageRules.local(title, text, category, vips)?.let { return it }
        val key = Prefs.apiKey
        // Without AI (or for private apps), the default is not urgent.
        if (!Prefs.triageEnabled || key.isBlank() || pkg in Prefs.privateSet()) {
            return Verdict(false, "not urgent (on-device rules)")
        }
        val v = Haiku.classify(app, title, text, vips, key)
        if (v != null) return v
        return Verdict(false, "not urgent (AI unavailable)")
    }

    companion object {
        @Volatile private var instance: TriageListener? = null

        /** Applies a hold setting change right away instead of waiting for the next tick. */
        fun syncNow() { instance?.let { l -> l.handler.post { l.syncHold() } } }

        /** Number of notifications held right now, for the launcher and the Digest screen. */
        val heldCount = mutableIntStateOf(0)

        /** Puts every held notification back now. The hold stays on for new ones. */
        fun releaseNow() { instance?.let { l -> l.handler.post { l.restoreHeld() } } }

        /** Puts back what is held for one app, or one conversation of it, when a not-held rule is set. */
        fun releaseApp(pkg: String, title: String? = null) {
            instance?.let { l -> l.handler.post { l.restoreHeld(pkg, title) } }
        }
    }
}
