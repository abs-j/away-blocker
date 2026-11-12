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
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppEntry(val pkg: String, val label: String)

object Apps {
    fun loadAll(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
    }

    fun displayName(e: AppEntry, renames: Map<String, String>) = renames[e.pkg] ?: e.label

    /** Apps the launcher may show right now: not hidden, and not blocked while blocking is active. */
    fun visible(all: List<AppEntry>): List<AppEntry> {
        val hidden = Prefs.hidden
        val blockNow = Blocking.isActive() && !Blocking.isPaused()
        val blocked = Prefs.blocked
        return all.filter { it.pkg !in hidden && !(blockNow && it.pkg in blocked) }
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        if (Blocking.shouldBlock(pkg)) {
            Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show()
            return false
        }
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        ctx.startActivity(i)
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

class LauncherActivity : ComponentActivity() {
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        enableEdgeToEdge()
        setContent { LauncherRoot(resumes.intValue) }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }

    @Deprecated("Home press should leave the launcher state as is")
    override fun onBackPressed() {
        // The home screen is the root; Back does nothing here. The drawer handles its own Back.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already on home: bump so the drawer closes.
        resumes.intValue++
    }
}

private val Dim = Color(0xFF8A8A8A)

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

    // Recomputed whenever the clock ticks, the activity resumes or an edit happens.
    val all = remember(resumeRev, localRev) { Apps.loadAll(ctx) }
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (!BlockerService.lockScreen()) {
                            Toast.makeText(ctx, "Enable the Away accessibility service to lock", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onLongPress = { openAway(0) },
                )
            }
            .pointerInput(Unit) {
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = { dx = 0f; dy = 0f },
                    onDragEnd = {
                        when {
                            -dy > 160 && -dy > Math.abs(dx) -> drawer = true
                            dx < -160 && -dx > Math.abs(dy) ->
                                swipeApp(Prefs.swipeLeftApp, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                            dx > 160 && dx > Math.abs(dy) ->
                                swipeApp(Prefs.swipeRightApp, Intent(Intent.ACTION_DIAL))
                        }
                    },
                ) { _, d -> dx += d.x; dy += d.y }
            }
            .systemBarsPadding(),
    ) {
        val pinned = Prefs.pinned.mapNotNull { byPkg[it] }.let { if (focus) it.take(FOCUS_LIMIT) else it }
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
            Text(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)),
                fontSize = 64.sp, fontWeight = FontWeight.Light,
            )
            Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now)), color = Dim, fontSize = 16.sp)
            Text(
                "away " + Blocking.formatDuration(Blocking.awayMillis(now)),
                Modifier.padding(top = 20.dp), fontSize = 30.sp,
            )
            val digest = remember(Prefs.digestVersion.intValue, now / 5000) { Prefs.digest() }
            if (digest.isNotEmpty()) {
                val urgent = digest.count { it.urgent }
                Text(
                    (if (urgent > 0) "$urgent urgent · " else "") + "${digest.size} held",
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
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
            ) {
                if (pinned.isEmpty()) {
                    Text("Swipe up for apps.\nLong-press an app there to pin it.", color = Dim, fontSize = 16.sp)
                }
                pinned.forEach { app ->
                    val d = Prefs.delays[app.pkg] ?: 0
                    Text(
                        Apps.displayName(app, renames) + if (d > 0) " ·${d}s" else "",
                        Modifier.fillMaxWidth().combinedClickable(
                            onClick = { go(app) },
                            onLongClick = { menuApp = app },
                        ).padding(vertical = 10.dp),
                        fontSize = 28.sp,
                    )
                }
            }
        }

        if (drawer) {
            Drawer(
                apps = visible,
                renames = renames,
                onLaunch = { drawer = false; go(it) },
                onLong = { menuApp = it },
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
            Column(
                Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Opening ${Apps.displayName(app, renames)}", fontSize = 22.sp)
                Text("$secs", fontSize = 72.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(vertical = 16.dp))
                TextButton(onClick = { pending = null }) { Text("Never mind", color = Color.White, fontSize = 18.sp) }
            }
        }
    }

    menuApp?.let { app ->
        val isPinned = app.pkg in Prefs.pinned
        val delaySecs = Prefs.delays[app.pkg] ?: 0
        AlertDialog(
            onDismissRequest = { menuApp = null },
            confirmButton = {},
            title = { Text(Apps.displayName(app, renames), color = Color.Black) },
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
                    MenuItem("Hide (restore in Away settings)") {
                        Prefs.hidden = Prefs.hidden + app.pkg
                        Prefs.pinned = Prefs.pinned - app.pkg
                        menuApp = null; localRev++
                    }
                }
            },
        )
    }

    renaming?.let { app ->
        var name by remember(app.pkg) { mutableStateOf(Apps.displayName(app, renames)) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename", color = Color.Black) },
            text = {
                BasicTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    textStyle = TextStyle(color = Color.Black, fontSize = 20.sp),
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
                }) { Text("Save", color = Color.Black) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = Color.Black) } },
        )
    }
}

private const val FOCUS_LIMIT = 4

@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    fontWeight: FontWeight? = null,
) = androidx.compose.material3.Text(text, modifier, color = color, fontSize = fontSize, fontWeight = fontWeight)

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    androidx.compose.material3.Text(
        label,
        Modifier.fillMaxWidth().combinedClickableCompat(onClick).padding(vertical = 12.dp),
        color = Color.Black, fontSize = 17.sp,
    )
}

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
    val kb = LocalSoftwareKeyboardController.current

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { focusReq.requestFocus(); kb?.show() }
    // A lone match opens after a short pause; typing another character cancels it.
    LaunchedEffect(query, matches) {
        if (query.trim().length >= 2 && matches.size == 1) {
            delay(400)
            onLaunch(matches[0])
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(horizontal = 28.dp, vertical = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            if (query.isEmpty()) Text("search apps", color = Dim, fontSize = 28.sp)
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 28.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { matches.firstOrNull()?.let(onLaunch) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusReq),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(matches, key = { it.pkg }) { app ->
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().combinedClickableCompat2(
                        onClick = { onLaunch(app) }, onLong = { onLong(app) },
                    ).padding(vertical = 10.dp),
                ) { Text(Apps.displayName(app, renames), fontSize = 24.sp) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat2(onClick: () -> Unit, onLong: () -> Unit) =
    this.combinedClickable(onClick = onClick, onLongClick = onLong)
