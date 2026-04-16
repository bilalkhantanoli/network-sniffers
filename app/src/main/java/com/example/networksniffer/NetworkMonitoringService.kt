package com.example.networksniffer

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.LinkedBlockingQueue

/**
 * Central service for managing network request capture and storage
 * Implements Binder pattern for inter-process communication
 */
class NetworkMonitoringService : Service() {
    
    private val binder = NetworkMonitoringBinder()

    companion object {
        val networkRequests = MutableLiveData<List<NetworkRequest>>(emptyList())
        private val requestQueue = LinkedBlockingQueue<NetworkRequest>(1000) // Max 1000 requests
        
        fun addRequest(request: NetworkRequest) {
            try {
                requestQueue.add(request)
                // Notify observers
                networkRequests.postValue(requestQueue.toList())
            } catch (e: IllegalStateException) {
                // Queue full, remove oldest
                requestQueue.poll()
                requestQueue.add(request)
                networkRequests.postValue(requestQueue.toList())
            }
        }
        
        fun getRequests(): List<NetworkRequest> = requestQueue.toList()
        
        fun clearRequests() {
            requestQueue.clear()
            networkRequests.postValue(emptyList())
        }
        
        fun getRequestsLiveData() = networkRequests
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    inner class NetworkMonitoringBinder : Binder() {
        fun getService(): NetworkMonitoringService = this@NetworkMonitoringService
    }
}

/**
 * Global storage for network traffic
 * Use this singleton to access network requests from anywhere
 */
object NetworkTrafficStore {
    private val _requests = MutableLiveData<List<NetworkRequest>>(emptyList())
    val requests = _requests

    fun addRequest(request: NetworkRequest) {
        val current = _requests.value.orEmpty().toMutableList()
        current.add(0, request) // Add to beginning (newest first)
        
        // Keep only last 1000 requests
        if (current.size > 1000) {
            current.removeLast()
        }
        
        _requests.postValue(current)
    }

    fun getAll(): List<NetworkRequest> = _requests.value.orEmpty()

    fun clear() {
        _requests.postValue(emptyList())
    }

    fun getByPackage(packageName: String): List<NetworkRequest> {
        // This would require extending NetworkRequest to include package info
        return getAll()
    }

    fun getByDomain(domain: String): List<NetworkRequest> {
        return getAll().filter { request ->
            try {
                java.net.URL(request.url).host.contains(domain)
            } catch (e: Exception) {
                false
            }
        }
    }

    fun getByStatusCode(statusCode: Int): List<NetworkRequest> {
        return getAll().filter { it.responseCode == statusCode }
    }

    fun getFailedRequests(): List<NetworkRequest> {
        return getAll().filter { it.responseCode >= 400 }
    }

    fun getSlowRequests(thresholdMs: Long = 1000): List<NetworkRequest> {
        return getAll().filter { it.timeTaken >= thresholdMs }
    }

    fun getTotalBytesTransferred(): Long {
        return getAll().sumOf { 
            it.requestBody.length.toLong() + it.responseBody.length.toLong()
        }
    }

    fun getAverageResponseTime(): Long {
        val requests = getAll()
        return if (requests.isEmpty()) 0 else requests.map { it.timeTaken }.average().toLong()
    }

    fun exportAsJson(): String {
        return com.google.gson.GsonBuilder()
            .setPrettyPrinting()
            .create()
            .toJson(getAll())
    }

    fun exportAsCsv(): String {
        val csvHeader = "Timestamp,Method,URL,Status,Duration(ms),Request Size,Response Size\n"
        val csvRows = getAll().map { req ->
            "${req.timestamp},${req.method},${req.url},${req.responseCode},${req.timeTaken}," +
            "${req.requestBody.length},${req.responseBody.length}"
        }.joinToString("\n")
        
        return csvHeader + csvRows
    }
}

/**
 * Enhanced NetworkRequest with package information
 */
data class NetworkRequestWithPackage(
    val request: NetworkRequest,
    val packageName: String,
    val appName: String,
    val captureTime: Long = System.currentTimeMillis()
)

/**
 * Statistics collector for network monitoring
 */
class NetworkStatistics {
    private val allRequests = mutableListOf<NetworkRequest>()

    fun addRequest(request: NetworkRequest) {
        allRequests.add(request)
    }

    fun getStats(): Map<String, Any> = mapOf<String, Any>(
        "total_requests" to allRequests.size,
        "failed_requests" to allRequests.count { it.responseCode >= 400 },
        "average_response_time" to if (allRequests.isNotEmpty()) 
            allRequests.map { it.timeTaken }.average() else 0.0,
        "total_data_transferred" to allRequests.sumOf {
            it.requestBody.length.toLong() + it.responseBody.length.toLong()
        },
        "domains" to allRequests.mapNotNull { req ->
            try {
                java.net.URL(req.url).host
            } catch (e: Exception) {
                null
            }
        }.distinct().size,
        "slowest_request" to (allRequests.maxOfOrNull { it.timeTaken } ?: 0L),
        "fastest_request" to (allRequests.minOfOrNull { it.timeTaken } ?: 0L)
    )

    fun resetStats() {
        allRequests.clear()
    }
}

/**
 * Network filter utility
 */
class NetworkRequestFilter {
    private var requests = emptyList<NetworkRequest>()
    
    fun setRequests(reqs: List<NetworkRequest>) {
        requests = reqs
    }

    fun filterByDomain(domain: String): List<NetworkRequest> {
        return requests.filter { request ->
            try {
                java.net.URL(request.url).host.contains(domain, ignoreCase = true)
            } catch (e: Exception) {
                false
            }
        }
    }

    fun filterByStatusCode(statusCode: Int): List<NetworkRequest> {
        return requests.filter { it.responseCode == statusCode }
    }

    fun filterByMethod(method: String): List<NetworkRequest> {
        return requests.filter { it.method.equals(method, ignoreCase = true) }
    }

    fun filterByTime(fromTime: Long, toTime: Long): List<NetworkRequest> {
        return requests.filter { it.timestamp in fromTime..toTime }
    }

    fun filterByKeyword(keyword: String): List<NetworkRequest> {
        return requests.filter { request ->
            request.url.contains(keyword, ignoreCase = true) ||
            request.requestBody.contains(keyword, ignoreCase = true) ||
            request.responseBody.contains(keyword, ignoreCase = true)
        }
    }

    fun filterSlowRequests(thresholdMs: Long): List<NetworkRequest> {
        return requests.filter { it.timeTaken >= thresholdMs }
    }

    fun filterErrors(): List<NetworkRequest> {
        return requests.filter { it.responseCode >= 400 }
    }

    fun filterSuccessful(): List<NetworkRequest> {
        return requests.filter { it.responseCode in 200..299 }
    }
}