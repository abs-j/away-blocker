package app.away

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.util.Log
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.graphics.Typeface
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.media.AudioManager

/**
 * Detects blocked apps opening, covers the screen, sends the user Home and kills the app's
 * background process. Also hosts the lock screen notification (time since you last used the phone).
 */
class BlockerService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var shield: View? = null
    private var currentPkg: String? = null
    private var lastBlockAt = 0L
    private var quitting = false
    private val hideRunnable = Runnable { hideShield(); quitting = false }

    private val tick = object : Runnable {
        override fun run() {
            refreshLabels()
            val now = System.currentTimeMillis()
            val pkg = currentPkg
            val interactive = getSystemService(PowerManager::class.java).isInteractive
            if (pkg != null && pkg != packageName && interactive) {
                if (Blocking.shouldBlock(pkg, now)) {
                    // Already open when blocking started or a pause ended.
                    block(pkg, now)
                } else if (pkg in Prefs.blocked) {
                    // Still using a distracting app: keep the home timer at 0 until you leave it.
                    Prefs.lastUse = now
                }
            }
            syncPhoneState()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onServiceConnected() {
        instance = this
        registerScreenReceiver()
        wm =getSystemService(WINDOW_SERVICE) as WindowManager
        ensureShield()
        refreshLabels()
        handler.post(tick)
    }

    @Volatile private var blockedLabels: Set<String> = emptySet()
    private var labelsFor: Set<String> = emptySet()

    private fun refreshLabels() {
        val b = Prefs.blocked
        if (b == labelsFor) return
        labelsFor = b
        blockedLabels = b.mapNotNull { pkg ->
            runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString().lowercase()
            }.getOrNull()
        }.toSet()
    }

    private val preemptHide = Runnable { if (!quitting) hideShield() }

    /**
     * Tapping a blocked app's icon in the launcher or Recents: cover the screen before the app even starts,
     * so the user never sees the launcher between the tap and the block.
     */
    private fun preempt(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || quitting) return
        if (!pkg.contains("launcher", ignoreCase = true) && pkg != homePackage()) return
        val now = System.currentTimeMillis()
        if (!Blocking.isActive(now) || Blocking.isPaused(now)) return
        // Cheap checks only (no binder calls): the label cache is refreshed off the hot path.
        val tapped = listOfNotNull(event.contentDescription, *event.text.toTypedArray())
            .map { it.toString().trim().lowercase() }.filter { it.isNotEmpty() }
        if (tapped.isEmpty()) return
        val labels = blockedLabels
        val hit = tapped.any { t -> t in labels || labels.any { t.startsWith("$it,") } }
        if (!hit) return
        showShield()
        handler.removeCallbacks(preemptHide)
        handler.postDelayed(preemptHide, 3000) // if no block follows (launch failed), drop it
    }

    private fun homePackage(): String? = packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
    )?.activityInfo?.packageName

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) { preempt(event); return }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || IME_HINTS.any { pkg.contains(it, ignoreCase = true) }) return
        // Away's own overlay windows (shield) also fire window events; only real Away
        // screens count, otherwise the foreground flips to Away and resets the use timer.
        if (pkg == packageName && event.className?.startsWith("app.away.") != true) return
        val prev = currentPkg
        currentPkg = pkg
        if (pkg == packageName) return
        val now = System.currentTimeMillis()
        if (Blocking.shouldBlock(pkg, now)) {
            block(pkg, now)
        } else if (pkg in Prefs.blocked) {
            // Opening a distracting app that is not being blocked (Focus off, paused or apps not
            // hidden) resets the home timer straight away. Blocked attempts never get here.
            if (pkg != prev) Stats.recordOpen(now, now - maxOf(Prefs.lastUse, Prefs.installTime))
            Prefs.lastUse = now
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        handler.removeCallbacksAndMessages(null)
        hidePause()
        shield?.let { runCatching { wm.removeView(it) } }
        shield = null
        super.onDestroy()
    }

    private fun block(pkg: String, now: Long) {
        // A quit sequence (Home, Recents, close card, Home) is already running; do not restart it.
        if (quitting) return
        Stats.recordBlocked(now)
        Log.d("Away", "block $pkg")
        lastBlockAt = now
        showShield()
        performGlobalAction(GLOBAL_ACTION_HOME)
        currentPkg = null
        // Keep the shield up until the launcher has surely taken over, then drop it.
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 1200)
        quitFromRecents(pkg)
        // Android 14+ only lets an app kill its own processes, so this is a no-op there.
        if (Build.VERSION.SDK_INT < 34) {
            val am = getSystemService(ActivityManager::class.java)
            handler.postDelayed({ am.killBackgroundProcesses(pkg) }, 700)
            handler.postDelayed({ am.killBackgroundProcesses(pkg) }, 3000)
        }
    }

    /** Open Recents, swipe the blocked app card away (ends the app, removes its thumbnail), go Home. */
    private fun quitFromRecents(pkg: String) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull() ?: return
        quitting = true
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 5000) // hard fallback so the shield can never stick
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            pollForCard(label, 0)
        }, 350)
    }

    private fun pollForCard(label: String, attempt: Int) {
        handler.postDelayed({
            val roots = (windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow))
            Log.d("Away", "poll $attempt label=$label windows=${roots.map { it.packageName }}")
            val card = roots.firstNotNullOfOrNull { findCard(it, label) }
            if (card != null) {
                Log.d("Away", "card found, swiping")
                val r = Rect()
                card.getBoundsInScreen(r)
                Log.d("Away", "card bounds $r actions=${card.actionList.map { "${it.id}:${it.label}" }}")
                // Prefer the card's own Close/Dismiss accessibility action; fall back to a swipe.
                val close = card.actionList.firstOrNull {
                    it.label?.toString()?.contains(Regex("(?i)close|dismiss|remove")) == true
                } ?: card.actionList.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_DISMISS }
                if (close != null && card.performAction(close.id)) {
                    Log.d("Away", "closed card via action ${close.label}")
                    handler.postDelayed({ finishQuit() }, 400)
                    return@postDelayed
                }
                handler.postDelayed({
                    val r2 = Rect()
                    card.refresh()
                    card.getBoundsInScreen(r2)
                    Log.d("Away", "settled bounds $r2")
                    swipeUp(r2)
                }, 500)
            } else if (attempt < 8) {
                pollForCard(label, attempt + 1)
            } else {
                finishQuit()
            }
        }, 250)
    }

    private fun findCard(n: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val d = n.contentDescription?.toString()
        if (d != null && d.startsWith(label, ignoreCase = true)) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 300 && r.height() > 600) return n
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findCard(c, label)?.let { return it }
        }
        return null
    }

    private fun swipeUp(r: Rect) {
        val path = Path().apply {
            moveTo(r.exactCenterX(), r.centerY().toFloat())
            lineTo(r.exactCenterX(), maxOf(5f, r.top - r.height() * 0.4f))
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 180)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d("Away", "swipe completed")
                handler.postDelayed({ finishQuit() }, 300)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.d("Away", "swipe cancelled"); finishQuit()
            }
        }, null)
        if (!ok) finishQuit()
    }

    private fun finishQuit() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 500)
    }

    companion object {
        @Volatile var instance: BlockerService? = null

        /** Locks the screen via the accessibility service. False if the service is not running. */
        fun lockScreen(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        private val IME_HINTS = listOf("inputmethod", "keyboard", "honeyboard", "swiftkey")
    }

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    )

    /** The shield window stays attached (invisible) so showing it is a single visibility flip, not a window add. */
    private fun ensureShield() {
        if (shield != null) return
        val v = TextView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF05060B.toInt(), 0xFF13163A.toInt(), 0xFF05060B.toInt()),
            )
            text = "Blocked"
            setTextColor(0xFFEDEEF6.toInt())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = 0.08f
            textSize = 26f
            gravity = Gravity.CENTER
            visibility = View.INVISIBLE
        }
        val p = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        ).apply {
            format = PixelFormat.OPAQUE
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { wm.addView(v, p); shield = v }
    }

    private fun showShield() {
        ensureShield()
        shield?.visibility = View.VISIBLE
    }

    private fun hideShield() {
        shield?.visibility = View.INVISIBLE
    }

    /** True from unlock until the screen turns off again. */
    private var unlocked = false
    private var shownText = ""
    private var indicatorHidden = false
    /** False until the first state check after the service starts, which is not a real pickup. */
    private var synced = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            syncPhoneState()
            // Face and fingerprint unlock may send no broadcast; watch closely while the lock screen is up.
            if (i.action == Intent.ACTION_SCREEN_ON && !unlocked) startUnlockWatch()
        }
    }

    private val unlockWatch = object : Runnable {
        var until = 0L
        override fun run() {
            syncPhoneState()
            val interactive = getSystemService(PowerManager::class.java).isInteractive
            if (!unlocked && interactive && System.currentTimeMillis() < until) handler.postDelayed(this, 250)
        }
    }

    private fun startUnlockWatch() {
        handler.removeCallbacks(unlockWatch)
        unlockWatch.until = System.currentTimeMillis() + 60_000
        handler.post(unlockWatch)
    }

    private fun registerScreenReceiver() {
        syncPhoneState()
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Works out whether the phone is in use by checking the keyguard directly (face and fingerprint
     * unlock do not always send USER_PRESENT), records the moment you put it down, and refreshes the
     * lock screen notification. Called from the screen broadcasts and every tick while awake.
     */
    private fun syncPhoneState() {
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val inUse = interactive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (unlocked && !inUse) {
            Prefs.lastPhoneUse = System.currentTimeMillis()
            unlocked = false
            hidePause()
        } else if (!unlocked && inUse) {
            unlocked = true
            val now = System.currentTimeMillis()
            if (synced) {
                Stats.recordPickup(now, now - (Prefs.lastPhoneUse.takeIf { it > 0 } ?: Prefs.installTime))
                maybeShowUnlockPause(now)
            }
        }
        synced = true
        postIndicatorNotification()
    }

    // --- Unlock pause: a short full-screen count after unlocking, to think before using the phone. ---

    private var pauseView: View? = null
    private var pauseLeft = 0
    private val pauseTick = object : Runnable {
        override fun run() {
            val v = pauseView ?: return
            if (inCall()) { hidePause(); return }
            if (pauseLeft > 0) {
                pauseLeft -= 1
                v.findViewWithTag<TextView>("count")?.let { popDigit(it, pauseLeft.toString()) }
            }
            if (pauseLeft == 0) {
                v.findViewWithTag<View>("go")?.let { go ->
                    if (go.visibility != View.VISIBLE) {
                        go.alpha = 0f
                        go.visibility = View.VISIBLE
                        go.animate().alpha(1f).setDuration(350).start()
                    }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val pauseFailsafe = Runnable { hidePause() }

    /** The digit swells then settles with a small overshoot, so each second lands with weight. */
    private fun popDigit(tv: TextView, text: String) {
        tv.text = text
        tv.scaleX = 1.45f; tv.scaleY = 1.45f; tv.alpha = 0.4f
        tv.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(520).setInterpolator(OvershootInterpolator(2f)).start()
    }

    private fun inCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun maybeShowUnlockPause(now: Long) {
        if (pauseView != null || inCall()) return
        val show = when (Prefs.unlockPause) {
            1 -> true
            2 -> Blocking.isFocusOn(now)
            3 -> Blocking.awayMillis(now) >= 60 * 60_000L
            else -> false
        }
        if (show) showPause(now)
    }

    private fun showPause(now: Long) {
        if (!::wm.isInitialized) return
        val prompts = listOf(
            "Do you really need your phone right now?",
            "What were you about to do?",
            "Your streak is worth more than a scroll.",
            "Take a breath. Still want to go in?",
        )
        fun label(t: String, sp: Float, c: Int) = TextView(this).apply {
            text = t; textSize = sp; setTextColor(c); gravity = Gravity.CENTER
        }
        fun btn(t: String, tag: String, fill: Int, ink: Int) = Button(this).apply {
            text = t; this.tag = tag; isAllCaps = false; setTextColor(ink)
            background = GradientDrawable().apply { setColor(fill); cornerRadius = 100f }
            setPadding(64, 24, 64, 24)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF05060B.toInt(), 0xFF171A45.toInt(), 0xFF05060B.toInt()),
            )
            setPadding(64, 64, 64, 64)
            alpha = 0f
        }
        root.addView(label("${Blocking.formatDuration(Blocking.awayMillis(now))} since a distracting app", 18f, Color.LTGRAY))
        root.addView(label("5", 132f, Color.WHITE).apply {
            tag = "count"
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        })
        root.addView(label(prompts.random(), 20f, Color.WHITE).apply { setPadding(0, 0, 0, 64) })
        root.addView(btn("Lock phone", "lock", 0xFF4A5BD8.toInt(), Color.WHITE).apply {
            setOnClickListener { hidePause(); performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) }
        })
        root.addView(btn("Continue", "go", 0xFF2B2B2B.toInt(), Color.LTGRAY).apply {
            visibility = View.INVISIBLE
            setOnClickListener { hidePause() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 32 })
        val p = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        ).apply {
            format = PixelFormat.OPAQUE
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (runCatching { wm.addView(root, p) }.isFailure) return
        pauseView = root
        pauseLeft = 5
        root.animate().alpha(1f).setDuration(320).start()
        root.findViewWithTag<TextView>("count")?.let { popDigit(it, "5") }
        handler.removeCallbacks(pauseTick)
        handler.postDelayed(pauseTick, 1000)
        // Never trap the phone: the overlay removes itself after two minutes whatever happens.
        handler.removeCallbacks(pauseFailsafe)
        handler.postDelayed(pauseFailsafe, 120_000)
    }

    private fun hidePause() {
        handler.removeCallbacks(pauseTick)
        handler.removeCallbacks(pauseFailsafe)
        pauseView?.let { v ->
            // Fade out rather than vanish; the window is removed once the fade ends.
            v.animate().alpha(0f).setDuration(180).withEndAction { runCatching { wm.removeView(v) } }.start()
        }
        pauseView = null
    }

    /**
     * Lock screen notification, in whole minutes since you last used the phone. A system chronometer
     * counts seconds, so this is plain text instead, refreshed whenever the screen wakes and each
     * minute while it stays on. Not shown while the phone is in use or before the chosen untouched time (default 1 hour).
     */
    private fun postIndicatorNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val since = Prefs.lastPhoneUse.takeIf { it > 0 } ?: Prefs.installTime
        val untouched = (System.currentTimeMillis() - since).coerceAtLeast(0)
        // Hidden while the phone is in use and until it has sat untouched for the chosen time.
        if (unlocked || untouched < Prefs.indicatorAfterMin * 60_000L) {
            // Also clears one left by an earlier run of the service.
            if (!indicatorHidden) { nm.cancel(1); indicatorHidden = true; shownText = "" }
            return
        }
        indicatorHidden = false
        val title = "Untouched for " + Blocking.formatDuration(untouched)
        if (title == shownText) return
        shownText = title
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, AwayApp.CH_INDICATOR)
            .setSmallIcon(R.drawable.ic_stat_away)
            .setContentTitle(title)
            .setContentText("Since you last used your phone")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
        nm.notify(1, b.build())
    }
}
