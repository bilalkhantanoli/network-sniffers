package com.example.networksniffer

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val isDebuggable: Boolean = false
)

class MainActivity : ComponentActivity() {

    private var currentMonitoringPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NetworkSnifferTheme {
                AppListScreen(
                    context = this@MainActivity,
                    onAppSelected = { app ->
                        startMonitoringApp(app)
                    }
                )
            }
        }
    }

    private fun startMonitoringApp(app: InstalledApp) {
        currentMonitoringPackage = app.packageName

        // Show confirmation dialog
        Toast.makeText(
            this,
            "Starting network monitoring for: ${app.appName}",
            Toast.LENGTH_SHORT
        ).show()

        // Step 1: Start system proxy service
        val proxyIntent = Intent(this, SystemProxyService::class.java)
        proxyIntent.putExtra("target_package", app.packageName)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(proxyIntent)
        } else {
            startService(proxyIntent)
        }

        // Step 2: Set system proxy (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setSystemProxy("localhost", 8888)
        }

        // Step 3: Wait a moment for proxy to start, then launch traffic detail screen
        Thread {
            Thread.sleep(1000)
            val detailIntent = Intent(this, TrafficDetailActivity::class.java)
            detailIntent.putExtra("package_name", app.packageName)
            detailIntent.putExtra("app_name", app.appName)
            startActivity(detailIntent)
        }.start()

        // Step 4: Launch the target app
        launchApp(app.packageName)
    }

    private fun setSystemProxy(host: String, port: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) 
                as ConnectivityManager
            
            // Note: Setting system proxy programmatically is restricted on Android 10+
            // Users need to set it manually in Settings > Network & Internet > Private DNS
            // Or use adb: adb shell settings put global http_proxy localhost:8888
            
            Toast.makeText(
                this,
                "Note: Set proxy manually in Settings or use:\nadb shell settings put global http_proxy localhost:8888",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun launchApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "Cannot launch app", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        // Stop monitoring when activity is closed
        if (currentMonitoringPackage != null) {
            stopMonitoring()
        }
        super.onDestroy()
    }

    private fun stopMonitoring() {
        val proxyIntent = Intent(this, SystemProxyService::class.java)
        stopService(proxyIntent)
        Toast.makeText(this, "Network monitoring stopped", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun AppListScreen(
    context: Context,
    onAppSelected: (InstalledApp) -> Unit
) {
    val apps = remember { getInstalledApps(context) }
    val searchQuery = remember { mutableStateOf("") }
    val showSystemApps = remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // Header
        Surface(
            color = Color(0xFF6200EE),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Network Sniffer",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Android 10+ • System Proxy",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery.value,
                onValueChange = { searchQuery.value = it },
                label = { Text("Search apps...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Show system apps toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSystemApps.value = !showSystemApps.value }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = showSystemApps.value,
                    onCheckedChange = { showSystemApps.value = it }
                )
                Text("Show system apps", modifier = Modifier.padding(start = 8.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // App count
            Text(
                text = "Total apps: ${apps.size}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Apps list
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    apps.filter { app ->
                        (showSystemApps.value || !app.isSystemApp) &&
                        (app.appName.contains(searchQuery.value, ignoreCase = true) ||
                         app.packageName.contains(searchQuery.value, ignoreCase = true))
                    }
                ) { app ->
                    AppListItem(
                        app = app,
                        onSelected = { onAppSelected(app) }
                    )
                }

                if (apps.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No apps found",
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppListItem(
    app: InstalledApp,
    onSelected: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onSelected() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                
                // Show app type
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    if (app.isSystemApp) {
                        Surface(
                            color = Color(0xFFE0E0E0),
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                "System",
                                fontSize = 10.sp,
                                modifier = Modifier.padding(4.dp, 2.dp)
                            )
                        }
                    }
                    
                    if (app.isDebuggable) {
                        Surface(
                            color = Color(0xFFC8E6C9),
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                "Debuggable",
                                fontSize = 10.sp,
                                color = Color(0xFF2E7D32),
                                modifier = Modifier.padding(4.dp, 2.dp)
                            )
                        }
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Start monitoring",
                modifier = Modifier.size(24.dp),
                tint = Color(0xFF6200EE)
            )
        }
    }
}

@Composable
fun NetworkSnifferTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF6200EE),
            secondary = Color(0xFF03DAC6),
            tertiary = Color(0xFF03DAC6)
        )
    ) {
        content()
    }
}

fun getInstalledApps(context: Context): List<InstalledApp> {
    val packageManager = context.packageManager
    val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

    return packages
        .map { app ->
            val isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isDebuggable = (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            
            InstalledApp(
                packageName = app.packageName,
                appName = packageManager.getApplicationLabel(app).toString(),
                isSystemApp = isSystemApp,
                isDebuggable = isDebuggable
            )
        }
        .filter { !it.packageName.startsWith("com.android") } // Filter out Android framework apps
        .sortedBy { it.appName }
}