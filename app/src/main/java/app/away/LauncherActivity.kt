package app.away

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppEntry(val pkg: String, val label: String)

object Apps {
    /** Last loaded app list so the home screen draws instantly; refreshed off the main thread. */
    @Volatile var cache: List<AppEntry> = emptyList()

    fun loadAll(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
    }

    fun displayName(e: AppEntry, renames: Map<String, String>) = renames[e.pkg] ?: e.label

    /** Apps the launcher may show right now: not hidden, not blocked while blocking is active, not over a daily limit. */
    fun visible(all: List<AppEntry>): List<AppEntry> {
        val hidden = Prefs.hidden
        val blockNow = Blocking.isActive() && !Blocking.isPaused()
        val blocked = Prefs.blocked
        // An app past its daily limit is blocked until midnight, so it also leaves home and search.
        return all.filter { it.pkg !in hidden && !(blockNow && it.pkg in blocked) && !UsageTracker.overLimit(it.pkg) }
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        if (Blocking.shouldBlock(pkg)) {
            Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show()
            return false
        }
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        val fade = android.app.ActivityOptions.makeCustomAnimation(ctx, android.R.anim.fade_in, android.R.anim.fade_out)
        ctx.startActivity(i, fade.toBundle())
        return true
    }

    /** Lower is better, -1 means no match. Prefix, word prefix, substring, initials, then subsequence. */
    fun rank(label: String, q: String): Int {
        val l = label.lowercase()
        if (l.startsWith(q)) return 0
        val words = l.split(' ', '-', '.', '_').filter { it.isNotEmpty() }
        if (words.any { it.startsWith(q) }) return 1
        if (l.contains(q)) return 2
        if (words.map { it[0] }.joinToString("").startsWith(q)) return 3
        var qi = 0
        for (c in l) if (qi < q.length && c == q[qi]) qi++
        return if (qi == q.length) 4 else -1
    }

    fun search(apps: List<AppEntry>, renames: Map<String, String>, query: String): List<AppEntry> {
        val q = query.trim().lowercase()
        val sorted = apps.sortedBy { displayName(it, renames).lowercase() }
        if (q.isEmpty()) return sorted
        return sorted
            .map { it to rank(displayName(it, renames), q) }
            .filter { it.second >= 0 }
            .sortedBy { it.second }
            .map { it.first }
    }
}

/** Lock screen wallpaper if the OS lets us read it, else the home wallpaper is shown by the window itself. */
object Wallpaper {
    @Volatile var cached: androidx.compose.ui.graphics.ImageBitmap? = null

    private fun open(wm: android.app.WallpaperManager, flag: Int) =
        runCatching { wm.getWallpaperFile(flag) }.getOrNull()

    fun load(ctx: Context): androidx.compose.ui.graphics.ImageBitmap? {
        val wm = android.app.WallpaperManager.getInstance(ctx)
        for (flag in intArrayOf(android.app.WallpaperManager.FLAG_LOCK, android.app.WallpaperManager.FLAG_SYSTEM)) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(wm, flag)?.use { android.graphics.BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds) }
            if (bounds.outWidth <= 0) continue
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1080) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = open(wm, flag)?.use {
                android.graphics.BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, opts)
            } ?: continue
            return bmp.asImageBitmap().also { cached = it }
        }
        return null
    }
}

class LauncherActivity : ComponentActivity() {
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Thread { Apps.cache = Apps.loadAll(applicationContext) }.start()
        enableEdgeToEdge()
        setContent { LauncherRoot(resumes.intValue) }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already on home: bump so the drawer closes.
        resumes.intValue++
    }
}

/** Text colour for everything drawn over the wallpaper: dark on bright wallpapers, white on dark ones. */
val LocalInk = androidx.compose.runtime.compositionLocalOf { Color.White }

private val Dim: Color @Composable get() = LocalInk.current.copy(alpha = 0.7f)

fun wallpaperWantsDarkText(ctx: Context): Boolean {
    val wm = android.app.WallpaperManager.getInstance(ctx)
    val c = runCatching {
        wm.getWallpaperColors(android.app.WallpaperManager.FLAG_LOCK)
            ?: wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
    }.getOrNull() ?: return false
    return if (android.os.Build.VERSION.SDK_INT >= 31) {
        c.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
    } else {
        androidx.core.graphics.ColorUtils.calculateLuminance(c.primaryColor.toArgb()) > 0.6
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherRoot(resumeRev: Int) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var localRev by remember { mutableIntStateOf(0) }
    var drawer by remember { mutableStateOf(false) }
    var menuApp by remember { mutableStateOf<AppEntry?>(null) }
    var renaming by remember { mutableStateOf<AppEntry?>(null) }
    var pending by remember { mutableStateOf<Pair<AppEntry, Int>?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(5000)
        }
    }
    // Close the drawer when home is pressed again.
    LaunchedEffect(resumeRev) { drawer = false; now = System.currentTimeMillis() }
    // Home is the root: swallow Back here. Registered first, so the drawer's and the launch
    // countdown's own Back handlers (composed later) take priority while they are showing.
    BackHandler { }

    // Recomputed whenever the clock ticks, the activity resumes or an edit happens.
    var all by remember { mutableStateOf(Apps.cache) }
    LaunchedEffect(resumeRev) {
        val fresh = withContext(Dispatchers.Default) { Apps.loadAll(ctx) }
        Apps.cache = fresh
        if (fresh != all) all = fresh
    }
    var darkInk by remember { mutableStateOf(wallpaperWantsDarkText(ctx)) }
    LaunchedEffect(resumeRev) { darkInk = wallpaperWantsDarkText(ctx) }
    val ink by animateColorAsState(if (darkInk) Color(0xFF111111) else Color.White, tween(250), label = "ink")
    var wallpaper by remember { mutableStateOf(Wallpaper.cached) }
    LaunchedEffect(resumeRev) {
        val w = withContext(Dispatchers.Default) { Wallpaper.load(ctx) }
        Log.d("AwayLauncher", "lock wallpaper loaded=${w != null}")
        wallpaper = w
    }
    val haptic = LocalHapticFeedback.current
    val homeAlpha by animateFloatAsState(if (drawer) 0f else 1f, tween(200), label = "homeAlpha")
    val renames = remember(resumeRev, localRev) { Prefs.renames }
    val visible = remember(now / 5000, resumeRev, localRev, all) { Apps.visible(all) }
    val byPkg = remember(visible) { visible.associateBy { it.pkg } }

    val focus = Blocking.isActive(now) && !Blocking.isPaused(now)

    fun go(app: AppEntry) {
        val d = Prefs.delays[app.pkg] ?: 0
        if (d > 0) pending = app to d else Apps.launch(ctx, app.pkg)
    }

    fun swipeApp(pkg: String, fallback: Intent) {
        if (pkg.isNotBlank() && Apps.launch(ctx, pkg)) return
        runCatching { ctx.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openAway(tab: Int) {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("tab", tab))
    }

    CompositionLocalProvider(LocalInk provides ink) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (!BlockerService.lockScreen()) {
                            Toast.makeText(ctx, "Enable the Away accessibility service to lock", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); openAway(0) },
                )
            }
            .pointerInput(Unit) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        when {
                            -dy > 160 && -dy > Math.abs(dx) -> {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); drawer = true
                            }
                            dx < -160 && -dx > Math.abs(dy) -> {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                swipeApp(Prefs.swipeLeftApp, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                            }
                            dx > 160 && dx > Math.abs(dy) -> {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                swipeApp(Prefs.swipeRightApp, Intent(Intent.ACTION_DIAL))
                            }
                        }
                    },
                ) { _, d -> dx += d.x; dy += d.y }
            }
            ,
    ) {
        wallpaper?.let {
            androidx.compose.foundation.Image(
                bitmap = it, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Scrim keeps white text readable on any wallpaper, darker at the top and bottom edges.
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.40f), Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.50f)),
                ),
            ),
        )
        val pinned = Prefs.pinned.mapNotNull { byPkg[it] }.let { if (focus) it.take(FOCUS_LIMIT) else it }
        Column(
            Modifier.fillMaxSize().graphicsLayer { alpha = homeAlpha }
                .systemBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Text(
                SimpleDateFormat(
                    if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm",
                    Locale.getDefault(),
                ).format(Date(now)),
                Modifier.staggerIn(0), fontSize = 68.sp, fontWeight = FontWeight.ExtraLight,
            )
            Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now)), Modifier.staggerIn(1), color = Dim, fontSize = 16.sp)
            val away = Blocking.awayMillis(now)
            Text(
                Blocking.formatDuration(away) + " away",
                Modifier.padding(top = 20.dp).staggerIn(2), fontSize = 30.sp,
            )
            Text(
                Motivation.line(away, Stats.best, maxOf(Prefs.lastUse, Prefs.installTime)),
                Modifier.padding(top = 2.dp).staggerIn(3), color = Dim, fontSize = 16.sp,
            )
            // Live count of what is held right now, not the digest history.
            val held = TriageListener.heldCount.intValue
            if (held > 0) {
                Text(
                    "$held notification${if (held == 1) "" else "s"} held",
                    Modifier.padding(top = 4.dp).combinedClickable(onClick = { openAway(2) }),
                    color = Dim, fontSize = 16.sp,
                )
            }
            if (focus) {
                Text(
                    "focus on · unlock",
                    Modifier.padding(top = 4.dp).combinedClickable(onClick = { openAway(0) }),
                    color = Dim, fontSize = 16.sp,
                )
            }
            // The blocker service tracks app use; without it the timer above is frozen.
            // Android sometimes fails to restart it after an update even though it is enabled.
            if (BlockerService.instance == null) {
                Text(
                    "timer paused · Away service not running · fix",
                    Modifier.padding(top = 4.dp).combinedClickable(onClick = {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }),
                    color = Color(0xFFFFB4AB), fontSize = 16.sp,
                )
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().animateContentSize(tween(220)),
                verticalArrangement = Arrangement.Center,
            ) {
                if (pinned.isEmpty()) {
                    Text("Swipe up for apps.\nLong-press an app there to pin it.", color = Dim, fontSize = 16.sp)
                }
                pinned.forEachIndexed { idx, app ->
                    val d = Prefs.delays[app.pkg] ?: 0
                    PressableText(
                        Apps.displayName(app, renames) + if (d > 0) " ·${d}s" else "",
                        modifier = Modifier.staggerIn(4 + idx),
                        fontSize = 28.sp,
                        onClick = { go(app) },
                        onLong = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuApp = app },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = drawer,
            enter = fadeIn(tween(180)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 8 },
            exit = fadeOut(tween(130)) + slideOutVertically(tween(160)) { it / 10 },
        ) {
            Drawer(
                apps = visible,
                renames = renames,
                onLaunch = { drawer = false; go(it) },
                onLong = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuApp = it },
                onClose = { drawer = false },
            )
        }

        pending?.let { (app, secs) ->
            LaunchedEffect(app.pkg, secs) {
                delay(1000)
                if (secs <= 1) {
                    pending = null
                    Apps.launch(ctx, app.pkg)
                } else {
                    pending = app to secs - 1
                }
            }
            BackHandler { pending = null }
            CompositionLocalProvider(LocalInk provides Color.White) {
            AnimatedVisibility(visible = true, enter = fadeIn(tween(200))) {
                Column(
                    Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Opening ${Apps.displayName(app, renames)}", fontSize = 22.sp)
                    AnimatedContent(
                        targetState = secs,
                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                        label = "countdown",
                    ) { s ->
                        Text("$s", fontSize = 72.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    TextButton(onClick = { pending = null }) { Text("Never mind", color = Color.White, fontSize = 18.sp) }
                }
            }
            }
        }
    }
    }

    // Dialogs use the same dark theme as Away's settings.
    menuApp?.let { app ->
        val isPinned = app.pkg in Prefs.pinned
        val delaySecs = Prefs.delays[app.pkg] ?: 0
        AwayTheme {
            AlertDialog(
                onDismissRequest = { menuApp = null },
                confirmButton = { TextButton(onClick = { menuApp = null }) { M3Text("Close") } },
                title = { M3Text(Apps.displayName(app, renames)) },
                text = {
                    Column {
                        MenuItem(if (isPinned) "Unpin from home" else "Pin to home") {
                            Prefs.pinned = if (isPinned) Prefs.pinned - app.pkg else Prefs.pinned + app.pkg
                            menuApp = null; localRev++
                        }
                        MenuItem("Rename") { renaming = app; menuApp = null }
                        MenuItem("Launch delay: ${if (delaySecs == 0) "off" else "${delaySecs}s"}") {
                            val next = when (delaySecs) { 0 -> 5; 5 -> 10; 10 -> 15; else -> 0 }
                            Prefs.delays = Prefs.delays.toMutableMap().also { if (next == 0) it.remove(app.pkg) else it[app.pkg] = next }
                            menuApp = null; localRev++
                        }
                        MenuItem("Swipe left opens this") { Prefs.swipeLeftApp = app.pkg; menuApp = null }
                        MenuItem("Swipe right opens this") { Prefs.swipeRightApp = app.pkg; menuApp = null }
                        MenuItem("Hide from search") {
                            Prefs.hidden = Prefs.hidden + app.pkg
                            Prefs.pinned = Prefs.pinned - app.pkg
                            menuApp = null; localRev++
                        }
                    }
                },
            )
        }
    }

    renaming?.let { app ->
        var name by remember(app.pkg) { mutableStateOf(Apps.displayName(app, renames)) }
        AwayTheme {
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { M3Text("Rename") },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        supportingText = { M3Text("Leave blank to use the original name") },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val n = name.trim()
                        Prefs.renames = Prefs.renames.toMutableMap().also {
                            if (n.isEmpty() || n == app.label) it.remove(app.pkg) else it[app.pkg] = n
                        }
                        renaming = null; localRev++
                    }) { M3Text("Save") }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { M3Text("Cancel") } },
            )
        }
    }
}

private const val FOCUS_LIMIT = 4

@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    fontWeight: FontWeight? = null,
) {
    val ink = LocalInk.current
    val c = if (color == Color.Unspecified) ink else color
    // Soft halo in the opposite tone keeps text readable over busy wallpaper.
    val halo = if (c.luminance() > 0.5f) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.35f)
    androidx.compose.material3.Text(
        text, modifier, color = c, fontSize = fontSize, fontWeight = fontWeight,
        style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(halo, Offset(0f, 2f), 10f)),
    )
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    androidx.compose.material3.Text(
        label,
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .combinedClickableCompat(onClick).padding(horizontal = 4.dp, vertical = 14.dp),
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
    )
}

/** Plain Material text for dialogs, without the wallpaper halo. */
@Composable
private fun M3Text(text: String) = androidx.compose.material3.Text(text)

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

@Composable
private fun Drawer(
    apps: List<AppEntry>,
    renames: Map<String, String>,
    onLaunch: (AppEntry) -> Unit,
    onLong: (AppEntry) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query, apps, renames) { Apps.search(apps, renames, query) }
    val focusReq = remember { FocusRequester() }
    // LazyColumn keeps the first visible item when results change, which hid the best matches
    // above the fold. Jump back to the top on every keystroke.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }
    val kb = LocalSoftwareKeyboardController.current

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { focusReq.requestFocus(); kb?.show() }
    DisposableEffect(Unit) { onDispose { kb?.hide() } }
    // A lone match opens after a short pause; typing another character cancels it.
    LaunchedEffect(query, matches) {
        if (query.trim().length >= 2 && matches.size == 1) {
            delay(400)
            onLaunch(matches[0])
        }
    }

    // Swipe down anywhere in the drawer returns to home, as long as the list started at its top.
    // Watches touches before the list or text field see them and never consumes them, so scrolling,
    // taps and the keyboard behave as before.
    Column(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val atTop = !listState.canScrollBackward
                    var dx = 0f
                    var dy = 0f
                    do {
                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        dx = c.position.x - down.position.x
                        dy = c.position.y - down.position.y
                    } while (c.pressed)
                    if (atTop && dy > 90.dp.toPx() && dy > kotlin.math.abs(dx) * 1.5f) onClose()
                }
            }
            .systemBarsPadding().padding(horizontal = 28.dp, vertical = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            if (query.isEmpty()) Text("search apps", color = Dim, fontSize = 28.sp)
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = TextStyle(color = LocalInk.current, fontSize = 28.sp),
                cursorBrush = SolidColor(LocalInk.current),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { matches.firstOrNull()?.let(onLaunch) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusReq),
            )
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(matches, key = { it.pkg }) { app ->
                PressableText(
                    Apps.displayName(app, renames), fontSize = 24.sp,
                    onClick = { onLaunch(app) }, onLong = { onLong(app) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** Plain text row that dips slightly while pressed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PressableText(
    label: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit,
    onLong: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "scale")
    val a by animateFloatAsState(if (pressed) 0.6f else 1f, tween(90), label = "alpha")
    Text(
        label,
        modifier.fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = a; transformOrigin = TransformOrigin(0f, 0.5f) }
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLong)
            .padding(vertical = 10.dp),
        fontSize = fontSize,
    )
}
