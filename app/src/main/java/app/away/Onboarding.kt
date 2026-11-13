package app.away

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val STEPS = 7

/**
 * First-run setup for people installing Away from a download. It explains each permission before
 * asking for it, walks through Android's "restricted settings" step for sideloaded apps, and shows
 * live status so you can see each step worked. Everything except Accessibility can be skipped.
 */
@Composable
fun Onboarding(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var step by remember { mutableIntStateOf(0) }
    var a11y by remember { mutableStateOf(a11yEnabled(ctx)) }
    var listener by remember { mutableStateOf(listenerEnabled(ctx)) }
    var home by remember { mutableStateOf(isDefaultHome(ctx)) }
    var battery by remember { mutableStateOf(batteryUnrestricted(ctx)) }
    var notifAllowed by remember { mutableStateOf(notificationsAllowed(ctx)) }
    var startFocus by remember { mutableStateOf(true) }
    val askNotifs = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifAllowed = it }

    // Status refreshes while you are away in Settings and come back.
    LaunchedEffect(Unit) {
        while (true) {
            a11y = a11yEnabled(ctx); listener = listenerEnabled(ctx); home = isDefaultHome(ctx)
            battery = batteryUnrestricted(ctx); notifAllowed = notificationsAllowed(ctx)
            delay(1000)
        }
    }
    BackHandler(enabled = step > 0) { step-- }

    fun next() { if (step < STEPS - 1) step++ else {
        if (startFocus) { Prefs.enabled = true; TriageListener.syncNow() }
        onDone()
    } }

    // Surface sets the default text colour; without it untinted text falls back to near-black.
    androidx.compose.material3.Surface(color = cs.surface, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
        // Progress dots.
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(STEPS) { i ->
                Box(
                    Modifier.padding(horizontal = 3.dp).height(6.dp).width(if (i == step) 22.dp else 6.dp)
                        .clip(CircleShape).background(if (i <= step) cs.primary else cs.surfaceContainerHigh),
                )
            }
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(260)) { it / 6 * dir } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(200)) { -it / 6 * dir } + fadeOut(tween(160)))
            },
            modifier = Modifier.weight(1f),
            label = "step",
        ) { s ->
            when (s) {
                0 -> Welcome()
                1 -> PickApps()
                2 -> Step(
                    "Let Away see which app opens",
                    "This is what lets Focus block apps and count your time away.",
                ) {
                    Disclosure(
                        does = listOf(
                            "Sees which app is in front",
                            "Blocks the apps you chose, only during Focus",
                            "Counts how long you stay away from them",
                            "Locks the screen when you double tap home",
                        ),
                        never = listOf(
                            "Reads your messages, typing or screen content",
                            "Sends anything off your phone",
                        ),
                    )
                    Spacer(Modifier.height(16.dp))
                    StatusRow("Accessibility", a11y)
                    if (!a11y) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Installed from a download? Android hides the switch until you allow it:",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    )
                    Numbered(1, "Open App info, tap ⋮ (top right), then Allow restricted settings.")
                    Numbered(2, "Open Accessibility, then Installed apps, then Away, and turn it on.")
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            safeStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                        }) { Text("App info") }
                        Button(onClick = { safeStart(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility") }
                    }
                    }
                }
                3 -> Step(
                    "Hold notifications",
                    "Optional. During Focus, notifications wait in a digest and come back when Focus ends. Urgent ones, like verification codes, still come through.",
                ) {
                    StatusRow("Show Away's alerts", notifAllowed)
                    StatusRow("Notification access", listener)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!notifAllowed && Build.VERSION.SDK_INT >= 33) {
                            FilledTonalButton(onClick = { askNotifs.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow alerts") }
                        }
                        if (!listener) Button(onClick = { safeStart(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notification access") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Notification text stays on your phone unless you add your own AI key later.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                4 -> Step(
                    "A calmer home screen",
                    "Optional. Away can replace your home screen with a quiet one: your time away, a few apps as text, and search when you need anything else. Distracting apps disappear during Focus.",
                ) {
                    StatusRow("Away is your home app", home)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { safeStart(ctx, Intent(Settings.ACTION_HOME_SETTINGS)) }) { Text(if (home) "Change" else "Set as home app") }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "You can switch back any time in Settings, Apps, Default apps, Home app.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                5 -> Step(
                    "Keep Away running",
                    "Phones stop background apps to save battery. Let Away run so blocking and the timers keep working.",
                ) {
                    StatusRow("Battery unrestricted", battery)
                    Spacer(Modifier.height(12.dp))
                    if (!battery) Button(onClick = {
                        safeStart(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("Allow") }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "On Samsung, also add Away to Settings, Battery, Background usage limits, Never sleeping apps.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                else -> Step("You're set", "Here is what is ready. You can change anything later in Away's settings.") {
                    StatusRow("Blocking and timers", a11y)
                    StatusRow("Notification hold", listener)
                    StatusRow("Home screen", home)
                    StatusRow("Runs in the background", battery)
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainer)
                            .toggleable(startFocus, role = Role.Switch) { startFocus = it }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Start Focus now", style = MaterialTheme.typography.titleMedium)
                            Text("Hide distracting apps and hold notifications", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Switch(startFocus, onCheckedChange = null)
                    }
                }
            }
        }

        // Bottom actions.
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step in 3..5) TextButton(onClick = { next() }) { Text("Skip") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { next() }, modifier = Modifier.height(52.dp)) {
                Text(
                    when (step) {
                        0 -> "Get started"
                        2 -> if (a11y) "Continue" else "Later"
                        STEPS - 1 -> "Finish"
                        else -> "Continue"
                    },
                    fontSize = 16.sp,
                )
            }
        }
    }
    }
}

@Composable
private fun Welcome() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppMark(112)
        Spacer(Modifier.height(28.dp))
        Text("Away", fontSize = 44.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(8.dp))
        Text(
            "Spend less time on your phone,\nwithout missing what matters.",
            style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Feature("Focus", "Hides and blocks the apps that pull you in")
            Feature("Quiet notifications", "They wait for you. Urgent ones still come through")
            Feature("A calm home screen", "Counts your time away and keeps you on track")
        }
    }
}

/** The app icon drawn in Compose (adaptive icons cannot be painted directly). */
@Composable
fun AppMark(size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF1B2466), Color(0xFF3B2E9E), Color(0xFF6A3FC8)))),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size((size * 1.5).dp))
    }
}

@Composable
private fun Feature(title: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(cs.primary))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun Step(title: String, body: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        content()
    }
}

@Composable
private fun Disclosure(does: List<String>, never: List<String>) {
    val cs = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Away uses it to", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            does.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(6.dp))
            Text("Away never", style = MaterialTheme.typography.labelLarge, color = cs.primary)
            never.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun Numbered(n: Int, text: String) {
    Row(Modifier.padding(top = 8.dp)) {
        Text("$n.", Modifier.width(22.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ok) {
            Icon(Icons.Filled.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(22.dp))
        } else {
            Box(Modifier.size(22.dp).padding(3.dp).clip(CircleShape).background(cs.surfaceContainerHigh))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(if (ok) "Done" else "Not yet", style = MaterialTheme.typography.labelLarge, color = if (ok) cs.primary else cs.onSurfaceVariant)
    }
}

@Composable
private fun PickApps() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val apps = remember { Apps.loadAll(ctx).sortedBy { it.label.lowercase() } }
    var blocked by remember { mutableStateOf(Prefs.blocked) }
    val initial = remember { Prefs.blocked }
    var q by remember { mutableStateOf("") }
    val shown = remember(q, apps) { Apps.search(apps, emptyMap(), q).sortedBy { if (it.pkg in initial) 0 else 1 } }
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Text("Pick your distracting apps", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "These are hidden and blocked during Focus. Common ones are already ticked.",
            style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Search apps") },
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
            items(shown, key = { it.pkg }) { app ->
                val on = app.pkg in blocked
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .toggleable(on, role = Role.Checkbox) {
                            blocked = if (on) blocked - app.pkg else blocked + app.pkg
                            Prefs.blocked = blocked
                        }.padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Checkbox(on, onCheckedChange = null)
                }
            }
        }
    }
}

fun batteryUnrestricted(ctx: android.content.Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

fun notificationsAllowed(ctx: android.content.Context): Boolean =
    androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()

/** Opens a Settings screen, ignoring phones that do not have it. */
fun safeStart(ctx: android.content.Context, intent: Intent) {
    runCatching { ctx.startActivity(intent) }
}
