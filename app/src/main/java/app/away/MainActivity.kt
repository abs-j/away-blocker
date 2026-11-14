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
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DateRange
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // People who set Away up before onboarding existed skip it.
        if (!Prefs.onboardedKnown && a11yEnabled(this)) Prefs.onboarded = true
        if (Prefs.onboarded && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val startTab = intent.getIntExtra("tab", 0)
        setContent {
            AwayTheme {
                var onboarded by remember { mutableStateOf(Prefs.onboarded) }
                if (onboarded) App(startTab, onRunSetup = { Prefs.onboarded = false; onboarded = false })
                else Onboarding(onDone = { Prefs.onboarded = true; onboarded = true })
            }
        }
    }
}

private data class Tab(val label: String, val on: ImageVector, val off: ImageVector)

private val TABS = listOf(
    Tab("Focus", Icons.Filled.Lock, Icons.Outlined.Lock),
    Tab("Usage", Icons.Filled.DateRange, Icons.Outlined.DateRange),
    Tab("Digest", Icons.Filled.Notifications, Icons.Outlined.Notifications),
    Tab("Triage", Icons.Filled.Star, Icons.Outlined.Star),
    Tab("Launcher", Icons.Filled.Home, Icons.Outlined.Home),
)

/** Five screens you can swipe between; the glass bar follows the swipe and jumps on tap. */
@Composable
fun App(startTab: Int = 0, onRunSetup: () -> Unit = {}) {
    val pager = rememberPagerState(initialPage = startTab.coerceIn(0, TABS.lastIndex)) { TABS.size }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(pager.settledPage) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val navItems = remember { TABS.map { NavItem(it.label, it.on, it.off) } }

    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { GlassNav(pager, navItems) { scope.launch { pager.animateScrollToPage(it, animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 260f)) } } },
        ) { pad ->
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().padding(pad),
                beyondViewportPageCount = 1,
            ) { page ->
                val offset = { (pager.currentPage - page) + pager.currentPageOffsetFraction }
                androidx.compose.runtime.CompositionLocalProvider(LocalPageOffset provides offset) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            // Pages recede and fade as they leave, and rise into focus as they arrive.
                            val o = kotlin.math.abs(offset()).coerceIn(0f, 1f)
                            alpha = 1f - 0.65f * o
                            scaleX = 1f - 0.06f * o
                            scaleY = 1f - 0.06f * o
                        },
                    ) {
                        when (page) {
                            0 -> HomeScreen(onRunSetup)
                            1 -> ScreenTimeScreen()
                            2 -> DigestScreen()
                            3 -> AiScreen()
                            else -> LauncherSettingsScreen()
                        }
                    }
                }
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
    Column(
        modifier.fillMaxWidth().animateContentSize(androidx.compose.animation.core.spring(stiffness = 400f)).glass(RoundedCornerShape(28.dp))
            .padding(vertical = 8.dp),
        content = content,
    )
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
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().pressScale(src, 0.985f)
            .toggleable(
                value = checked, enabled = enabled, role = Role.Switch, interactionSource = src, indication = null,
                onValueChange = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onChange(it) },
            )
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
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = awaySwitchColors())
    }
}

@Composable
private fun awaySwitchColors() = androidx.compose.material3.SwitchDefaults.colors(
    checkedTrackColor = Glow.Indigo, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
    uncheckedTrackColor = Color.White.copy(alpha = 0.08f), uncheckedThumbColor = Glow.InkDim,
    uncheckedBorderColor = Color.White.copy(alpha = 0.18f),
)

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

fun a11yEnabled(ctx: Context): Boolean {
    val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(ctx, BlockerService::class.java).flattenToString()
    return s.split(':').any { it.equals(me, ignoreCase = true) }
}

fun listenerEnabled(ctx: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

fun appLabel(ctx: Context, pkg: String): String =
    runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }
        .getOrDefault(pkg)

private fun fmtTime(min: Int) = "%02d:%02d".format(min / 60, min % 60)

private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

// ---------------------------------------------------------------------------------------------
// Focus
// ---------------------------------------------------------------------------------------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onRunSetup: () -> Unit = {}) {
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
    var a11yRunning by remember { mutableStateOf(BlockerService.instance != null) }
    var listener by remember { mutableStateOf(listenerEnabled(ctx)) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            a11y = a11yEnabled(ctx)
            a11yRunning = BlockerService.instance != null
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
        // Hero: the distraction-free timer, as a glowing ring that fills toward the next milestone.
        val away = Blocking.awayMillis(now)
        val milestones = remember { listOf(15L, 60L, 180L, 480L, 1440L, 4320L).map { it * 60_000L } }
        val prevMark = milestones.lastOrNull { it <= away } ?: 0L
        val nextMark = milestones.firstOrNull { it > away } ?: (milestones.last() * 2)
        val shown = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(away / 60_000L) {
            shown.animateTo((away / 60_000L).toFloat(), androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
        Box(
            Modifier.fillMaxWidth().staggerIn(0).cascade(0)
                .glass(RoundedCornerShape(34.dp), 0.05f)
                .background(Brush.verticalGradient(listOf(Glow.Indigo.copy(alpha = if (active) 0.34f else 0.16f), Glow.Violet.copy(alpha = 0.08f), Color.Transparent)))
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SINCE A DISTRACTING APP", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, letterSpacing = 1.6.sp)
                StreakRing(((away - prevMark).toFloat() / (nextMark - prevMark)).coerceIn(0f, 1f), Modifier.fillMaxWidth(0.8f).padding(vertical = 4.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            Blocking.formatDuration((shown.value * 60_000L).toLong()),
                            style = MaterialTheme.typography.displayLarge.copy(fontSize = 44.sp),
                            maxLines = 1, softWrap = false, color = cs.onSurface,
                        )
                        Text("next: ${fmtUse(nextMark)}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
                Text(
                    Motivation.line(away, Stats.best, maxOf(Prefs.lastUse, Prefs.installTime)),
                    style = MaterialTheme.typography.titleMedium, color = cs.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                val dot by androidx.compose.animation.core.rememberInfiniteTransition(label = "dot").animateFloat(
                    0.35f, 1f,
                    androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1100), androidx.compose.animation.core.RepeatMode.Reverse),
                    label = "dot",
                )
                Row(
                    Modifier.glass(CircleShape, if (active) 0.12f else 0.06f).padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).graphicsLayer { alpha = if (active) dot else 0.5f }.clip(CircleShape).background(if (active) Glow.Mint else cs.onSurfaceVariant))
                    Text(
                        when {
                            active -> "Focus on"
                            enabled && sched -> "Focus starts ${fmtTime(start)}"
                            else -> "Focus off"
                        },
                        style = MaterialTheme.typography.labelLarge, color = if (active) cs.onSurface else cs.onSurfaceVariant,
                    )
                }
            }
        }

        // Focus settings.
        Panel(Modifier.staggerIn(1).cascade(1)) {
            Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = {
                enabled = it; Prefs.enabled = it; TriageListener.syncNow()
            }).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Focus", style = MaterialTheme.typography.titleLarge)
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = if (active) cs.primary else cs.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = null, colors = awaySwitchColors())
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

        Panel(Modifier.staggerIn(2).cascade(2)) {
            var after by remember { mutableIntStateOf(Prefs.indicatorAfterMin) }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lock screen timer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (after == 0) "Shown whenever the phone is idle" else "Appears after ${fmtUse(after * 60_000L)} untouched",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { after = (after - 15).coerceAtLeast(0); Prefs.indicatorAfterMin = after }) { Text("-15m") }
                TextButton(onClick = { after = (after + 15).coerceAtMost(24 * 60); Prefs.indicatorAfterMin = after }) { Text("+15m") }
            }
        }

        Panel(Modifier.staggerIn(3).cascade(3)) {
            var mode by remember { mutableIntStateOf(Prefs.unlockPause) }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Unlock pause", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (mode) {
                        0 -> "Off"
                        1 -> "A 5 second pause after every unlock, to think before you go in"
                        2 -> "A 5 second pause after unlocking during Focus hours"
                        else -> "A 5 second pause when you have gone an hour or more without a distracting app"
                    },
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Off", "Every unlock", "Focus hours", "Streak 1h+").forEachIndexed { i, label ->
                        FilterChip(
                            selected = mode == i, onClick = { mode = i; Prefs.unlockPause = i },
                            label = { Text(label, maxLines = 1, softWrap = false) },
                        )
                    }
                }
            }
        }

        WeekStats(now)

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
        AnimatedVisibility(!a11y || !a11yRunning || !listener) {
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
                    if (!a11y) PermRow("Accessibility", "Blocking, the home timer and the lock screen timer") {
                        safeStart(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } else if (!a11yRunning) PermRow(
                        "Accessibility is on but not running",
                        "Android did not restart Away after an update. Turn Away off and on again, or restart the phone.",
                        action = "Open",
                    ) {
                        safeStart(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    if (!listener) PermRow("Notification access", "Holding and triage") {
                        safeStart(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    Text(
                        "Toggles greyed out? Open App info, tap the three-dot menu, choose Allow restricted settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Troubleshooting links, kept quiet at the bottom.
        var about by remember { mutableStateOf(false) }
        if (about) AboutDialog(onDismiss = { about = false }, onRunSetup = { about = false; onRunSetup() })
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { about = true }) { Text("About") }
            TextButton(onClick = {
                safeStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null)))
            }) { Text("App info") }
            TextButton(onClick = { safeStart(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                Text("Battery settings")
            }
        }
    }
}

/** This week at a glance: a bar per day of distracting-app opens, then the key numbers. */
@Composable
private fun WeekStats(now: Long) {
    val cs = MaterialTheme.colorScheme
    val v = Stats.version.intValue
    val days = remember(v, now / 60_000) { Stats.lastDays(14, now) }
    val week = days.takeLast(7)
    val prev = days.take(7)
    val opens = week.sumOf { it.opens }
    val prevOpens = prev.sumOf { it.opens }
    val blocked = week.sumOf { it.blocked }
    val pickups = week.sumOf { it.pickups }
    val gapSum = week.sumOf { it.gapSum }
    val longest = maxOf(week.maxOf { it.longest }, Blocking.awayMillis(now))
    val maxBar = maxOf(1, week.maxOf { it.opens })
    val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
    // Average over days that have any data, so the first week is not diluted by empty days.
    val tracked = week.count { it.opens > 0 || it.blocked > 0 || it.pickups > 0 }.coerceAtLeast(1)

    Panel {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("This week", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (prevOpens > 0) {
                val change = (opens - prevOpens) * 100 / prevOpens
                Pill(
                    if (change <= 0) "↓ ${-change}% opens" else "↑ $change% opens",
                    container = if (change <= 0) cs.primary else cs.surfaceContainerHigh,
                    content = if (change <= 0) cs.onPrimary else cs.onSurfaceVariant,
                )
            }
        }
        // Distracting-app opens per day, today highlighted.
        Row(
            Modifier.fillMaxWidth().height(110.dp).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            week.forEachIndexed { i, d ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (d.opens > 0) "${d.opens}" else "", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Box(
                        Modifier.fillMaxWidth().height((6 + 64 * d.opens / maxBar).dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (i == week.lastIndex) cs.primary else cs.primary.copy(alpha = 0.35f)),
                    )
                    Text(dayFmt.format(Date(d.start)).take(1), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
        Text(
            "Distracting-app opens per day",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Divider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            StatCell("Opens", "$opens", Modifier.weight(1f))
            StatCell("Blocked", "$blocked", Modifier.weight(1f))
            StatCell("Longest away", Blocking.formatDuration(longest), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            StatCell("Pickups a day", "${pickups / tracked}", Modifier.weight(1f))
            StatCell("Between uses", if (pickups > 0) Blocking.formatDuration(gapSum / pickups) else "–", Modifier.weight(1f))
            StatCell("Best ever", if (Stats.best > 0) Blocking.formatDuration(maxOf(Stats.best, Blocking.awayMillis(now))) else "–", Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(12.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit, onRunSetup: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppMark(56) },
        title = { Text("Away $version") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Spend less time on your phone, without missing what matters.")
                Text(
                    "Everything stays on your phone. Notification text is only sent out if you add your own AI key, " +
                        "and then only to that provider, with numbers, emails and links removed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            Row {
                TextButton(onClick = { safeStart(ctx, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(PROJECT_URL))) }) { Text("Website") }
                TextButton(onClick = onRunSetup) { Text("Run setup again") }
            }
        },
    )
}

const val PROJECT_URL = "https://github.com/abs-j/away-blocker"

@Composable
private fun PermRow(title: String, why: String, action: String = "Enable", onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(why, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onOpen) { Text(action) }
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DigestScreen() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val version = Prefs.digestVersion.intValue
    val items = remember(version) { Prefs.digest() }
    val urgent = items.count { it.urgent }
    val held = TriageListener.heldCount.intValue
    val rules = remember(version) { Prefs.passRules() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            PageHeader(
                "Digest",
                when {
                    items.isEmpty() -> "Held notifications appear here"
                    else -> "Last 24 hours · ${items.size} total · $urgent urgent"
                },
            ) {
                if (items.isNotEmpty()) TextButton(onClick = { Prefs.clearDigest() }) { Text("Clear") }
            }
        }
        if (held > 0) {
            item {
                Panel(Modifier.cascade(1)) {
                    SettingRow("$held held right now", "They return when Focus ends") {
                        FilledTonalButton(onClick = { TriageListener.releaseNow() }, modifier = Modifier.padding(end = 12.dp)) {
                            Text("Release all")
                        }
                    }
                }
            }
        }
        if (rules.isNotEmpty()) {
            item {
                Panel(Modifier.cascade(1)) {
                    Text("Not held", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                    rules.forEach { (key, until) ->
                        val pkg = key.substringBefore('|')
                        val chat = key.substringAfter('|', "").replaceFirstChar { it.uppercase() }
                        SettingRow(
                            appLabel(ctx, pkg) + if (chat.isNotBlank()) " · $chat" else "",
                            if (until == Prefs.PASS_ALWAYS) "Always comes through" else "Comes through until ${clock(until)}",
                        ) { TextButton(onClick = { Prefs.clearPass(key) }) { Text("Hold again") } }
                    }
                }
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
        } else {
            item {
                Text(
                    "Tap to open the app. Swipe to dismiss.",
                    Modifier.padding(start = 4.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
        }
        items(items, key = { "${it.time}_${it.app}_${it.title.hashCode()}" }) { d ->
            val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                if (v != SwipeToDismissBoxValue.Settled) { Prefs.removeDigest(d); true } else false
            })
            SwipeToDismissBox(
                state = state,
                modifier = Modifier.animateItem().cascade(2),
                backgroundContent = {
                    // The card is translucent glass, so the hint must only exist while a swipe is under way.
                    if (state.dismissDirection != SwipeToDismissBoxValue.Settled) {
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Glow.Rose.copy(alpha = 0.18f))
                                .padding(horizontal = 28.dp),
                            contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
                        ) { Text("Dismiss", style = MaterialTheme.typography.labelLarge, color = Glow.Rose) }
                    }
                },
            ) {
                val launch = remember(d.pkg) { d.pkg.takeIf { it.isNotBlank() }?.let { ctx.packageManager.getLaunchIntentForPackage(it) } }
                Panel(Modifier.clip(RoundedCornerShape(24.dp)).clickable(enabled = launch != null) { launch?.let { safeStart(ctx, it) } }) {
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
                        if (d.pkg.isNotBlank()) {
                            val chatKey = Prefs.passKey(d.pkg, d.title)
                            val until = rules[d.pkg] ?: rules[chatKey]
                            val canScope = d.title.isNotBlank()
                            var wholeApp by remember(d.pkg, d.title) { mutableStateOf(!canScope) }
                            val target = if (canScope && !wholeApp) "“${d.title}”" else d.app
                            if (until != null) {
                                Text(
                                    if (until == Prefs.PASS_ALWAYS) "$target is never held" else "$target is not held until ${clock(until)}",
                                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                                )
                            } else {
                                if (canScope) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = !wholeApp, onClick = { wholeApp = false }, label = { Text("This chat", maxLines = 1, softWrap = false) })
                                        FilterChip(selected = wholeApp, onClick = { wholeApp = true }, label = { Text("All of ${d.app}", maxLines = 1, softWrap = false) })
                                    }
                                }
                                Text("Don't hold $target for", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("30 min" to 30, "1 hour" to 60, "4 hours" to 240, "Always" to -1).forEach { (label, mins) ->
                                        AssistChip(
                                            onClick = {
                                                val key = if (wholeApp) d.pkg else chatKey
                                                Prefs.setPass(key, if (mins < 0) Prefs.PASS_ALWAYS else System.currentTimeMillis() + mins * 60_000L)
                                                TriageListener.releaseApp(d.pkg, if (wholeApp) null else d.title)
                                            },
                                            label = { Text(label, maxLines = 1, softWrap = false) },
                                        )
                                    }
                                }
                            }
                        }
                    }
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
        key.isBlank() -> "Optional. Add your own key below"
        openRouter -> "OpenRouter · ${model.ifBlank { defaultModel }}"
        else -> "Anthropic · ${model.ifBlank { defaultModel }}"
    }
    val calls = Prefs.callsToday()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePad)) {
        PageHeader("Triage", "Decides which held notifications are urgent enough to come through")
        Panel {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("On-device rules", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Codes, VIPs, calls to call back, emergencies, travel and security alerts. Free and private.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                Pill("Always on", cs.primary, cs.onPrimary)
            }
            Divider()
            ToggleRow("AI for everything else", provider, on && key.isNotBlank(), enabled = key.isNotBlank()) {
                on = it; Prefs.triageEnabled = it
            }
            Divider()
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row {
                    Text("Calls today", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("$calls / ${Prefs.DAILY_CAP}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                LimitBar(calls / Prefs.DAILY_CAP.toFloat())
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
            "With AI on, notification text is sent to your chosen provider with numbers, emails and links removed. Private apps and everything the rules catch never leave the phone.",
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

fun isDefaultHome(ctx: Context): Boolean =
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
                    if (isHome) TextButton(onClick = { safeStart(ctx, Intent(Settings.ACTION_HOME_SETTINGS)) }) { Text("Change") }
                    else Button(onClick = { safeStart(ctx, Intent(Settings.ACTION_HOME_SETTINGS)) }) { Text("Set") }
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

// ---------------------------------------------------------------------------------------------
// Screen time
// ---------------------------------------------------------------------------------------------

@Composable
private fun LimitBar(fraction: Float) {
    val f by androidx.compose.animation.core.animateFloatAsState(
        fraction.coerceIn(0f, 1f), androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 80f), label = "limit",
    )
    val c = when { f >= 1f -> Glow.Rose; f > 0.75f -> Glow.Amber; else -> Glow.Mint }
    Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
        Box(
            Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.55f), c))),
        )
    }
}

/** Usage for today and the week, with daily limits per app; the distracting-apps list lives behind a toggle. */
@Composable
fun ScreenTimeScreen() {
    var mode by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Usage") })
            FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Distracting apps") })
        }
        if (mode == 0) UsageContent() else AppsScreen()
    }
}

/** Compact h:mm for the narrow bar labels. */
private fun fmtHm(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "%d:%02d".format(m / 60, m % 60) else "${m}m"
}

private fun fmtUse(ms: Long): String {
    val m = ms / 60_000
    return when {
        m >= 60 && m % 60 == 0L -> "${m / 60}h"
        m >= 60 -> "${m / 60}h ${m % 60}m"
        m >= 1 -> "${m}m"
        ms > 0 -> "<1m"
        else -> "0m"
    }
}

@Composable
private fun UsageContent() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var access by remember { mutableStateOf(UsageTracker.hasAccess(ctx)) }
    var today by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var daily by remember { mutableStateOf<List<Long>>(emptyList()) }
    var limits by remember { mutableStateOf(Prefs.limits) }
    var editing by remember { mutableStateOf<String?>(null) }
    val labels = remember { Apps.loadAll(ctx).associate { it.pkg to it.label } }
    val pickups = remember(Stats.version.intValue) { Stats.lastDays(1).last().pickups }

    LaunchedEffect(Unit) {
        while (true) {
            access = UsageTracker.hasAccess(ctx)
            if (access) withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                today = UsageTracker.usage(ctx, UsageTracker.dayStart(now), now)
                daily = UsageTracker.dailyTotals(ctx, 7, now)
            }
            delay(10_000)
        }
    }

    editing?.let { pkg ->
        val existing = limits[pkg]
        var mins by remember(pkg) { mutableIntStateOf(existing ?: 30) }
        // Loosening a limit (higher, or removing it) takes a 30 second wait, like unlocking apps in Focus.
        var wait by remember(pkg) { mutableIntStateOf(if (existing != null) 30 else 0) }
        LaunchedEffect(pkg, wait) { if (wait > 0) { delay(1000); wait -= 1 } }
        val loosening = existing != null && mins > existing
        val locked = wait > 0 && loosening
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Daily limit for ${labels[pkg] ?: pkg}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(fmtUse(mins * 60_000L), style = MaterialTheme.typography.headlineMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60, 120).forEach { m ->
                            FilterChip(selected = mins == m, onClick = { mins = m }, label = { Text(fmtUse(m * 60_000L), maxLines = 1, softWrap = false) })
                        }
                    }
                    Row {
                        TextButton(onClick = { mins = (mins - 5).coerceAtLeast(5) }) { Text("-5m") }
                        TextButton(onClick = { mins = (mins + 5).coerceAtMost(12 * 60) }) { Text("+5m") }
                        if (pkg in limits) TextButton(enabled = wait == 0, onClick = {
                            limits = limits - pkg; Prefs.limits = limits; editing = null
                        }) { Text(if (wait > 0) "Remove in ${wait}s" else "Remove limit") }
                    }
                    Text(
                        if (existing != null) "Lowering a limit is instant. Raising or removing it takes 30 seconds."
                        else "The app is blocked for the rest of the day once you reach it.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = !locked, onClick = { limits = limits + (pkg to mins); Prefs.limits = limits; editing = null }) {
                    Text(if (locked) "Set in ${wait}s" else "Set")
                }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }

    val rows = today.filterKeys { it in labels }.entries.sortedByDescending { it.value }.take(25)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Screen time", "Today, per app. Tap an app to set a daily limit. Phone, Messages, Maps and Settings are never limited.") }
        if (!access) {
            item {
                Panel {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Usage access needed", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Android only shares app usage with apps you allow. It stays on this phone.",
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        )
                        Button(onClick = { safeStart(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow usage access") }
                    }
                }
            }
            return@LazyColumn
        }
        item {
            val total = today.filterKeys { it in labels }.values.sum()
            val yesterday = daily.getOrNull(daily.size - 2) ?: 0L
            val shown = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(total / 60_000L) {
                shown.animateTo((total / 60_000L).toFloat(), androidx.compose.animation.core.tween(1300, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            }
            Box(
                Modifier.fillMaxWidth().staggerIn(0).cascade(0).glass(RoundedCornerShape(32.dp), 0.05f)
                    .background(Brush.verticalGradient(listOf(Glow.Violet.copy(alpha = 0.26f), Glow.Indigo.copy(alpha = 0.08f), Color.Transparent)))
                    .padding(24.dp),
            ) {
                Column {
                    Text("TODAY", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, letterSpacing = 1.6.sp)
                    Text(fmtUse((shown.value * 60_000L).toLong()), style = MaterialTheme.typography.displayLarge, color = cs.onSurface, maxLines = 1, softWrap = false)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$pickups pickups", Modifier.glass(CircleShape, 0.08f).padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = cs.onSurface)
                        if (yesterday > 0) {
                            val lower = total <= yesterday
                            Text(
                                (if (lower) "↓ " else "↑ ") + "vs ${fmtUse(yesterday)} yesterday",
                                Modifier.glass(CircleShape, 0.08f).padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge, color = if (lower) Glow.Mint else Glow.Amber,
                            )
                        }
                    }
                }
            }
        }
        if (daily.isNotEmpty()) {
            item {
                Panel(Modifier.staggerIn(1).cascade(1)) {
                    val max = (daily.maxOrNull() ?: 0L).coerceAtLeast(1L)
                    val fmt = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
                    val now = System.currentTimeMillis()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom,
                    ) {
                        daily.forEachIndexed { i, ms ->
                            val day = fmt.format(Date(now - (daily.size - 1 - i) * 86_400_000L))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(fmtHm(ms), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
                                var grown by remember { mutableStateOf(false) }
                                LaunchedEffect(daily) { delay(120L + i * 70L); grown = true }
                                val barH by androidx.compose.animation.core.animateDpAsState(
                                    if (grown) maxOf(3L, 72L * ms / max).toInt().dp else 3.dp,
                                    androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 120f), label = "bar",
                                )
                                Box(
                                    Modifier.padding(vertical = 4.dp).width(20.dp).height(barH)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (i == daily.lastIndex) Brush.verticalGradient(listOf(Glow.Violet, Glow.Indigo))
                                            else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.07f))),
                                        ),
                                )
                                Text(day, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item { SectionLabel("Apps today") }
        if (rows.isEmpty()) {
            item { Hint("No app use recorded yet today.") }
        } else {
            item {
                Panel(Modifier.staggerIn(2).cascade(2)) {
                    rows.forEachIndexed { i, (pkg, ms) ->
                        val limit = limits[pkg]
                        Column(Modifier.fillMaxWidth().clickable(enabled = !UsageTracker.isNeverBlock(pkg)) { editing = pkg }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(labels[pkg] ?: pkg, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    fmtUse(ms) + if (limit != null) " / ${fmtUse(limit * 60_000L)}" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (limit != null && ms >= limit * 60_000L) cs.error else cs.onSurfaceVariant,
                                )
                            }
                            if (limit != null) {
                                Spacer(Modifier.height(6.dp))
                                LimitBar(ms / (limit * 60_000f))
                            }
                        }
                        if (i < rows.lastIndex) Divider()
                    }
                }
            }
        }
        val unused = limits.keys.filter { it !in today }
        if (unused.isNotEmpty()) {
            item { SectionLabel("Other limits") }
            item {
                Panel {
                    unused.forEach { pkg ->
                        SettingRow(labels[pkg] ?: pkg, "Limit ${fmtUse(limits.getValue(pkg) * 60_000L)} a day") {
                            TextButton(onClick = { editing = pkg }) { Text("Edit") }
                        }
                    }
                }
            }
        }
    }
}
