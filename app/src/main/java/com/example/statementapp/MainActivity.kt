@file:OptIn(ExperimentalMaterialApi::class, ExperimentalMaterial3Api::class)
package com.example.statementapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Canvas
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import android.telecom.TelecomManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.unit.Dp

// Vristo Colors
val VristoPrimary = Color(0xFF43B02A)
val VristoDanger = Color(0xFFEF4136)
val VristoSuccess = Color(0xFF00AB55)
val VristoWarning = Color(0xFFE2A03F)

val VristoDarkBg = Color(0xFF0E1726)
val VristoDarkCard = Color(0xFF1B2E4B)
val VristoDarkText = Color(0xFF888EA8)

val VristoLightBg = Color(0xFFFAFAFA)
val VristoLightCard = Color(0xFFFFFFFF)
val VristoLightText = Color(0xFF3B3F5C)

@Composable
fun VristoTheme(themeMode: Int, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            background = VristoDarkBg, surface = VristoDarkCard,
            onSurface = VristoDarkText, primary = VristoPrimary, onBackground = Color.White
        )
    } else {
        lightColorScheme(
            background = VristoLightBg, surface = VristoLightCard,
            onSurface = VristoLightText, primary = VristoPrimary, onBackground = Color(0xFF0E1726)
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Create Notification Channel
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "safaricom_status",
                "Safaricom Status",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when Safaricom is up or down"
                enableVibration(true)
            }
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
        installSplashScreen()
        setContent {
            val prefs = getSharedPreferences("SecurePrefs", Context.MODE_PRIVATE)
            var themeMode by remember { mutableStateOf(prefs.getInt("theme_mode", 0)) }
            
            VristoTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LaunchedEffect(Unit) {
                        val perms = mutableListOf(
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.RECEIVE_SMS,
                            Manifest.permission.READ_SMS,
                            Manifest.permission.READ_PHONE_STATE
                        )
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        val missing = perms.filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                        if (missing.isNotEmpty()) {
                            requestPermissionLauncher.launch(missing.toTypedArray())
                        }
                    }
                    MainApp(
                        onCheckPermissions = { checkAndRequestPermissions() },
                        currentTheme = themeMode,
                        onThemeChanged = {
                            themeMode = it
                            prefs.edit().putInt("theme_mode", it).apply()
                        }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.CALL_PHONE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECEIVE_SMS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.READ_SMS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.READ_PHONE_STATE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            // Check if we should show rationale — if not, the user said "Don't ask again", go directly to Settings
            val canShowRationale = permissions.any { shouldShowRequestPermissionRationale(it) }
            if (canShowRationale || permissions.size == 4) {
                requestPermissionLauncher.launch(permissions.toTypedArray())
            } else {
                Toast.makeText(this, "Please enable permissions in App Settings", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
        } else {
            Toast.makeText(this, "All permissions already granted!", Toast.LENGTH_SHORT).show()
        }
    }
}

sealed class BottomNavItem(val route: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String) {
    object Dashboard : BottomNavItem("dashboard", Icons.Filled.Dashboard, "Monitor")
    object History : BottomNavItem("history", Icons.Filled.History, "Logs")
    object Settings : BottomNavItem("settings", Icons.Filled.Settings, "Settings")
}

@Composable
fun MainApp(onCheckPermissions: () -> Unit, currentTheme: Int, onThemeChanged: (Int) -> Unit) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = LocalContext.current
    var showThemeMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentRoute?.replaceFirstChar { it.uppercase() } ?: "Dashboard", fontWeight = FontWeight.ExtraBold) },
                actions = {

                    val hasPerms = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED && (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                    Button(
                        onClick = { if (hasPerms) Toast.makeText(context, "All Permissions Granted!", Toast.LENGTH_SHORT).show() else onCheckPermissions() },
                        colors = ButtonDefaults.buttonColors(containerColor = if (hasPerms) VristoSuccess else VristoWarning),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp).padding(end = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (hasPerms) Icons.Filled.Check else Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (hasPerms) "Granted" else "Permissions", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Box {
                        IconButton(onClick = { showThemeMenu = true }) {
                            Icon(Icons.Filled.Palette, contentDescription = "Theme")
                        }
                        DropdownMenu(expanded = showThemeMenu, onDismissRequest = { showThemeMenu = false }) {
                            DropdownMenuItem(text = { Text("System Default") }, onClick = { onThemeChanged(0); showThemeMenu = false })
                            DropdownMenuItem(text = { Text("Light") }, onClick = { onThemeChanged(1); showThemeMenu = false })
                            DropdownMenuItem(text = { Text("Dark") }, onClick = { onThemeChanged(2); showThemeMenu = false })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, titleContentColor = MaterialTheme.colorScheme.onBackground)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val items = listOf(BottomNavItem.Dashboard, BottomNavItem.History, BottomNavItem.Settings)
                items.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = currentRoute == item.route,
                        onClick = { navController.navigate(item.route) { launchSingleTop = true; restoreState = true } },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = VristoPrimary, unselectedIconColor = MaterialTheme.colorScheme.onSurface)
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = BottomNavItem.Dashboard.route,
            modifier = Modifier.padding(paddingValues),
            enterTransition = {
                val from = initialState.destination.route
                val to = targetState.destination.route
                val routes = listOf("dashboard", "history", "settings")
                val fromIdx = routes.indexOf(from)
                val toIdx = routes.indexOf(to)
                if (toIdx > fromIdx)
                    slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(280)) +
                    fadeIn(animationSpec = tween(200))
                else
                    slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(280)) +
                    fadeIn(animationSpec = tween(200))
            },
            exitTransition = {
                val from = initialState.destination.route
                val to = targetState.destination.route
                val routes = listOf("dashboard", "history", "settings")
                val fromIdx = routes.indexOf(from)
                val toIdx = routes.indexOf(to)
                if (toIdx > fromIdx)
                    slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(280)) +
                    fadeOut(animationSpec = tween(200))
                else
                    slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(280)) +
                    fadeOut(animationSpec = tween(200))
            },
            popEnterTransition = {
                slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(280)) +
                fadeIn(animationSpec = tween(200))
            },
            popExitTransition = {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(280)) +
                fadeOut(animationSpec = tween(200))
            }
        ) {
            composable(BottomNavItem.Dashboard.route) { DashboardScreen() }
            composable(BottomNavItem.History.route) { HistoryScreen() }
            composable(BottomNavItem.Settings.route) { SettingsScreen() }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val dbHelper = remember { DatabaseHelper(context) }
    val scope = rememberCoroutineScope()

    // ── Reactive state — updated by prefs listener AND a 5s poll ticker ──
    var logs by remember { mutableStateOf(dbHelper.getAllLogs()) }
    var pendingTime by remember { mutableStateOf(0L) }
    var lastSuccessTime by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    /** Central reload — called by listener, ticker, and pull-to-refresh */
    fun reload() {
        val metricsPrefs = context.getSharedPreferences("MetricsPrefs", Context.MODE_PRIVATE)
        logs = dbHelper.getAllLogs()
        pendingTime = metricsPrefs.getLong("last_request_time_ms", 0L)
        lastSuccessTime = metricsPrefs.getString("last_success_time", null)
    }

    // Initial load
    LaunchedEffect(Unit) { reload() }

    // 1️⃣  SharedPreferences listener — fires IMMEDIATELY when SmsReceiver writes
    DisposableEffect(Unit) {
        val metricsPrefs = context.getSharedPreferences("MetricsPrefs", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            reload()
        }
        metricsPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { metricsPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // 2️⃣  5-second polling safety net — catches anything the listener might miss
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            reload()
        }
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                delay(600)
                reload()
                isRefreshing = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    )

    val lastLog = logs.firstOrNull()
    val latencyMs = lastLog?.latencyMs ?: 0L

    // Read accessibility state SYNCHRONOUSLY so it's correct on first frame — no flicker
    var isAccessibilityEnabled by remember {
        mutableStateOf(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains(context.packageName) == true
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                isAccessibilityEnabled = enabled?.contains(context.packageName) == true
                reload() // also refresh data on resume
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }


    Box(modifier = Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            // ── Accessibility Setup Card ──────────────────────────────────────
            AnimatedVisibility(
                visible = !isAccessibilityEnabled,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { -it },
                exit = fadeOut(tween(300)) + slideOutHorizontally(tween(300)) { -it }
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E0C0C)),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, VristoDanger.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

                        // Header
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                modifier = Modifier.size(52.dp).background(VristoDanger.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.AccessibilityNew, "Accessibility", tint = VristoDanger, modifier = Modifier.size(30.dp))
                            }
                            Column {
                                Surface(color = VristoDanger.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
                                    Text("ACTION REQUIRED", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = VristoDanger, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                                Spacer(Modifier.height(2.dp))
                                Text("Accessibility Permission", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            }
                        }

                        Text(
                            "The Automator needs Android's Accessibility Service to navigate the M-Pesa USSD menus and enter your PIN automatically. The Start button is disabled until this is enabled.",
                            fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f), lineHeight = 19.sp
                        )

                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                        // Steps
                        Text("How to enable in 5 steps:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccessibilityStep("1", "Tap \"Open Accessibility Settings\" below")
                            AccessibilityStep("2", "Scroll to \"Downloaded Apps\" section")
                            AccessibilityStep("3", "Tap \"M-Pesa Statement Monitor\"")
                            AccessibilityStep("4", "Toggle the switch ON and allow the permission")
                            AccessibilityStep("5", "Return here — this card disappears automatically ✓")
                        }

                        // CTA
                        Button(
                            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VristoDanger),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Filled.OpenInNew, "Open Settings", tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open Accessibility Settings", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                        }
                    }
                }
            }

            // Health Card — with uptime % and last success time
            val isPending = pendingTime > 0L
            val totalLast = logs.size
            val successCountAll = logs.count { it.status == "Success" }
            val uptimePct = if (totalLast > 0) (successCountAll * 100 / totalLast) else -1

            val healthStatus = if (isPending) "Pending — Waiting for SMS..." else if (latencyMs == 0L) "Awaiting First Data" else if (latencyMs < 120000) "Excellent" else if (latencyMs < 300000) "Degraded" else "Unresponsive"
            val healthColor = if (isPending) VristoWarning else if (latencyMs == 0L) VristoPrimary else if (latencyMs < 120000) VristoSuccess else if (latencyMs < 300000) VristoWarning else VristoDanger
            val healthIcon = when {
                isPending -> Icons.Filled.HourglassEmpty
                latencyMs == 0L -> Icons.Filled.WifiProtectedSetup
                latencyMs < 120000 -> Icons.Filled.CheckCircle
                latencyMs < 300000 -> Icons.Filled.Warning
                else -> Icons.Filled.SignalCellularOff
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = healthColor.copy(alpha = 0.12f)),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, healthColor.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(healthIcon, "Status", tint = healthColor, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Safaricom Status", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(healthStatus, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = healthColor)
                        }
                        if (uptimePct >= 0) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("$uptimePct%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = healthColor)
                                Text("Uptime", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    if (lastSuccessTime != null) {
                        HorizontalDivider(color = healthColor.copy(alpha = 0.2f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AccessTime, "Last Success", tint = VristoSuccess, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Last success: $lastSuccessTime", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (latencyMs > 0L) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Speed, "Latency", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Last latency: ${formatLatency(latencyMs)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            // Chart Card
            val recentLogs = logs.take(10).reversed()
            val maxLatency = recentLogs.maxOfOrNull { it.latencyMs } ?: 1L
            val sdfXAxis = SimpleDateFormat("HH:mm", Locale.getDefault())

            Card(modifier = Modifier.fillMaxWidth().height(260.dp), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
                    Text("Latency Over Time", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 8.dp))
                    Spacer(modifier = Modifier.height(4.dp))
                    if (recentLogs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No data yet. Start the automator to begin.", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                        }
                    } else {
                        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            val w = size.width
                            val h = size.height
                            val paddingLeft = 150f   // space for Y labels
                            val paddingBottom = 60f  // space for X labels
                            val paddingTop = 20f
                            val chartW = w - paddingLeft
                            val chartH = h - paddingBottom - paddingTop

                            val textPaint = android.graphics.Paint().apply {
                                color = android.graphics.Color.GRAY
                                textSize = 28f
                                isAntiAlias = true
                            }
                            val axisLinePaint = android.graphics.Paint().apply {
                                color = android.graphics.Color.GRAY
                                strokeWidth = 3f
                                isAntiAlias = true
                            }
                            val gridPaint = android.graphics.Paint().apply {
                                color = android.graphics.Color.LTGRAY
                                strokeWidth = 1f
                                alpha = 100
                                isAntiAlias = true
                            }

                            drawContext.canvas.nativeCanvas.apply {
                                // Draw Y axis
                                drawLine(paddingLeft, paddingTop, paddingLeft, paddingTop + chartH, axisLinePaint)
                                // Draw X axis
                                drawLine(paddingLeft, paddingTop + chartH, w, paddingTop + chartH, axisLinePaint)

                                // Y axis labels + grid (5 steps)
                                val ySteps = 4
                                for (i in 0..ySteps) {
                                    val fraction = i.toFloat() / ySteps
                                    val y = paddingTop + chartH - fraction * chartH
                                    val ms = (maxLatency * fraction).toLong()
                                    // grid line
                                    drawLine(paddingLeft, y, w, y, gridPaint)
                                    // label
                                    val label = formatLatencyForAxis(ms)
                                    val tw = textPaint.measureText(label)
                                    drawText(label, paddingLeft - tw - 10f, y + 10f, textPaint)
                                }

                                // Y axis title (rotated)
                                val titlePaint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.GRAY
                                    textSize = 24f
                                    isAntiAlias = true
                                }
                                save()
                                rotate(-90f, 30f, paddingTop + chartH / 2)
                                drawText("Latency", -chartH / 2 + paddingTop, 30f, titlePaint)
                                restore()

                                // X axis labels
                                val stepX = if (recentLogs.size > 1) chartW / (recentLogs.size - 1) else chartW
                                recentLogs.forEachIndexed { index, log ->
                                    val x = paddingLeft + index * stepX
                                    val label = sdfXAxis.format(Date(log.timestamp))
                                    val tw = textPaint.measureText(label)
                                    if (recentLogs.size <= 5 || index % 2 == 0) {
                                        drawText(label, x - tw / 2, paddingTop + chartH + 50f, textPaint)
                                    }
                                }

                                // Data line
                                val linePath = android.graphics.Path()
                                recentLogs.forEachIndexed { index, log ->
                                    val x = paddingLeft + index * stepX
                                    val pct = log.latencyMs.toFloat() / maxLatency.toFloat()
                                    val y = paddingTop + chartH - pct * chartH
                                    if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                                }
                                drawPath(linePath, android.graphics.Paint().apply {
                                    color = android.graphics.Color.parseColor("#43B02A")
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = 5f
                                    isAntiAlias = true
                                })

                                // Data dots
                                recentLogs.forEachIndexed { index, log ->
                                    val x = paddingLeft + index * stepX
                                    val pct = log.latencyMs.toFloat() / maxLatency.toFloat()
                                    val y = paddingTop + chartH - pct * chartH
                                    drawCircle(x, y, 10f, android.graphics.Paint().apply {
                                        color = android.graphics.Color.parseColor("#43B02A")
                                        isAntiAlias = true
                                    })
                                    drawCircle(x, y, 5f, android.graphics.Paint().apply {
                                        color = android.graphics.Color.WHITE
                                        isAntiAlias = true
                                    })
                                }
                            }
                        }
                        Text("Time of Request \u2192", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp))
                    }
                }
            }

            // Stats Grid
            val timeoutCount = logs.count { it.status == "Timeout" }
            val rateLimitCount = logs.count { it.status == "Rate Limited" }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.Numbers, "Total", tint = VristoPrimary, modifier = Modifier.size(20.dp))
                        Text("${logs.size}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Text("Total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.CheckCircleOutline, "Success", tint = VristoSuccess, modifier = Modifier.size(20.dp))
                        Text("${logs.count { it.status == "Success" }}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = VristoSuccess)
                        Text("Success", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.Timer, "Timeout", tint = VristoDanger, modifier = Modifier.size(20.dp))
                        Text("$timeoutCount", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = VristoDanger)
                        Text("Timeout", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Icon(Icons.Filled.Block, "RateLimit", tint = VristoWarning, modifier = Modifier.size(20.dp))
                        Text("$rateLimitCount", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = VristoWarning)
                        Text("Limited", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // Pie Chart
            val totalLogs = logs.size
            if (totalLogs > 0) {
                val successCountTotal = logs.count { it.status == "Success" }
                val timeoutCountTotal = logs.count { it.status == "Timeout" }
                val rateLimitCountTotal = logs.count { it.status == "Rate Limited" }
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Request Status Distribution", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Canvas(modifier = Modifier.size(120.dp)) {
                                var startAngle = -90f
                                val successAngle = (successCountTotal.toFloat() / totalLogs) * 360f
                                val timeoutAngle = (timeoutCountTotal.toFloat() / totalLogs) * 360f
                                val rateLimitAngle = (rateLimitCountTotal.toFloat() / totalLogs) * 360f
                                
                                if (successAngle > 0) drawArc(color = VristoSuccess, startAngle = startAngle, sweepAngle = successAngle, useCenter = true)
                                startAngle += successAngle
                                if (timeoutAngle > 0) drawArc(color = VristoDanger, startAngle = startAngle, sweepAngle = timeoutAngle, useCenter = true)
                                startAngle += timeoutAngle
                                if (rateLimitAngle > 0) drawArc(color = VristoWarning, startAngle = startAngle, sweepAngle = rateLimitAngle, useCenter = true)
                            }
                            Spacer(modifier = Modifier.width(24.dp))
                            Column {
                                val sPct = if(totalLogs>0) (successCountTotal*100/totalLogs) else 0
                                val tPct = if(totalLogs>0) (timeoutCountTotal*100/totalLogs) else 0
                                val rPct = if(totalLogs>0) (rateLimitCountTotal*100/totalLogs) else 0
                                LegendItem("Success ($sPct%)", VristoSuccess)
                                LegendItem("Timeout ($tPct%)", VristoDanger)
                                LegendItem("Rate Limited ($rPct%)", VristoWarning)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }

        PullRefreshIndicator(refreshing = isRefreshing, state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter), contentColor = VristoPrimary)
        
        Column(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Observe WorkManager state to guard against duplicate scheduling
            val workManager = WorkManager.getInstance(context)
            val periodicWorkInfos by workManager.getWorkInfosForUniqueWorkFlow("ussd_routine")
                .collectAsState(initial = emptyList())
            val immediateWorkInfos by workManager.getWorkInfosForUniqueWorkFlow("ussd_immediate")
                .collectAsState(initial = emptyList())

            val isPeriodicRunning = periodicWorkInfos.any { info ->
                info.state == androidx.work.WorkInfo.State.RUNNING || info.state == androidx.work.WorkInfo.State.ENQUEUED
            }
            val isImmediateRunning = immediateWorkInfos.any { info ->
                info.state == androidx.work.WorkInfo.State.RUNNING || info.state == androidx.work.WorkInfo.State.ENQUEUED
            }
            val isAnyRunning = isPeriodicRunning || isImmediateRunning

            // Stop button
            FloatingActionButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                workManager.cancelAllWork()
                MpesaUssdService.endSession(context)
                context.getSharedPreferences("MetricsPrefs", Context.MODE_PRIVATE).edit().putLong("last_request_time_ms", 0L).apply()
                Toast.makeText(context, "Automator Stopped", Toast.LENGTH_SHORT).show()
            }, containerColor = VristoDanger, contentColor = Color.White) {
                Icon(Icons.Filled.Stop, "Stop")
            }

            // Start button — disabled if accessibility not enabled OR already running
            val fabEnabled = isAccessibilityEnabled && !isAnyRunning
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!isAccessibilityEnabled) {
                    Text(
                        "⚠️ Enable Accessibility above to start",
                        fontSize = 11.sp,
                        color = VristoDanger,
                        fontWeight = FontWeight.Bold
                    )
                }
                ExtendedFloatingActionButton(
                    onClick = {
                        if (!isAccessibilityEnabled) {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            return@ExtendedFloatingActionButton
                        }
                        if (isAnyRunning) {
                            Toast.makeText(context, "Automator is already running!", Toast.LENGTH_SHORT).show()
                            return@ExtendedFloatingActionButton
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val hasPerms = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                            (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                             ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                        if (!hasPerms) {
                            Toast.makeText(context, "Please grant all permissions first!", Toast.LENGTH_LONG).show()
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                            return@ExtendedFloatingActionButton
                        }
                        val prefs = context.getSharedPreferences("SecurePrefs", Context.MODE_PRIVATE)
                        val interval = prefs.getInt("interval", 60)
                        val immediateWork = androidx.work.OneTimeWorkRequestBuilder<UssdWorker>().build()
                        workManager.enqueueUniqueWork("ussd_immediate", androidx.work.ExistingWorkPolicy.KEEP, immediateWork)
                        Toast.makeText(context, "Executing Request Now...", Toast.LENGTH_SHORT).show()
                        val workRequest = PeriodicWorkRequestBuilder<UssdWorker>(interval.toLong(), TimeUnit.MINUTES).build()
                        workManager.enqueueUniquePeriodicWork("ussd_routine", ExistingPeriodicWorkPolicy.KEEP, workRequest)
                        Toast.makeText(context, "Automator Scheduled every $interval min", Toast.LENGTH_SHORT).show()
                    },
                    containerColor = when {
                        !isAccessibilityEnabled -> VristoDanger.copy(alpha = 0.5f)
                        isAnyRunning -> Color.Gray
                        else -> VristoSuccess
                    },
                    contentColor = Color.White,
                    icon = {
                        when {
                            !isAccessibilityEnabled -> Icon(Icons.Filled.Lock, "Locked")
                            isAnyRunning -> Icon(Icons.Filled.CheckCircle, "Active")
                            else -> Icon(Icons.Filled.PlayArrow, "Start")
                        }
                    },
                    text = {
                        Text(
                            when {
                                !isAccessibilityEnabled -> "Setup Required"
                                isAnyRunning -> "Automator Active"
                                else -> "Start Automator"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterialApi::class)
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val dbHelper = remember { DatabaseHelper(context) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    // ── State ──────────────────────────────────────────────────────────
    var activeFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var selectedLog by remember { mutableStateOf<RequestLog?>(null) }

    // Pagination state
    var currentPage by remember { mutableStateOf(0) }
    var displayedLogs by remember { mutableStateOf<List<RequestLog>>(emptyList()) }
    var totalCount by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }

    // Summary strip always shows full counts
    var summaryAll by remember { mutableStateOf(0) }
    var summarySuccess by remember { mutableStateOf(0) }
    var summaryTimeout by remember { mutableStateOf(0) }
    var summaryRateLimit by remember { mutableStateOf(0) }

    // ── Load helper ────────────────────────────────────────────────────
    fun loadPage(page: Int, filter: String?, query: String, append: Boolean) {
        val offset = page * DatabaseHelper.PAGE_SIZE
        val fresh = if (query.isBlank()) {
            dbHelper.getLogsPage(offset, DatabaseHelper.PAGE_SIZE, filter)
        } else {
            // Search mode: no pagination (results already trimmed by searchLogs)
            if (page == 0) dbHelper.searchLogs(query, filter) else emptyList()
        }
        val total = if (query.isBlank()) dbHelper.getLogCount(filter) else fresh.size + offset
        displayedLogs = if (append) displayedLogs + fresh else fresh
        totalCount = total
        hasMore = query.isBlank() && (displayedLogs.size < total)
    }

    fun reloadSummary() {
        summaryAll     = dbHelper.getLogCount(null)
        summarySuccess = dbHelper.getLogCount("Success")
        summaryTimeout = dbHelper.getLogCount("Timeout")
        summaryRateLimit = dbHelper.getLogCount("Rate Limited")
    }

    // Initial load
    LaunchedEffect(activeFilter, searchQuery) {
        currentPage = 0
        loadPage(0, activeFilter, searchQuery, false)
        reloadSummary()
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                isRefreshing = true
                delay(500)
                currentPage = 0
                loadPage(0, activeFilter, searchQuery, false)
                reloadSummary()
                isRefreshing = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    )

    // Date range filter state
    var dateFromMs by remember { mutableStateOf<Long?>(null) }
    var dateToMs by remember { mutableStateOf<Long?>(null) }
    val sdfDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    // Re-trigger load when date range changes
    LaunchedEffect(dateFromMs, dateToMs) {
        currentPage = 0
        loadPage(0, activeFilter, searchQuery, false)
    }

    Box(modifier = Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top bar: metrics or search field ──────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // When search is active, the field takes all available space
                if (searchExpanded) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search date, status…", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VristoPrimary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        ),
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, "Clear", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    )
                } else {
                    // Metrics chips
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MetricChip("$summaryAll Total", MaterialTheme.colorScheme.onSurface)
                        MetricChip("✅ $summarySuccess", VristoSuccess)
                        MetricChip("⏱ $summaryTimeout", VristoDanger)
                        MetricChip("⚠️ $summaryRateLimit", VristoWarning)
                    }
                }
                // Search toggle icon
                IconButton(onClick = {
                    searchExpanded = !searchExpanded
                    if (!searchExpanded) searchQuery = ""
                }) {
                    Icon(
                        if (searchExpanded) Icons.Filled.SearchOff else Icons.Filled.Search,
                        "Toggle Search",
                        tint = if (searchExpanded) VristoPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // ── Filter chips ───────────────────────────────────────────
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf(null to "All", "Success" to "Success", "Timeout" to "Timeout", "Rate Limited" to "Limited")
                items(filters.size) { idx ->
                    val (filter, label) = filters[idx]
                    val selected = activeFilter == filter
                    val chipColor = when(filter) {
                        "Success" -> VristoSuccess; "Timeout" -> VristoDanger
                        "Rate Limited" -> VristoWarning; else -> MaterialTheme.colorScheme.onSurface
                    }
                    Surface(
                        modifier = Modifier.clickable { activeFilter = filter },
                        color = if (selected) chipColor.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(20.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) chipColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        )
                    ) {
                        Text(
                            label,
                            color = if (selected) chipColor else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // ── Date range filter ──────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val cal = java.util.Calendar.getInstance()
                // From date button
                Surface(
                    modifier = Modifier.clickable {
                        val c = java.util.Calendar.getInstance().apply {
                            dateFromMs?.let { timeInMillis = it }
                        }
                        android.app.DatePickerDialog(context, { _, y, m, d ->
                            cal.set(y, m, d, 0, 0, 0)
                            dateFromMs = cal.timeInMillis
                        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show()
                    },
                    color = if (dateFromMs != null) VristoPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (dateFromMs != null) VristoPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                ) {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.CalendarMonth, "From", modifier = Modifier.size(13.dp), tint = if (dateFromMs != null) VristoPrimary else MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (dateFromMs != null) "From: ${sdfDate.format(java.util.Date(dateFromMs!!))}"
                            else "From date",
                            fontSize = 11.sp,
                            color = if (dateFromMs != null) VristoPrimary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (dateFromMs != null) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
                // To date button
                Surface(
                    modifier = Modifier.clickable {
                        val c = java.util.Calendar.getInstance().apply {
                            dateToMs?.let { timeInMillis = it }
                        }
                        android.app.DatePickerDialog(context, { _, y, m, d ->
                            cal.set(y, m, d, 23, 59, 59)
                            dateToMs = cal.timeInMillis
                        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show()
                    },
                    color = if (dateToMs != null) VristoPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (dateToMs != null) VristoPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                ) {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.CalendarMonth, "To", modifier = Modifier.size(13.dp), tint = if (dateToMs != null) VristoPrimary else MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (dateToMs != null) "To: ${sdfDate.format(java.util.Date(dateToMs!!))}"
                            else "To date",
                            fontSize = 11.sp,
                            color = if (dateToMs != null) VristoPrimary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (dateToMs != null) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
                // Clear date filter
                if (dateFromMs != null || dateToMs != null) {
                    IconButton(onClick = { dateFromMs = null; dateToMs = null }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, "Clear dates", tint = VristoDanger, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // ── Log list ─────────────────────────────────────────────
            // Apply date range filter client-side
            val filteredByDate = displayedLogs.filter { log ->
                (dateFromMs == null || log.timestamp >= dateFromMs!!) &&
                (dateToMs == null || log.timestamp <= dateToMs!!)
            }

            if (filteredByDate.isEmpty() && !isRefreshing) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.SearchOff, "Empty", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(48.dp))
                        Text(
                            if (searchQuery.isNotBlank()) "No results for \"$searchQuery\""
                            else if (activeFilter != null) "No $activeFilter records."
                            else "No history yet.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold
                        )
                        if (searchQuery.isBlank() && activeFilter == null)
                            Text("Start the Automator to begin monitoring.", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Spacer(Modifier.height(6.dp)) }

                    // Page info
                    item {
                        Text(
                            if (searchQuery.isNotBlank()) "${displayedLogs.size} results for \"$searchQuery\""
                            else "Showing ${displayedLogs.size} of $totalCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }

                    items(filteredByDate, key = { it.id }) { log ->
                        val statusColor = when(log.status) {
                            "Success" -> VristoSuccess; "Rate Limited" -> VristoWarning; else -> VristoDanger
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 2 }
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { selectedLog = log },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.width(4.dp).height(44.dp).background(statusColor, RoundedCornerShape(2.dp)))
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(sdf.format(Date(log.timestamp)), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp)
                                            Text("Latency: ${formatLatency(log.latencyMs)}", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Surface(color = statusColor.copy(alpha = 0.15f), shape = RoundedCornerShape(20.dp)) {
                                        Text(log.status, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                                    }
                                }
                            }
                        }
                    }

                    // ── Load More ──────────────────────────────────────
                    if (hasMore || isLoadingMore) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                if (isLoadingMore) {
                                    CircularProgressIndicator(color = VristoPrimary, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                isLoadingMore = true
                                                val nextPage = currentPage + 1
                                                loadPage(nextPage, activeFilter, searchQuery, true)
                                                currentPage = nextPage
                                                isLoadingMore = false
                                            }
                                        },
                                        border = androidx.compose.foundation.BorderStroke(1.dp, VristoPrimary),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Icon(Icons.Filled.ExpandMore, "Load more", tint = VristoPrimary, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Load ${minOf(DatabaseHelper.PAGE_SIZE, totalCount - displayedLogs.size)} more", color = VristoPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }

        PullRefreshIndicator(refreshing = isRefreshing, state = pullRefreshState, modifier = Modifier.align(Alignment.TopCenter), contentColor = VristoPrimary)

        // ── Detail Modal ───────────────────────────────────────────────
        selectedLog?.let { log ->
            val statusColor = when(log.status) { "Success" -> VristoSuccess; "Rate Limited" -> VristoWarning; else -> VristoDanger }
            AlertDialog(
                onDismissRequest = { selectedLog = null },
                title = { Text("Log Details", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(color = statusColor.copy(alpha = 0.15f), shape = RoundedCornerShape(20.dp)) {
                            Text(log.status, color = statusColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                        HorizontalDivider()
                        Row { Text("Time: ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground); Text(sdf.format(Date(log.timestamp)), color = MaterialTheme.colorScheme.onSurface) }
                        Row { Text("Latency: ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground); Text(formatLatency(log.latencyMs), color = MaterialTheme.colorScheme.onSurface) }
                        Row { Text("Log ID: ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground); Text("#${log.id}", color = MaterialTheme.colorScheme.onSurface) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedLog = null }) { Text("Close", color = VristoPrimary, fontWeight = FontWeight.Bold) }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("SecurePrefs", Context.MODE_PRIVATE)

    var pin by remember { mutableStateOf(prefs.getString("mpesa_pin", "") ?: "") }
    var email by remember { mutableStateOf(prefs.getString("email", "") ?: "") }
    var appPassword by remember { mutableStateOf(prefs.getString("app_password", "") ?: "") }
    var interval by remember { mutableStateOf(prefs.getInt("interval", 60).toString()) }
    var showPin by remember { mutableStateOf(false) }
    var showPass by remember { mutableStateOf(false) }
    var testEmailStatus by remember { mutableStateOf("") }
    var isSendingTest by remember { mutableStateOf(false) }

    // Validation state
    val pinError = pin.isNotEmpty() && (pin.length < 4 || pin.length > 6 || !pin.all { it.isDigit() })
    val intervalVal = interval.toIntOrNull() ?: 0
    val intervalError = intervalVal < 1
    val intervalWarning = intervalVal in 1..14
    val emailError = email.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // ── Automation Credentials ──────────────────────────────────────
        Text("Automation Credentials", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

        OutlinedTextField(
            value = pin, onValueChange = { if (it.length <= 6) pin = it },
            label = { Text("M-Pesa PIN") },
            visualTransformation = if (showPin) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
            trailingIcon = {
                IconButton(onClick = { showPin = !showPin }) {
                    Icon(if (showPin) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Toggle PIN visibility")
                }
            },
            isError = pinError,
            supportingText = { if (pinError) Text("PIN must be 4–6 digits", color = VristoDanger) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = interval, onValueChange = { interval = it },
            label = { Text("Check Interval (Minutes)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            isError = intervalError,
            supportingText = {
                when {
                    intervalError -> Text("Must be at least 1 minute", color = VristoDanger)
                    intervalWarning -> Text("⚠️ Android enforces a 15-min minimum for background tasks", color = VristoWarning, fontSize = 11.sp)
                    else -> Text("WorkManager will run every $intervalVal min (min 15 min enforced by Android)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)
        )

        HorizontalDivider()

        // ── Email Reporting ─────────────────────────────────────────────
        Text("Email Reporting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

        OutlinedTextField(
            value = email, onValueChange = { email = it },
            label = { Text("Gmail Address") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            isError = emailError,
            supportingText = { if (emailError) Text("Enter a valid email address", color = VristoDanger) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = appPassword, onValueChange = { appPassword = it },
            label = { Text("Gmail App Password") },
            visualTransformation = if (showPass) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Toggle password visibility")
                }
            },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)
        )

        // Help link
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://myaccount.google.com/apppasswords"))
                context.startActivity(intent)
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.OpenInNew, "Open", tint = VristoPrimary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("How to generate a Gmail App Password", fontSize = 12.sp, color = VristoPrimary)
        }

        // Test Email Button
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (email.isEmpty() || appPassword.isEmpty()) {
                        Toast.makeText(context, "Enter email and app password first", Toast.LENGTH_SHORT).show()
                        return@OutlinedButton
                    }
                    isSendingTest = true
                    testEmailStatus = "Sending..."
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            EmailSender.sendEmail(
                                senderEmail = email,
                                appPassword = appPassword,
                                recipientEmail = email,
                                subject = "✅ M-Pesa Monitor — Test Email",
                                body = "Your email reporting is configured correctly!"
                            )
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                testEmailStatus = "✅ Sent!"
                                isSendingTest = false
                            }
                        } catch (e: Exception) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                testEmailStatus = "❌ Failed: ${e.message?.take(60)}"
                                isSendingTest = false
                            }
                        }
                    }
                },
                enabled = !isSendingTest,
                border = androidx.compose.foundation.BorderStroke(1.dp, VristoPrimary),
                modifier = Modifier.weight(1f)
            ) {
                if (isSendingTest) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = VristoPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isSendingTest) "Sending..." else "Send Test Email", fontSize = 13.sp, color = VristoPrimary)
            }
        }
        if (testEmailStatus.isNotEmpty()) {
            Text(testEmailStatus, fontSize = 12.sp, color = if (testEmailStatus.startsWith("✅")) VristoSuccess else VristoDanger)
        }

        HorizontalDivider()

        // ── Save ────────────────────────────────────────────────────────
        val canSave = !pinError && !intervalError && !emailError && pin.isNotEmpty()
        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val safeInterval = maxOf(15, intervalVal)
                prefs.edit()
                    .putString("mpesa_pin", pin)
                    .putString("email", email)
                    .putString("app_password", appPassword)
                    .putInt("interval", safeInterval)
                    .apply()
                interval = safeInterval.toString()
                Toast.makeText(context, "Settings Saved!", Toast.LENGTH_SHORT).show()
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = VristoPrimary),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Save Settings", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(32.dp))
        var showResetDialog by remember { mutableStateOf(false) }

        Button(
            onClick = { showResetDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = VristoDanger),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Factory Reset App", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        if (showResetDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showResetDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("Factory Reset", color = VristoDanger) },
                text = { Text("This will permanently delete all logs, credentials, metrics, and cancel all background jobs. Are you sure?", color = MaterialTheme.colorScheme.onSurface) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { 
                        showResetDialog = false
                        WorkManager.getInstance(context).cancelAllWork()
                        prefs.edit().clear().apply()
                        context.getSharedPreferences("MetricsPrefs", Context.MODE_PRIVATE).edit().clear().apply()
                        DatabaseHelper(context).clearAllLogs()
                        pin = ""
                        email = ""
                        appPassword = ""
                        Toast.makeText(context, "App completely reset!", Toast.LENGTH_LONG).show()
                    }) { 
                        Text("RESET", color = VristoDanger, fontWeight = FontWeight.Bold) 
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showResetDialog = false }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurface) }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Privacy Policy
        var showPrivacyDialog by remember { mutableStateOf(false) }

        androidx.compose.material3.TextButton(
            onClick = { showPrivacyDialog = true },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            Text("Privacy Policy", fontSize = 14.sp, color = VristoPrimary, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
        }

        if (showPrivacyDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showPrivacyDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("Privacy Policy", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "This app uses the Android Accessibility Service to automate USSD menu navigation on your behalf. " +
                            "It reads the content of the USSD dialogs to determine which options to select and where to input your PIN.",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Text(
                            "We also require the 'Read SMS' permission to detect when Safaricom sends your statement confirmation.",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Text(
                            "Data Privacy:\n" +
                            "• Your M-Pesa PIN and Gmail credentials are encrypted and stored securely on your local device.\n" +
                            "• The Accessibility Service does not log, record, or transmit any of your screen content to third-party servers.\n" +
                            "• This app operates entirely offline except when sending emails directly from your own Gmail account to yourself.",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showPrivacyDialog = false }) {
                        Text("I Understand", color = VristoPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
fun AccessibilityStep(number: String, description: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier.size(22.dp).background(VristoDanger.copy(alpha = 0.25f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(number, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = VristoDanger)
        }
        Text(description, fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.weight(1f))
    }
}

@Composable
fun MetricChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(16.dp)) {
        Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
fun LegendItem(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Box(modifier = Modifier.size(12.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}


fun formatLatency(ms: Long): String {
    if (ms == 0L) return "0ms"
    if (ms < 1000) return "${ms}ms"
    if (ms < 60000) return "${ms / 1000}s (${ms}ms)"
    
    val hours = ms / 3600000
    val minutes = (ms % 3600000) / 60000
    val seconds = (ms % 60000) / 1000
    
    val parts = mutableListOf<String>()
    if (hours > 0) parts.add("${hours}h")
    if (minutes > 0) parts.add("${minutes}m")
    if (seconds > 0 && hours == 0L) parts.add("${seconds}s")
    
    return "${parts.joinToString(" ")} (${ms}ms)"
}

fun formatLatencyForAxis(ms: Long): String {
    if (ms == 0L) return "0"
    if (ms < 1000) return "${ms}ms"
    if (ms < 60000) return "${ms / 1000}s"
    val m = ms / 60000
    if (m < 60) return "${m}m"
    val h = ms / 3600000
    val remM = (ms % 3600000) / 60000
    return if (remM > 0) "${h}h ${remM}m" else "${h}h"
}
