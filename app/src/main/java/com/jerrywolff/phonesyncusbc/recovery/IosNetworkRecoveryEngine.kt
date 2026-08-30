package com.jerrywolff.phonesyncusbc.recovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

class IosNetworkRecoveryEngine(
    context: Context,
    private val onDiscoveryListener: (deviceName: String, ipAddress: String, port: Int) -> Unit,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun startDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == "_phonesync._tcp.") {
                    nsdManager.resolveService(serviceInfo, createResolveListener())
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener?.let(nsdManager::stopServiceDiscovery)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        nsdManager.discoverServices(
            "_phonesync._tcp",
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener,
        )
    }

    private fun createResolveListener(): NsdManager.ResolveListener =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val ipAddress = serviceInfo.host.hostAddress ?: return
                onDiscoveryListener(serviceInfo.serviceName, ipAddress, serviceInfo.port)
            }
        }

    fun stopDiscovery() {
        discoveryListener?.let(nsdManager::stopServiceDiscovery)
        discoveryListener = null
    }
}

class IosDataFetcher(
    private val iosIp: String,
    private val iosPort: Int,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "http://$iosIp:$iosPort"

    suspend fun checkStatus(): IosCompanionStatus? = request("/api/status")
    suspend fun fetchMessages(): List<IosMessage> = request<MessagesResponse>("/api/messages")?.data.orEmpty()
    suspend fun fetchContacts(): List<IosContact> = request<ContactsResponse>("/api/contacts")?.data.orEmpty()
    suspend fun fetchNotes(): List<IosNote> = request<NotesResponse>("/api/notes")?.data.orEmpty()
    suspend fun fetchCallHistory(): List<IosCall> = request<CallHistoryResponse>("/api/callhistory")?.data.orEmpty()
    suspend fun fetchSharedFiles(): List<IosSharedFile> = request<SharedFilesResponse>("/api/files")?.data.orEmpty()

    suspend fun fetchFileData(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val encodedPath = java.net.URLEncoder.encode(relativePath, Charsets.UTF_8.name())
                .replace("+", "%20")
            val connection = (URL("$baseUrl/api/file/$encodedPath").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.readBytes()
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private suspend inline fun <reified T> request(endpoint: String): T? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL("$baseUrl$endpoint").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.bufferedReader().use { json.decodeFromString<T>(it.readText()) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}

@Serializable
data class IosCompanionStatus(
    val status: String,
    val version: String,
    val device: String,
    val available: List<String>,
)

@Serializable
data class IosMessage(
    val rowId: Long,
    val address: String,
    val date: Long,
    val text: String,
    val flags: Int,
)

@Serializable
data class MessagesResponse(val type: String, val count: Int, val data: List<IosMessage>)

@Serializable
data class IosContact(
    val id: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
)

@Serializable
data class ContactsResponse(val type: String, val count: Int, val data: List<IosContact>)

@Serializable
data class IosNote(
    val id: String,
    val title: String,
    val content: String,
    val created: Long,
    val modified: Long,
)

@Serializable
data class NotesResponse(val type: String, val count: Int, val data: List<IosNote>)

@Serializable
data class IosCall(
    val id: Long,
    val address: String,
    val date: Long,
    val duration: Long,
    val flags: Int,
)

@Serializable
data class CallHistoryResponse(val type: String, val count: Int, val data: List<IosCall>)

@Serializable
data class IosSharedFile(
    val path: String,
    val size: Long,
    val modified: Long,
)

@Serializable
data class SharedFilesResponse(val type: String, val count: Int, val data: List<IosSharedFile>)
