package com.example.networksniffer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * System Proxy Service for Android 10+
 * Routes all app traffic through a local proxy server
 * No ROOT required
 */
class SystemProxyService : Service() {
    
    private val binder = SystemProxyBinder()
    private var proxyServer: ProxyServer? = null
    private var targetPackage: String? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    companion object {
        private const val TAG = "SystemProxyService"
        private const val PROXY_PORT = 8888
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SystemProxyService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        targetPackage = intent?.getStringExtra("target_package")
        Log.d(TAG, "Starting proxy for package: $targetPackage")

        serviceScope.launch {
            try {
                proxyServer = ProxyServer(PROXY_PORT, targetPackage ?: "")
                proxyServer?.start()
                Log.d(TAG, "Proxy server started on port $PROXY_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy server", e)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun getNetworkRequests(): List<NetworkRequest> =
        proxyServer?.getNetworkRequests() ?: emptyList()

    fun clearRequests() {
        proxyServer?.clearRequests()
    }

    override fun onDestroy() {
        Log.d(TAG, "SystemProxyService destroyed")
        proxyServer?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    inner class SystemProxyBinder : Binder() {
        fun getService(): SystemProxyService = this@SystemProxyService
        fun getProxyPort(): Int = PROXY_PORT
        fun getNetworkRequests(): List<NetworkRequest> = 
            proxyServer?.getNetworkRequests() ?: emptyList()
        fun clearRequests() = proxyServer?.clearRequests()
    }
}

/**
 * Local HTTP/HTTPS proxy server that intercepts traffic
 */
class ProxyServer(private val port: Int, private val targetPackage: String) {
    private val serverSocket: ServerSocket = ServerSocket(port)
    private var isRunning = true
    private val interceptedRequests = CopyOnWriteArrayList<NetworkRequest>()
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    companion object {
        private const val TAG = "ProxyServer"
    }

    fun start() {
        scope.launch {
            while (isRunning) {
                try {
                    val clientSocket = serverSocket.accept()
                    scope.launch {
                        handleClientConnection(clientSocket)
                    }
                } catch (e: SocketException) {
                    if (isRunning) {
                        Log.e(TAG, "Socket error", e)
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        serverSocket.close()
    }

    private suspend fun handleClientConnection(clientSocket: Socket) {
        try {
            val inputStream = clientSocket.inputStream
            val outputStream = clientSocket.outputStream

            // Read HTTP request from client
            val requestData = readHttpRequest(inputStream)
            if (requestData.isEmpty()) {
                clientSocket.close()
                return
            }

            val requestString = String(requestData, StandardCharsets.UTF_8)
            Log.d(TAG, "Received request: ${requestString.lines().firstOrNull()}")

            // Parse HTTP request
            val (method, path, headers) = parseHttpRequest(requestString)

            // Extract host from headers
            val host = extractHost(headers)
            if (host == null) {
                sendErrorResponse(outputStream, 400, "Bad Request")
                clientSocket.close()
                return
            }

            val startTime = System.currentTimeMillis()

            // Forward request to actual server
            val (statusCode, responseHeaders, responseBody) = forwardRequest(
                method,
                host,
                path,
                headers,
                requestString
            )

            val timeTaken = System.currentTimeMillis() - startTime

            // Send response back to client
            sendProxyResponse(outputStream, statusCode, responseHeaders, responseBody)

            // Extract request body
            val requestBody = extractRequestBody(requestString)

            // Store intercepted request
            val networkRequest = NetworkRequest(
                timestamp = startTime,
                method = method,
                url = "http://$host$path",
                headers = headers,
                requestBody = requestBody,
                responseCode = statusCode,
                responseBody = responseBody,
                responseHeaders = responseHeaders,
                timeTaken = timeTaken,
                protocol = "HTTP/1.1"
            )

            interceptedRequests.add(networkRequest)
            NetworkTrafficStore.addRequest(networkRequest)
            Log.d(TAG, "Intercepted: $method $host$path -> $statusCode (${timeTaken}ms)")

            clientSocket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client connection", e)
            try {
                clientSocket.close()
            } catch (ignored: Exception) {
            }
        }
    }

    private suspend fun forwardRequest(
        method: String,
        host: String,
        path: String,
        headers: Map<String, String>,
        originalRequest: String
    ): Triple<Int, Map<String, String>, String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = if (host.contains(":")) {
                    val (hostname, port) = host.split(":")
                    URL("http://$hostname:$port$path")
                } else {
                    URL("http://$host$path")
                }

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = method
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                // Copy headers
                headers.forEach { (key, value) ->
                    if (key.lowercase() !in listOf("host", "connection", "proxy-connection")) {
                        connection.setRequestProperty(key, value)
                    }
                }

                // Extract and forward body if present
                if (method in listOf("POST", "PUT", "PATCH")) {
                    val bodyStart = originalRequest.indexOf("\r\n\r\n") + 4
                    if (bodyStart > 3) {
                        val body = originalRequest.substring(bodyStart)
                        connection.doOutput = true
                        connection.outputStream.write(body.toByteArray())
                    }
                }

                val statusCode = connection.responseCode
                val responseHeaders = connection.headerFields.mapKeys { it.key ?: "" }
                    .filter { it.key.isNotEmpty() }
                    .mapValues { it.value.firstOrNull() ?: "" }

                val responseBody = try {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    ""
                }

                Triple(statusCode, responseHeaders, responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding request", e)
                Triple(502, emptyMap(), "Bad Gateway: ${e.message}")
            }
        }
    }

    private fun readHttpRequest(inputStream: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val bytes = ByteArray(4096)
        var isHeaderComplete = false
        var contentLength = 0

        while (true) {
            val read = inputStream.read(bytes)
            if (read == -1) break

            buffer.write(bytes, 0, read)
            val currentData = buffer.toByteArray()
            val dataString = String(currentData, StandardCharsets.UTF_8)

            if (!isHeaderComplete) {
                val headerEndIndex = dataString.indexOf("\r\n\r\n")
                if (headerEndIndex != -1) {
                    isHeaderComplete = true
                    // Parse Content-Length
                    val contentLengthMatch = Regex("Content-Length: (\\d+)").find(dataString)
                    contentLength = contentLengthMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                    val headerLength = headerEndIndex + 4
                    val currentBodyLength = currentData.size - headerLength

                    if (contentLength == 0 || currentBodyLength >= contentLength) {
                        break
                    }
                }
            } else {
                val headerEndIndex = dataString.indexOf("\r\n\r\n") + 4
                if (currentData.size - headerEndIndex >= contentLength) {
                    break
                }
            }

            if (read < bytes.size) break
        }

        return buffer.toByteArray()
    }

    private fun parseHttpRequest(request: String): Triple<String, String, Map<String, String>> {
        val lines = request.split("\r\n")
        val requestLine = lines[0].split(" ")
        val method = requestLine[0]
        val path = requestLine[1]

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            if (lines[i].isEmpty()) break
            val parts = lines[i].split(": ", limit = 2)
            if (parts.size == 2) {
                headers[parts[0]] = parts[1]
            }
        }

        return Triple(method, path, headers)
    }

    private fun extractHost(headers: Map<String, String>): String? {
        return headers["Host"] ?: headers["host"]
    }

    private fun extractRequestBody(request: String): String {
        val bodyStartIndex = request.indexOf("\r\n\r\n")
        return if (bodyStartIndex != -1) {
            request.substring(bodyStartIndex + 4).trim()
        } else {
            ""
        }
    }

    private fun sendProxyResponse(
        outputStream: OutputStream,
        statusCode: Int,
        headers: Map<String, String>,
        body: String
    ) {
        val response = StringBuilder()
        response.append("HTTP/1.1 $statusCode\r\n")
        headers.forEach { (key, value) ->
            response.append("$key: $value\r\n")
        }
        response.append("Content-Length: ${body.length}\r\n")
        response.append("\r\n")
        response.append(body)

        outputStream.write(response.toString().toByteArray())
        outputStream.flush()
    }

    private fun sendErrorResponse(
        outputStream: OutputStream,
        statusCode: Int,
        message: String
    ) {
        val response = "HTTP/1.1 $statusCode $message\r\n" +
                "Content-Length: ${message.length}\r\n" +
                "\r\n" +
                message
        outputStream.write(response.toByteArray())
        outputStream.flush()
    }

    fun getNetworkRequests(): List<NetworkRequest> = interceptedRequests.toList()

    fun clearRequests() = interceptedRequests.clear()
}