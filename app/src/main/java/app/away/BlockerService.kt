package app.away

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
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
import android.widget.TextView

/**
 * Detects blocked apps opening, covers the screen, sends the user Home and kills the app's
 * background process. Also hosts the time-away pill and the lock screen notification.
 */
class BlockerService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var pill: TextView? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var shield: View? = null
    private var currentPkg: String? = null
    private var lastBlockAt = 0L
    private var fgSince = 0L
    private var quitting = false
    private val hideRunnable = Runnable { hideShield(); quitting = false }
    private var ticks = 0

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
                } else if (pkg in Prefs.blocked && now - fgSince > 5000) {
                    // Genuine use: allowed blocked app held the foreground for more than 5s.
                    Prefs.lastUse = now
                }
            }
            updatePill()
            if (ticks++ % 20 == 0) postIndicatorNotification()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onServiceConnected() {
        instance = this
        wm =getSystemService(WINDOW_SERVICE) as WindowManager
        ensureShield()
        refreshLabels()
        addPill()
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
        Log.d("Away", "click pkg=$pkg desc=${event.contentDescription} text=${event.text} srcText=${event.source?.text} srcDesc=${event.source?.contentDescription}")
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
        Log.d("Away", "preempt shield for $tapped")
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
        if (pkg != currentPkg) fgSince = System.currentTimeMillis()
        currentPkg = pkg
        if (pkg == packageName) return
        val now = System.currentTimeMillis()
        if (Blocking.shouldBlock(pkg, now)) {
            block(pkg, now)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        pill?.let { runCatching { wm.removeView(it) } }
        shield?.let { runCatching { wm.removeView(it) } }
        shield = null
        super.onDestroy()
    }

    private fun block(pkg: String, now: Long) {
        // A quit sequence (Home, Recents, close card, Home) is already running; do not restart it.
        if (quitting) return
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
            setBackgroundColor(Color.BLACK)
            text = "Blocked"
            setTextColor(Color.WHITE)
            textSize = 22f
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

    private fun addPill() {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(32, 14, 32, 14)
            background = GradientDrawable().apply {
                cornerRadius = 80f
                setColor(0xCC111111.toInt())
            }
        }
        val p = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 90
        }
        var startY = 0
        var touchY = 0f
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = p.y; touchY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    p.y = (startY + (e.rawY - touchY)).toInt().coerceAtLeast(0)
                    runCatching { wm.updateViewLayout(tv, p) }
                }
            }
            true
        }
        runCatching { wm.addView(tv, p); pill = tv; pillParams = p }
        updatePill()
    }

    private fun label(): String = "Away " + Blocking.formatDuration(Blocking.awayMillis())

    private fun updatePill() {
        pill?.text = label()
    }

    private fun postIndicatorNotification() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, AwayApp.CH_INDICATOR)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label())
            .setContentText("Since you last used a blocked app")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .build()
        getSystemService(NotificationManager::class.java).notify(1, n)
    }
}
