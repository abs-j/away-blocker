package app.away

import android.Manifest
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val startTab = intent.getIntExtra("tab", 0)
        setContent { MaterialTheme { App(startTab) } }
    }
}

@Composable
fun App(startTab: Int = 0) {
    var tab by remember { mutableIntStateOf(startTab) }
    val tabs = listOf("Home", "Apps", "Digest", "AI", "Launcher")
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) })
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> HomeScreen()
                1 -> AppsScreen()
                2 -> DigestScreen()
                3 -> AiScreen()
                else -> LauncherSettingsScreen()
            }
        }
    }
}

private fun a11yEnabled(ctx: Context): Boolean {
    val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val me = ComponentName(ctx, BlockerService::class.java).flattenToString()
    return s.split(':').any { it.equals(me, ignoreCase = true) }
}

private fun listenerEnabled(ctx: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

private fun fmtTime(min: Int) = "%02d:%02d".format(min / 60, min % 60)

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var enabled by remember { mutableStateOf(Prefs.enabled) }
    var sched by remember { mutableStateOf(Prefs.scheduleEnabled) }
    var start by remember { mutableIntStateOf(Prefs.startMin) }
    var end by remember { mutableIntStateOf(Prefs.endMin) }
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Time away", fontSize = 16.sp)
        Text(Blocking.formatDuration(Blocking.awayMillis(now)), fontSize = 56.sp)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Blocking", Modifier.weight(1f), fontSize = 18.sp)
                    Switch(checked = enabled, onCheckedChange = { enabled = it; Prefs.enabled = it })
                }
                val status = when {
                    !enabled -> "Off"
                    Blocking.isPaused(now) -> "Paused until " +
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(Prefs.unlockUntil))
                    Blocking.isActive(now) -> "Active now"
                    else -> "On, outside schedule"
                }
                Text(status)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Only during a daily schedule", Modifier.weight(1f))
                    Switch(checked = sched, onCheckedChange = { sched = it; Prefs.scheduleEnabled = it })
                }
                if (sched) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            TimePickerDialog(ctx, { _, h, m -> start = h * 60 + m; Prefs.startMin = start }, start / 60, start % 60, true).show()
                        }) { Text("From ${fmtTime(start)}") }
                        OutlinedButton(onClick = {
                            TimePickerDialog(ctx, { _, h, m -> end = h * 60 + m; Prefs.endMin = end }, end / 60, end % 60, true).show()
                        }) { Text("To ${fmtTime(end)}") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Need a blocked app?", fontSize = 18.sp)
                Text("Wait 30 seconds, then blocking pauses for 10 minutes. Open the app yourself afterwards.")
                Button(enabled = countdown == 0 && Blocking.isActive(now) && !Blocking.isPaused(now), onClick = { countdown = 30 }) {
                    Text(if (countdown > 0) "Unlocking in ${countdown}s" else "Pause blocking")
                }
                if (countdown > 0) OutlinedButton(onClick = { countdown = 0 }) { Text("Cancel") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Setup", fontSize = 18.sp)
                PermRow("Accessibility (blocking + pill)", a11y) {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                PermRow("Notification access (triage)", listener) {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                Text(
                    "If the Enable toggles are greyed out (sideloaded app on Android 13+): open App info, " +
                        "tap the three-dot menu, choose Allow restricted settings, then come back.",
                    fontSize = 13.sp,
                )
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))
                    )
                }) { Text("App info") }
                Text(
                    "Also set battery usage to Unrestricted so Android does not stop Away.",
                    fontSize = 13.sp,
                )
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text("Battery settings") }
            }
        }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label + if (ok) ": on" else ": off", Modifier.weight(1f))
        if (!ok) Button(onClick = onOpen) { Text("Enable") }
    }
}

@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val apps = remember {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ctx.packageManager.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(ctx.packageManager).toString() }
            .filter { it.first != ctx.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    var blocked by remember { mutableStateOf(Prefs.blocked) }
    Column(Modifier.fillMaxSize()) {
        Text("Blocked apps", Modifier.padding(20.dp), fontSize = 20.sp)
        LazyColumn(Modifier.fillMaxSize()) {
            items(apps) { (pkg, label) ->
                val on = pkg in blocked
                Row(
                    Modifier.fillMaxWidth().clickable {
                        blocked = if (on) blocked - pkg else blocked + pkg
                        Prefs.blocked = blocked
                    }.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = null)
                    Text(label, Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

@Composable
fun LauncherSettingsScreen() {
    val ctx = LocalContext.current
    val apps = remember { Apps.loadAll(ctx).sortedBy { it.label.lowercase() } }
    var hidden by remember { mutableStateOf(Prefs.hidden) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Launcher", fontSize = 20.sp)
            Text(
                "Away can be your home screen. Blocked apps are hidden while blocking is active. " +
                    "Swipe up: search. Double tap: lock. Long-press home: this app. Long-press an app: pin, rename, delay, hide.",
                fontSize = 13.sp,
            )
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }) { Text("Set Away as home app") }
            Text("Hidden from launcher", fontSize = 16.sp)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(apps) { app ->
                val on = app.pkg in hidden
                Row(
                    Modifier.fillMaxWidth().clickable {
                        hidden = if (on) hidden - app.pkg else hidden + app.pkg
                        Prefs.hidden = hidden
                    }.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = null)
                    Text(app.label, Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

@Composable
fun DigestScreen() {
    val version = Prefs.digestVersion.intValue
    val items = remember(version) { Prefs.digest() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Digest", Modifier.weight(1f), fontSize = 20.sp)
            OutlinedButton(onClick = { Prefs.clearDigest() }) { Text("Clear") }
        }
        if (items.isEmpty()) Text("No suppressed notifications yet.", Modifier.padding(horizontal = 20.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(items) { d ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(d.time))
                    Text((if (d.urgent) "URGENT  " else "") + "${d.app}  $time", fontSize = 12.sp)
                    Text(d.title, fontSize = 16.sp)
                    if (d.text.isNotBlank()) Text(d.text, fontSize = 14.sp)
                    Text(d.reason, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun AiScreen() {
    var on by remember { mutableStateOf(Prefs.triageEnabled) }
    var key by remember { mutableStateOf(Prefs.apiKey) }
    var model by remember { mutableStateOf(Prefs.model) }
    var vips by remember { mutableStateOf(Prefs.vips) }
    var priv by remember { mutableStateOf(Prefs.privateApps) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI triage", fontSize = 20.sp)
        Text(
            "When on, notification text (with numbers, emails and links removed) is sent to the provider for your key (Anthropic or OpenRouter) " +
                "to decide if it is urgent. Off by default. Calls today: ${Prefs.callsToday()} / ${Prefs.DAILY_CAP}.",
            fontSize = 13.sp,
        )
        if (Prefs.lastError.isNotBlank()) Text("Last AI error: ${Prefs.lastError}", fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use Claude Haiku", Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { on = it; Prefs.triageEnabled = it })
        }
        OutlinedTextField(
            value = key, onValueChange = { key = it; Prefs.apiKey = it },
            label = { Text("API key (Anthropic sk-ant-... or OpenRouter sk-or-...)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it; Prefs.model = it },
            label = { Text(if (key.startsWith("sk-or-")) "Model (blank = openai/gpt-4o-mini)" else "Model (blank = claude-haiku-5-5)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vips, onValueChange = { vips = it; Prefs.vips = it },
            label = { Text("VIP names, comma separated") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = priv, onValueChange = { priv = it; Prefs.privateApps = it },
            label = { Text("Private app package names, never sent") }, modifier = Modifier.fillMaxWidth(),
        )
    }
}
