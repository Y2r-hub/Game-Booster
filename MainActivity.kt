package com.playsmoothly.gamebooster

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

private val Bg = Color(0xFF080D12)
private val Panel = Color(0xFF121B22)
private val Green = Color(0xFF7CFF6B)
private val Muted = Color(0xFF9AAAB5)
private const val PREFS = "game_booster_prefs"
private const val SELECTED_GAMES = "selected_games"
private const val PROFILE_PREFIX = "profile_"
private val profiles = listOf("Balanced", "Battery Saver", "Visual Quality")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GameBoosterHome(this) }
    }
}

private data class DeviceSnapshot(
    val manufacturer: String, val model: String, val androidVersion: String,
    val sdk: Int, val cpuCores: Int, val totalRam: Long, val availableRam: Long,
    val lowMemory: Boolean, val network: String, val metered: Boolean,
    val batteryPercent: Int, val charging: Boolean, val thermal: String
) {
    val usedRam: Long get() = (totalRam - availableRam).coerceAtLeast(0L)
    val usedFraction: Float get() =
        if (totalRam > 0L) (usedRam.toFloat() / totalRam).coerceIn(0f, 1f) else 0f
}

private data class LaunchableApp(val packageName: String, val label: String, val isGame: Boolean)

private fun readDeviceSnapshot(context: Context): DeviceSnapshot {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memory = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(memory)

    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val active = cm.activeNetwork
    val caps = if (active != null) cm.getNetworkCapabilities(active) else null
    val network = when {
        caps == null -> "Offline"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "Connected"
    }
    val metered = cm.isActiveNetworkMetered

    val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val battery = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL

    val thermal = if (Build.VERSION.SDK_INT >= 29) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unknown"
        }
    } else "Not supported on this Android version"

    return DeviceSnapshot(
        manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() },
        model = Build.MODEL.orEmpty(), androidVersion = Build.VERSION.RELEASE ?: "Unknown",
        sdk = Build.VERSION.SDK_INT, cpuCores = Runtime.getRuntime().availableProcessors(),
        totalRam = memory.totalMem, availableRam = memory.availMem, lowMemory = memory.lowMemory,
        network = network, metered = metered, batteryPercent = battery, charging = charging, thermal = thermal
    )
}

@Suppress("DEPRECATION")
private fun discoverLaunchableApps(context: Context): List<LaunchableApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val pm = context.packageManager
    val resolved = if (Build.VERSION.SDK_INT >= 33)
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    else pm.queryIntentActivities(intent, 0)
    return resolved.mapNotNull { info ->
        val activityInfo = info.activityInfo ?: return@mapNotNull null
        val appInfo = activityInfo.applicationInfo
        if (appInfo.packageName == context.packageName) return@mapNotNull null
        LaunchableApp(appInfo.packageName, pm.getApplicationLabel(appInfo).toString(),
            Build.VERSION.SDK_INT >= 26 && appInfo.category == ApplicationInfo.CATEGORY_GAME)
    }.distinctBy { it.packageName }.sortedBy { it.label.lowercase(Locale.getDefault()) }
}

private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
private fun savedGames(context: Context): Set<String> =
    prefs(context).getStringSet(SELECTED_GAMES, emptySet())?.toSet() ?: emptySet()
private fun saveGames(context: Context, packages: Set<String>) {
    prefs(context).edit().putStringSet(SELECTED_GAMES, packages.toSet()).apply()
}
private fun getProfile(context: Context, pkg: String): String =
    prefs(context).getString(PROFILE_PREFIX + pkg, "Balanced") ?: "Balanced"
private fun saveProfile(context: Context, pkg: String, profile: String) {
    prefs(context).edit().putString(PROFILE_PREFIX + pkg, profile).apply()
}
private fun formatBytes(bytes: Long): String =
    String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))

@Composable
fun GameBoosterHome(context: Context) {
    var snapshot by remember { mutableStateOf(readDeviceSnapshot(context)) }
    var allApps by remember { mutableStateOf(discoverLaunchableApps(context)) }
    var selectedPackages by remember { mutableStateOf(savedGames(context)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf("Games") }
    var statusMessage by remember { mutableStateOf("Choose a game to launch") }
    var profileVersion by remember { mutableIntStateOf(0) }
    val selectedApps = allApps.filter { it.packageName in selectedPackages }

    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            snapshot = readDeviceSnapshot(context)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Bg, surface = Panel,
        onBackground = Color.White, onSurface = Color.White)) {
        Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("GAME BOOSTER", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Play Smoothly", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("Less Lag. More Gaming.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Games", "Device", "Status").forEach { tab ->
                    FilterChip(selected = selectedTab == tab, onClick = { selectedTab = tab }, label = { Text(tab) })
                }
            }
            Spacer(Modifier.height(10.dp))

            when (selectedTab) {
                "Games" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("MY GAMES", color = Green, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${selectedApps.size} added • profiles saved on this phone", color = Muted, fontSize = 10.sp)
                        }
                        Button(onClick = { allApps = discoverLaunchableApps(context); showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Bg)) {
                            Text("+ ADD GAME", fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (selectedApps.isEmpty()) {
                        InfoCard("GET STARTED") {
                            Text("No games added yet", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Tap ADD GAME to choose installed apps. Android-identified games appear first; use Show all to add other launchable apps.",
                                color = Muted, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(selectedApps, key = { it.packageName }) { app ->
                                GameRow(app, getProfile(context, app.packageName), onProfile = {
                                    saveProfile(context, app.packageName, it); profileVersion++
                                }, onLaunch = {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                                    if (launchIntent != null) {
                                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(launchIntent)
                                        statusMessage = "Launching ${app.label} • ${getProfile(context, app.packageName)} profile selected"
                                    } else statusMessage = "Could not launch ${app.label}"
                                }, onRemove = {
                                    selectedPackages = selectedPackages - app.packageName
                                    saveGames(context, selectedPackages)
                                })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(statusMessage, color = Muted, fontSize = 11.sp)
                    Text("Profiles are guidance presets only. Android does not let this app change another game's graphics, CPU/GPU speed or FPS automatically.",
                        color = Muted, fontSize = 10.sp)
                }
                "Device" -> DevicePanel(snapshot) { snapshot = readDeviceSnapshot(context) }
                else -> StatusPanel(snapshot)
            }
        }
        if (showAddDialog) AddGameDialog(allApps, selectedPackages, { showAddDialog = false }, {
            selectedPackages = it; saveGames(context, it); showAddDialog = false
        })
    }
}

@Composable
private fun GameRow(app: LaunchableApp, profile: String, onProfile: (String) -> Unit,
                    onLaunch: () -> Unit, onRemove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(if (app.isGame) "Android game category" else "Added app", color = Muted, fontSize = 10.sp)
                }
                TextButton(onClick = onRemove) { Text("REMOVE", color = Muted, fontSize = 10.sp) }
                Button(onClick = onLaunch, colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Bg)) {
                    Text("PLAY", fontWeight = FontWeight.Bold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PROFILE", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.width(10.dp))
                Box {
                    OutlinedButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(profile, color = Green, fontSize = 11.sp)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        profiles.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = {
                            onProfile(p); expanded = false
                        }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddGameDialog(apps: List<LaunchableApp>, selectedPackages: Set<String>,
                          onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var chosen by remember(selectedPackages) { mutableStateOf(selectedPackages.toSet()) }
    var showAll by remember { mutableStateOf(false) }
    val candidates = apps.filter { showAll || it.isGame }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Panel,
        title = { Text("Add installed games", color = Color.White) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show all launchable apps", color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Switch(checked = showAll, onCheckedChange = { showAll = it })
                }
                if (candidates.isEmpty()) Text("No apps identified as games. Turn on Show all launchable apps to select manually.",
                    color = Muted, fontSize = 12.sp)
                else LazyColumn {
                    items(candidates, key = { it.packageName }) { app ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, color = Color.White, fontSize = 13.sp)
                                Text(app.packageName, color = Muted, fontSize = 9.sp)
                            }
                            Checkbox(checked = app.packageName in chosen, onCheckedChange = {
                                chosen = if (it) chosen + app.packageName else chosen - app.packageName
                            })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen) }) { Text("SAVE", color = Green) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = Muted) } })
}

@Composable
private fun DevicePanel(data: DeviceSnapshot, onRefresh: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            InfoCard("YOUR DEVICE") {
                Text("${data.manufacturer} ${data.model}".trim(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                InfoLine("Android", "${data.androidVersion} (API ${data.sdk})")
                InfoLine("CPU cores visible to app", data.cpuCores.toString())
                InfoLine("Physical RAM", formatBytes(data.totalRam))
            }
        }
        item {
            InfoCard("LIVE RAM MONITOR") {
                Text("${(data.usedFraction * 100).toInt()}% used", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                LinearProgressIndicator(progress = { data.usedFraction }, modifier = Modifier.fillMaxWidth().height(9.dp),
                    color = if (data.lowMemory) Color(0xFFFFB454) else Green, trackColor = Color(0xFF26343D))
                InfoLine("Used (approx.)", formatBytes(data.usedRam))
                InfoLine("Available", formatBytes(data.availableRam))
                InfoLine("Total", formatBytes(data.totalRam))
                InfoLine("System low-memory flag", if (data.lowMemory) "Yes" else "No")
                TextButton(onClick = onRefresh) { Text("REFRESH", color = Green) }
            }
        }
        item { Text("RAM values are device-wide figures reported by Android. This app does not force-stop other apps or claim to free RAM.",
            color = Muted, fontSize = 11.sp) }
    }
}

@Composable
private fun StatusPanel(data: DeviceSnapshot) {
    val recommendation = when {
        data.thermal in listOf("Severe", "Critical", "Emergency", "Shutdown") ->
            "Device is reporting high thermal pressure. Pause gaming, remove the case if safe, and let the phone cool."
        data.batteryPercent in 0..19 && !data.charging ->
            "Battery is low. Enable your phone's built-in battery saver and reduce screen brightness."
        data.lowMemory || data.usedFraction > 0.88f ->
            "Available RAM is low. Close apps you are not using through Android's normal app switcher."
        data.network == "Offline" -> "No active network is detected. Check Wi-Fi or mobile data before online play."
        else -> "No major warning is currently reported. Use your game's built-in balanced graphics preset for a stable starting point."
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            InfoCard("LIVE STATUS") {
                InfoLine("Network", data.network)
                InfoLine("Metered connection", if (data.metered) "Yes" else "No")
                InfoLine("Battery", if (data.batteryPercent >= 0) "${data.batteryPercent}%" else "Unavailable")
                InfoLine("Charging", if (data.charging) "Yes" else "No")
                InfoLine("Thermal status", data.thermal)
                Text("Refreshes about every 4 seconds while the app is open.", color = Muted, fontSize = 10.sp)
            }
        }
        item {
            InfoCard("SMART DEVICE ADVICE") {
                Text(recommendation, color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("Network status is connectivity only—not a ping test or internet-speed measurement. Thermal status is available on Android 10+ devices that expose it.",
                    color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp)); content()
        }
    }
}
@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
