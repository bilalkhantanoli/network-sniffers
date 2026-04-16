package com.example.networksniffer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class TrafficDetailActivity : ComponentActivity() {

    private var systemProxyService: SystemProxyService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SystemProxyService.SystemProxyBinder
            systemProxyService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            systemProxyService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra("package_name") ?: "Unknown"
        val appName = intent.getStringExtra("app_name") ?: "Unknown App"

        // Bind to proxy service
        val intent = Intent(this, SystemProxyService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            NetworkSnifferTheme {
                TrafficDetailScreen(
                    context = this@TrafficDetailActivity,
                    packageName = packageName,
                    appName = appName,
                    getRequests = { systemProxyService?.getNetworkRequests() ?: emptyList() },
                    clearRequests = { systemProxyService?.clearRequests() }
                )
            }
        }
    }

    override fun onDestroy() {
        unbindService(serviceConnection)
        super.onDestroy()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TrafficDetailScreen(
    context: Context,
    packageName: String,
    appName: String,
    getRequests: () -> List<NetworkRequest>,
    clearRequests: () -> Unit
) {
    val requests = remember { mutableStateOf<List<NetworkRequest>>(emptyList()) }
    val selectedRequest = remember { mutableStateOf<NetworkRequest?>(null) }
    val searchQuery = remember { mutableStateOf("") }
    val filterStatus = remember { mutableStateOf("") }

    // Auto-refresh every 500ms to get new requests
    LaunchedEffect(Unit) {
        while (true) {
            requests.value = getRequests()
            delay(500)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with app info
        TopAppBar(
            title = {
                Column {
                    Text(appName, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(packageName, fontSize = 11.sp, color = Color.Gray)
                }
            },
            actions = {
                IconButton(onClick = {
                    requests.value = getRequests()
                }) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
                
                IconButton(onClick = {
                    exportNetworkLog(context, requests.value)
                }) {
                    Icon(Icons.Default.Download, "Export")
                }
                
                IconButton(onClick = {
                    clearRequests()
                    requests.value = emptyList()
                }) {
                    Icon(Icons.Default.Clear, "Clear")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (selectedRequest.value == null) {
            // Network list view
            NetworkListView(
                requests = requests.value,
                searchQuery = searchQuery,
                filterStatus = filterStatus,
                onRequestSelected = { selectedRequest.value = it }
            )
        } else {
            // Detailed view
            NetworkDetailView(
                request = selectedRequest.value!!,
                onBack = { selectedRequest.value = null }
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NetworkListView(
    requests: List<NetworkRequest>,
    searchQuery: MutableState<String>,
    filterStatus: MutableState<String>,
    onRequestSelected: (NetworkRequest) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search and filter bar
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
        ) {
            OutlinedTextField(
                value = searchQuery.value,
                onValueChange = { searchQuery.value = it },
                label = { Text("Filter by domain...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(45.dp),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Status filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "2xx", "3xx", "4xx", "5xx", "Errors").forEach { status ->
                    FilterChip(
                        selected = filterStatus.value == status,
                        onClick = { filterStatus.value = if (filterStatus.value == status) "" else status },
                        label = { Text(status, fontSize = 11.sp) }
                    )
                }
            }
        }

        Divider(thickness = 1.dp)

        // Stats row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF5F5F5))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Total: ${requests.size}", fontSize = 11.sp)
            Text("Avg: ${
                if (requests.isNotEmpty()) 
                    "${requests.map { it.timeTaken }.average().toLong()}ms" 
                else "0ms"
            }", fontSize = 11.sp)
            Text("Data: ${
                (requests.sumOf { it.responseBody.length } / 1024)
            }KB", fontSize = 11.sp)
        }

        Divider(thickness = 1.dp)

        // Requests list
        val filteredRequests = requests.filter { request ->
            val query = searchQuery.value.lowercase()
            val matchesSearch = request.url.lowercase().contains(query)
            val matchesFilter = when (filterStatus.value) {
                "2xx" -> request.responseCode in 200..299
                "3xx" -> request.responseCode in 300..399
                "4xx" -> request.responseCode in 400..499
                "5xx" -> request.responseCode >= 500
                "Errors" -> request.responseCode >= 400
                else -> true
            }
            matchesSearch && matchesFilter
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filteredRequests) { request ->
                NetworkRequestRow(request) {
                    onRequestSelected(request)
                }
                Divider(thickness = 0.5.dp, color = Color(0xFFEEEEEE))
            }

            if (filteredRequests.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (requests.isEmpty())
                                "Waiting for network requests...\n(Make sure app is running)"
                            else
                                "No requests match filter",
                            color = Color.Gray,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkRequestRow(request: NetworkRequest, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clickable { onClick() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        Surface(
            color = getStatusColor(request.responseCode),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.size(4.dp)
        ) {}

        // Domain
        val domain = try {
            java.net.URL(request.url).host
        } catch (e: Exception) {
            request.url.take(30)
        }

        Text(
            text = domain,
            modifier = Modifier.weight(2f),
            fontSize = 12.sp,
            maxLines = 1
        )

        // Method
        Text(
            text = request.method,
            modifier = Modifier.width(50.dp),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )

        // Status code
        Text(
            text = request.responseCode.toString(),
            modifier = Modifier.width(40.dp),
            fontSize = 11.sp,
            color = getStatusColor(request.responseCode),
            fontWeight = FontWeight.Bold
        )

        // Time
        Text(
            text = "${request.timeTaken}ms",
            modifier = Modifier.width(50.dp),
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

fun getStatusColor(statusCode: Int): Color {
    return when {
        statusCode in 200..299 -> Color(0xFF4CAF50) // Green
        statusCode in 300..399 -> Color(0xFF2196F3) // Blue
        statusCode in 400..499 -> Color(0xFFFF9800) // Orange
        statusCode >= 500 -> Color(0xFFF44336) // Red
        else -> Color.Gray
    }
}

@Composable
fun NetworkDetailView(request: NetworkRequest, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Back button
        Button(
            onClick = onBack,
            modifier = Modifier.padding(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))
        ) {
            Text("← Back")
        }

        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            // URL and method
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BadgeText(request.method)
                        Text(
                            text = request.responseCode.toString(),
                            color = getStatusColor(request.responseCode),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = request.url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Duration: ${request.timeTaken}ms", fontSize = 11.sp)
                        Text("Time: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(request.timestamp))}", fontSize = 11.sp)
                    }
                }
            }

            // Tabs
            TabSection(request)
        }
    }
}

@Composable
fun TabSection(request: NetworkRequest) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Headers", "Request", "Response", "Cookies")

    Column {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontSize = 12.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> HeadersTab(request)
            1 -> RequestTab(request)
            2 -> ResponseTab(request)
            3 -> CookiesTab(request)
        }
    }
}

@Composable
fun HeadersTab(request: NetworkRequest) {
    Column {
        if (request.headers.isNotEmpty()) {
            Text("Request Headers", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            request.headers.forEach { (key, value) ->
                HeaderRow(key, value)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (request.responseHeaders.isNotEmpty()) {
            Text("Response Headers", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            request.responseHeaders.forEach { (key, value) ->
                HeaderRow(key, value)
            }
        }
    }
}

@Composable
fun HeaderRow(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = key,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            modifier = Modifier.width(120.dp),
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF666666)
        )
    }
}

@Composable
fun RequestTab(request: NetworkRequest) {
    Column {
        Text("Method: ${request.method}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("Protocol: ${request.protocol}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        
        if (request.requestBody.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Body:", fontWeight = FontWeight.Bold)
            CodeBlock(request.requestBody)
        } else {
            Spacer(modifier = Modifier.height(12.dp))
            Text("(No body)", color = Color.Gray, fontSize = 11.sp)
        }
    }
}

@Composable
fun ResponseTab(request: NetworkRequest) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Status: ${request.responseCode}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text("Time: ${request.timeTaken}ms", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }

        if (request.responseBody.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Body:", fontWeight = FontWeight.Bold)
            
            val prettyBody = try {
                val json = JsonParser.parseString(request.responseBody)
                GsonBuilder().setPrettyPrinting().create().toJson(json)
            } catch (e: Exception) {
                request.responseBody
            }
            
            CodeBlock(prettyBody)
        } else {
            Spacer(modifier = Modifier.height(12.dp))
            Text("(No body)", color = Color.Gray, fontSize = 11.sp)
        }
    }
}

@Composable
fun CookiesTab(request: NetworkRequest) {
    val cookies = request.responseHeaders["Set-Cookie"]?.split(";") ?: emptyList()
    
    if (cookies.isEmpty()) {
        Text("No cookies set", color = Color.Gray)
    } else {
        cookies.forEach { cookie ->
            if (cookie.isNotEmpty()) {
                CodeBlock(cookie.trim())
            }
        }
    }
}

@Composable
fun CodeBlock(content: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        color = Color(0xFFF5F5F5),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = content,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            modifier = Modifier.padding(12.dp),
            color = Color(0xFF333333)
        )
    }
}

@Composable
fun BadgeText(text: String) {
    Surface(
        color = Color(0xFF6200EE),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(6.dp, 3.dp)
        )
    }
}

fun exportNetworkLog(context: Context, requests: List<NetworkRequest>) {
    try {
        val json = GsonBuilder().setPrettyPrinting().create().toJson(requests)
        val fileName = "network_log_${System.currentTimeMillis()}.json"
        val file = File(context.getExternalFilesDir(null), fileName)
        file.writeText(json)
        Log.d("Export", "Exported to: ${file.absolutePath}")
    } catch (e: Exception) {
        Log.e("Export", "Failed to export", e)
    }
}