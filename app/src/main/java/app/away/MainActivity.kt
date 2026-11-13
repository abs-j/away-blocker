package app.away

import android.Manifest
import android.app.TimePickerDialog
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val startTab = intent.getIntExtra("tab", 0)
        setContent { AwayTheme { App(startTab) } }
    }
}

// ---------------------------------------------------------------------------------------------
// Theme: always dark to match the launcher, coloured from the wallpaper on Android 12+.
// ---------------------------------------------------------------------------------------------

@Composable
fun AwayTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        dynamicDarkColorScheme(ctx)
    } else {
        darkColorScheme(
            primary = Color(0xFFB9C3FF), onPrimary = Color(0xFF1B2A6B),
            primaryContainer = Color(0xFF333F80), onPrimaryContainer = Color(0xFFDDE1FF),
            background = Color(0xFF111318), surface = Color(0xFF111318),
            surfaceContainer = Color(0xFF1D1F25), surfaceContainerHigh = Color(0xFF272A30),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private data class Tab(val label: String, val on: ImageVector, val off: ImageVector)

private val TABS = listOf(
    Tab("Focus", Icons.Filled.Lock, Icons.Outlined.Lock),
    Tab("Apps", Icons.Filled.List, Icons.Outlined.List),
    Tab("Digest", Icons.Filled.Notifications, Icons.Outlined.Notifications),
    Tab("AI", Icons.Filled.Star, Icons.Outlined.Star),
    Tab("Launcher", Icons.Filled.Home, Icons.Outlined.Home),
)

/** Five screens you can swipe between; the bottom bar follows the swipe and jumps on tap. */
@Composable
fun App(startTab: Int = 0) {
    val pager = rememberPagerState(initialPage = startTab.coerceIn(0, TABS.lastIndex)) { TABS.size }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(pager.settledPage) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                TABS.forEachIndexed { i, t ->
                    val selected = pager.targetPage == i
                    NavigationBarItem(
                        selected = selected,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        icon = { Icon(if (selected) t.on else t.off, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().padding(pad),
            beyondViewportPageCount = 1,
        ) { page ->
            when (page) {
                0 -> HomeScreen()
                1 -> AppsScreen()
                2 -> DigestScreen()
                3 -> AiScreen()
                else -> LauncherSettingsScreen()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------------------------

private val PagePad = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)

@Composable
private fun PageHeader(title: String, subtitle: String? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

/** Rounded surface that groups related rows. */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Column(Modifier.padding(vertical = 8.dp), content = content) }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.2.sp,
    )
}

/** A row with a title, optional description and a trailing switch. The whole row toggles. */
@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A row with a title, optional value underneath and trailing actions. */
@Composable
private fun SettingRow(title: String, value: String? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        actions()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    Text(
        "+  $label",
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh)

/** Small rounded label, used for status and "urgent". */
@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Text(
        text,
        Modifier.clip(CircleShape).background(container).padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = content,
    )
}

private fun a11yEnabled(ctx: Context): Boolean {
    val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(ctx, BlockerService::class.java).flattenToString()
    return s.split(':').any { it.equals(me, ignoreCase = true) }
}

private fun listenerEnabled(ctx: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

private fun fmtTime(min: Int) = "%02d:%02d".format(min / 60, min % 60)

private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

// ---------------------------------------------------------------------------------------------
// Focus
// ---------------------------------------------------------------------------------------------

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var enabled by remember { mutableStateOf(Prefs.enabled) }
    var sched by remember { mutableStateOf(Prefs.scheduleEnabled) }
    var start by remember { mutableIntStateOf(Prefs.startMin) }
    var end by remember { mutableIntStateOf(Prefs.endMin) }
    var blocksApps by remember { mutableStateOf(Prefs.focusBlocksApps) }
    var holdsNotifs by remember { mutableStateOf(Prefs.focusHoldsNotifs) }
    var countdown by remember { mutableIntStateOf(0) }
    var a11y by remember { mutableStateOf(a11yEnabled(ctx)) }
    var listener by remember { mutableStateOf(listenerEnabled(ctx)) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            a11y = a11yEnabled(ctx)
            listener = listenerEnabled(ctx)
            delay(1000)
        }
    }
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
            if (countdown == 0) Prefs.unlockUntil = System.currentTimeMillis() + 10 * 60_000L
        }
    }

    val focusOn = Blocking.isFocusOn(now)
    val paused = Blocking.isPaused(now)
    val status = when {
        !enabled -> "Off"
        !focusOn -> "Waiting for schedule"
        !blocksApps && !holdsNotifs -> "On, nothing selected"
        else -> listOfNotNull(
            "Apps hidden".takeIf { blocksApps && !paused },
            "Apps unlocked until ${clock(Prefs.unlockUntil)}".takeIf { blocksApps && paused },
            "Notifications held".takeIf { holdsNotifs },
        ).joinToString(" · ")
    }
    val active = enabled && focusOn

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePad),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        // Hero: the distraction-free timer.
        val heroTop by animateColorAsState(if (active) cs.primaryContainer else cs.surfaceContainerHigh, label = "hero")
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(heroTop, cs.surfaceContainer)))
                .padding(24.dp),
        ) {
            Column {
                Text("Since a distracting app", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Text(
                    Blocking.formatDuration(Blocking.awayMillis(now)),
                    fontSize = 64.sp, fontWeight = FontWeight.Light, color = cs.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Pill(
                    if (active) "Focus on" else "Focus off",
                    container = if (active) cs.primary else cs.surfaceContainerHigh,
                    content = if (active) cs.onPrimary else cs.onSurfaceVariant,
                )
            }
        }

        // Focus settings.
        Panel {
            Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = {
                enabled = it; Prefs.enabled = it; TriageListener.syncNow()
            }).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Focus", style = MaterialTheme.typography.titleLarge)
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = if (active) cs.primary else cs.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = null)
            }
            Divider()
            ToggleRow("Hide distracting apps", "Blocked, and hidden from the launcher", blocksApps) {
                blocksApps = it; Prefs.focusBlocksApps = it
            }
            ToggleRow("Hold notifications", "Urgent ones still come through", holdsNotifs) {
                holdsNotifs = it; Prefs.focusHoldsNotifs = it; TriageListener.syncNow()
            }
            Divider()
            ToggleRow("Daily schedule", if (sched) "Focus runs ${fmtTime(start)} to ${fmtTime(end)}" else "Focus runs whenever it is on", sched) {
                sched = it; Prefs.scheduleEnabled = it; TriageListener.syncNow()
            }
            AnimatedVisibility(sched, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        TimePickerDialog(ctx, { _, h, m -> start = h * 60 + m; Prefs.startMin = start; TriageListener.syncNow() }, start / 60, start % 60, true).show()
                    }) { Text("From ${fmtTime(start)}") }
                    FilledTonalButton(onClick = {
                        TimePickerDialog(ctx, { _, h, m -> end = h * 60 + m; Prefs.endMin = end; TriageListener.syncNow() }, end / 60, end % 60, true).show()
                    }) { Text("To ${fmtTime(end)}") }
                }
            }
        }

        // Unlock, only while apps are actually being hidden.
        AnimatedVisibility(Blocking.isActive(now) && (!paused || countdown > 0), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Panel {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Need a distracting app?", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Wait 30 seconds and apps unlock for 10 minutes. Notifications stay held.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    )
                    if (countdown > 0) {
                        LinearProgressIndicator(progress = { 1f - countdown / 30f }, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Unlocking in ${countdown}s", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { countdown = 0 }) { Text("Cancel") }
                        }
                    } else {
                        FilledTonalButton(onClick = { countdown = 30 }) { Text("Unlock apps for 10 min") }
                    }
                }
            }
        }

        // Setup: only shown when something is missing.
        AnimatedVisibility(!a11y || !listener) {
            Card(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Warning, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Finish setup", style = MaterialTheme.typography.titleMedium)
                    }
                    if (!a11y) PermRow("Accessibility", "Blocking and the lock screen timer") {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    if (!listener) PermRow("Notification access", "Holding and triage") {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    Text(
                        "Toggles greyed out? Open App info, tap the three-dot menu, choose Allow restricted settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Troubleshooting links, kept quiet at the bottom.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null)))
            }) { Text("App info") }
            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                Text("Battery settings")
            }
        }
    }
}

@Composable
private fun PermRow(title: String, why: String, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(why, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onOpen) { Text("Enable") }
    }
}

// ---------------------------------------------------------------------------------------------
// Distracting apps
// ---------------------------------------------------------------------------------------------

@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val apps = remember { Apps.loadAll(ctx).sortedBy { it.label.lowercase() } }
    var blocked by remember { mutableStateOf(Prefs.blocked) }
    // Order is fixed when the screen opens so rows do not jump while you tick them.
    val initial = remember { Prefs.blocked }
    var q by remember { mutableStateOf("") }
    val shown = remember(q, apps) { Apps.search(apps, emptyMap(), q) }
    val chosen = shown.filter { it.pkg in initial }
    val rest = shown.filter { it.pkg !in initial }

    fun LazyListScope.appRows(list: List<AppEntry>) {
        items(list, key = { it.pkg }) { app ->
            val on = app.pkg in blocked
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(on, role = Role.Checkbox) {
                        blocked = if (on) blocked - app.pkg else blocked + app.pkg
                        Prefs.blocked = blocked
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Checkbox(checked = on, onCheckedChange = null)
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePad) {
        item {
            val count = apps.count { it.pkg in blocked }
            PageHeader("Distracting apps", "$count selected · hidden and blocked while Focus is on")
        }
        item {
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("Search apps") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        if (chosen.isNotEmpty()) {
            item { SectionLabel("Selected") }
            appRows(chosen)
        }
        if (rest.isNotEmpty()) {
            item { SectionLabel("All apps") }
            appRows(rest)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Digest
// ---------------------------------------------------------------------------------------------

@Composable
fun DigestScreen() {
    val cs = MaterialTheme.colorScheme
    val version = Prefs.digestVersion.intValue
    val items = remember(version) { Prefs.digest() }
    val urgent = items.count { it.urgent }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            PageHeader(
                "Digest",
                if (items.isEmpty()) "Held notifications appear here" else "${items.size} held · $urgent urgent",
            ) {
                if (items.isNotEmpty()) TextButton(onClick = { Prefs.clearDigest() }) { Text("Clear") }
            }
        }
        if (items.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Notifications, null, Modifier.size(48.dp), tint = cs.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("Nothing held", style = MaterialTheme.typography.titleMedium)
                    Text("Turn on Focus with Hold notifications.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }
        items(items) { d ->
            Panel {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${d.app} · ${clock(d.time)}", Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                        )
                        if (d.urgent) Pill("Urgent", cs.primary, cs.onPrimary)
                    }
                    if (d.title.isNotBlank()) Text(d.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (d.text.isNotBlank()) Text(d.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (d.reason.isNotBlank()) Text(d.reason, style = MaterialTheme.typography.bodySmall, color = cs.primary)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// AI triage
// ---------------------------------------------------------------------------------------------

@Composable
fun AiScreen() {
    val cs = MaterialTheme.colorScheme
    var on by remember { mutableStateOf(Prefs.triageEnabled) }
    var key by remember { mutableStateOf(Prefs.apiKey) }
    var model by remember { mutableStateOf(Prefs.model) }
    var vips by remember { mutableStateOf(Prefs.vips) }
    var priv by remember { mutableStateOf(Prefs.privateApps) }
    val openRouter = key.startsWith("sk-or-")
    val defaultModel = if (openRouter) "openai/gpt-4o-mini" else "claude-haiku-5-5"
    val provider = when {
        key.isBlank() -> "Add an API key below"
        openRouter -> "OpenRouter · ${model.ifBlank { defaultModel }}"
        else -> "Anthropic · ${model.ifBlank { defaultModel }}"
    }
    val calls = Prefs.callsToday()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePad)) {
        PageHeader("AI triage", "Decides which held notifications are urgent")
        Panel {
            ToggleRow("Classify with AI", provider, on && key.isNotBlank(), enabled = key.isNotBlank()) {
                on = it; Prefs.triageEnabled = it
            }
            Divider()
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row {
                    Text("Calls today", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("$calls / ${Prefs.DAILY_CAP}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { calls / Prefs.DAILY_CAP.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            if (Prefs.lastError.isNotBlank()) {
                Text(
                    "Last error: ${Prefs.lastError}",
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = cs.error,
                )
            }
        }

        SectionLabel("Connection")
        Panel {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = key, onValueChange = { key = it; Prefs.apiKey = it },
                    label = { Text("API key") }, supportingText = { Text("Anthropic (sk-ant-…) or OpenRouter (sk-or-…)") },
                    singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it; Prefs.model = it },
                    label = { Text("Model") }, placeholder = { Text(defaultModel) },
                    singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionLabel("Rules")
        Panel {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = vips, onValueChange = { vips = it; Prefs.vips = it },
                    label = { Text("VIP names") }, supportingText = { Text("Comma separated. Messages from them count as urgent.") },
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = priv, onValueChange = { priv = it; Prefs.privateApps = it },
                    label = { Text("Private apps") }, supportingText = { Text("Package names, comma separated. Never sent to the AI.") },
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Text(
            "Notification text is sent to your provider with numbers, emails and links removed. Verification codes and VIPs are detected on the phone.",
            Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Launcher
// ---------------------------------------------------------------------------------------------

private enum class PickFor(val title: String) {
    PIN("Add to home screen"), HIDE("Hide an app"), DELAY("Add a launch delay"),
    LEFT("Swipe left opens"), RIGHT("Swipe right opens"),
}

private fun isDefaultHome(ctx: Context): Boolean =
    runCatching { ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) }
        .getOrDefault(false)

/**
 * Launcher settings: one place to see and change everything the home screen does, instead of
 * hunting through long-press menus. Each group lists only what is set, plus an add row that opens
 * a searchable app picker.
 */
@Composable
fun LauncherSettingsScreen() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val apps = remember { Apps.loadAll(ctx).sortedBy { it.label.lowercase() } }
    val byPkg = remember(apps) { apps.associateBy { it.pkg } }
    var rev by remember { mutableIntStateOf(0) }
    var isHome by remember { mutableStateOf(isDefaultHome(ctx)) }
    var picker by remember { mutableStateOf<PickFor?>(null) }
    LaunchedEffect(Unit) {
        while (true) { isHome = isDefaultHome(ctx); delay(1500) }
    }

    // Snapshots of the launcher prefs, refreshed after every edit. Uninstalled apps are skipped.
    val pinned = remember(rev) { Prefs.pinned.filter { it in byPkg } }
    val hidden = remember(rev) { Prefs.hidden.filter { it in byPkg }.sortedBy { byPkg[it]!!.label.lowercase() } }
    val renames = remember(rev) { Prefs.renames.filterKeys { it in byPkg } }
    val delays = remember(rev) { Prefs.delays.filterKeys { it in byPkg }.toList().sortedBy { byPkg[it.first]!!.label.lowercase() } }
    fun name(pkg: String) = renames[pkg] ?: byPkg[pkg]?.label ?: pkg
    fun edit(block: () -> Unit) { block(); rev++ }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePad) {
        item { PageHeader("Launcher", "Your home screen, gestures and app rules") }
        item {
            Panel {
                SettingRow(
                    if (isHome) "Away is your home screen" else "Away is not your home screen",
                    if (isHome) "Press Home to see it" else "Set it as the default home app",
                ) {
                    if (isHome) TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }) { Text("Change") }
                    else Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }) { Text("Set") }
                }
            }
        }

        item { SectionLabel("Home screen apps") }
        item {
            Panel {
                if (pinned.isEmpty()) Hint("No apps pinned yet.")
                pinned.forEachIndexed { i, pkg ->
                    SettingRow(name(pkg), if (i == 3 && pinned.size > 4) "Last one shown while Focus hides apps" else null) {
                        IconButton(enabled = i > 0, onClick = {
                            edit { Prefs.pinned = pinned.toMutableList().apply { add(i - 1, removeAt(i)) } }
                        }) { Icon(Icons.Outlined.KeyboardArrowUp, "Move up") }
                        IconButton(enabled = i < pinned.lastIndex, onClick = {
                            edit { Prefs.pinned = pinned.toMutableList().apply { add(i + 1, removeAt(i)) } }
                        }) { Icon(Icons.Outlined.KeyboardArrowDown, "Move down") }
                        IconButton(onClick = { edit { Prefs.pinned = pinned - pkg } }) { Icon(Icons.Outlined.Close, "Remove") }
                    }
                }
                AddRow("Add app") { picker = PickFor.PIN }
            }
        }
        item {
            Text(
                "While Focus hides apps, only the first 4 show.",
                Modifier.padding(start = 4.dp, top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
        }

        item { SectionLabel("Gestures") }
        item {
            Panel {
                SettingRow("Swipe left", Prefs.swipeLeftApp.takeIf { it in byPkg }?.let(::name) ?: "Camera") {
                    if (Prefs.swipeLeftApp.isNotBlank()) TextButton(onClick = { edit { Prefs.swipeLeftApp = "" } }) { Text("Reset") }
                    TextButton(onClick = { picker = PickFor.LEFT }) { Text("Change") }
                }
                SettingRow("Swipe right", Prefs.swipeRightApp.takeIf { it in byPkg }?.let(::name) ?: "Phone") {
                    if (Prefs.swipeRightApp.isNotBlank()) TextButton(onClick = { edit { Prefs.swipeRightApp = "" } }) { Text("Reset") }
                    TextButton(onClick = { picker = PickFor.RIGHT }) { Text("Change") }
                }
                Divider()
                SettingRow("Swipe up", "Search apps")
                SettingRow("Swipe down in search", "Back to home")
                SettingRow("Double tap", "Lock screen")
                SettingRow("Long-press home", "These settings")
            }
        }

        item { SectionLabel("Launch delays") }
        item {
            Panel {
                if (delays.isEmpty()) Hint("A countdown before an app opens. None set.")
                delays.forEach { (pkg, secs) ->
                    SettingRow(name(pkg)) {
                        TextButton(onClick = {
                            val next = when (secs) { 5 -> 10; 10 -> 15; 15 -> 30; else -> 5 }
                            edit { Prefs.delays = Prefs.delays + (pkg to next) }
                        }) { Text("${secs}s") }
                        IconButton(onClick = { edit { Prefs.delays = Prefs.delays - pkg } }) { Icon(Icons.Outlined.Close, "Remove") }
                    }
                }
                AddRow("Add delay") { picker = PickFor.DELAY }
            }
        }

        if (renames.isNotEmpty()) {
            item { SectionLabel("Renamed apps") }
            item {
                Panel {
                    renames.toList().forEach { (pkg, label) ->
                        SettingRow(label, byPkg[pkg]?.label) {
                            TextButton(onClick = { edit { Prefs.renames = Prefs.renames - pkg } }) { Text("Reset") }
                        }
                    }
                }
            }
        }

        item { SectionLabel("Hidden apps") }
        item {
            Panel {
                if (hidden.isEmpty()) Hint("Hidden apps never appear in search. None hidden.")
                hidden.forEach { pkg ->
                    SettingRow(byPkg[pkg]!!.label) {
                        TextButton(onClick = { edit { Prefs.hidden = Prefs.hidden - pkg } }) { Text("Unhide") }
                    }
                }
                AddRow("Hide an app") { picker = PickFor.HIDE }
            }
        }
    }

    picker?.let { mode ->
        val exclude = when (mode) {
            PickFor.PIN -> pinned.toSet() + hidden
            PickFor.HIDE -> hidden.toSet()
            PickFor.DELAY -> delays.map { it.first }.toSet()
            PickFor.LEFT, PickFor.RIGHT -> emptySet()
        }
        AppPickerDialog(mode.title, apps.filter { it.pkg !in exclude }, onDismiss = { picker = null }) { pkg ->
            edit {
                when (mode) {
                    PickFor.PIN -> Prefs.pinned = pinned + pkg
                    PickFor.HIDE -> { Prefs.hidden = Prefs.hidden + pkg; Prefs.pinned = Prefs.pinned - pkg }
                    PickFor.DELAY -> Prefs.delays = Prefs.delays + (pkg to 10)
                    PickFor.LEFT -> Prefs.swipeLeftApp = pkg
                    PickFor.RIGHT -> Prefs.swipeRightApp = pkg
                }
            }
            picker = null
        }
    }
}

@Composable
private fun AppPickerDialog(
    title: String,
    apps: List<AppEntry>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val matches = remember(q, apps) { Apps.search(apps, emptyMap(), q) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text("Search apps") }, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(matches, key = { it.pkg }) { app ->
                        Text(
                            app.label,
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(app.pkg) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
