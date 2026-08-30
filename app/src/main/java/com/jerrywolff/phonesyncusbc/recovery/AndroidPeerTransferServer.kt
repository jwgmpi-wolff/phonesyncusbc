package com.jerrywolff.phonesyncusbc.recovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

object AndroidPeerTransferServer {
    private const val PORT = 8765
    private const val SERVICE_TYPE = "_phonesync._tcp"
    private val started = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            started.set(false)
            return
        }

        val sharedDir = ensureSharedDirectory(context)
        ensureSampleFile(sharedDir)

        ioScope.launch {
            try {
                server = ServerSocket(PORT)
                registerService()
                while (!server!!.isClosed) {
                    val socket = server!!.accept()
                    ioScope.launch { handleClient(socket, sharedDir) }
                }
            } catch (_: Exception) {
                started.set(false)
            }
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        registrationListener?.let { nsdManager?.unregisterService(it) }
        registrationListener = null
        started.set(false)
    }

    private fun registerService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "PhoneSyncCompanion-${android.os.Build.MODEL ?: "Android"}"
            serviceType = SERVICE_TYPE
            port = PORT
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }

        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun handleClient(socket: Socket, sharedDir: File) {
        try {
            val requestLine = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: ""
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                socket.close()
                return
            }

            val method = parts[0]
            val path = parts[1]
            if (method != "GET") {
                socket.close()
                return
            }

            val responseBody = when {
                path == "/api/status" -> buildStatusPayload()
                path == "/api/files" -> buildFilesPayload(sharedDir)
                path.startsWith("/api/file/") -> readFilePayload(path, sharedDir)
                else -> null
            }

            val out = socket.getOutputStream()
            val payload = responseBody ?: returnNotFound()
            out.write(payload)
            out.flush()
        } catch (_: Exception) {
        } finally {
            socket.close()
        }
    }

    private fun buildStatusPayload(): ByteArray {
        val payload = mapOf(
            "status" to "online",
            "version" to "android-peer",
            "device" to (android.os.Build.MODEL ?: "Android"),
            "available" to listOf("files"),
        )
        return buildHttpResponse("application/json", json.encodeToString(payload).toByteArray(Charsets.UTF_8))
    }

    private fun buildFilesPayload(sharedDir: File): ByteArray {
        val files = sharedDir.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val relative = file.relativeTo(sharedDir).invariantSeparatorsPath
                mapOf(
                    "path" to relative,
                    "size" to file.length(),
                    "modified" to file.lastModified(),
                )
            }
            .toList()

        val payload = mapOf(
            "type" to "files",
            "count" to files.size,
            "data" to files,
        )
        return buildHttpResponse("application/json", json.encodeToString(payload).toByteArray(Charsets.UTF_8))
    }

    private fun readFilePayload(path: String, sharedDir: File): ByteArray? {
        val rawPath = path.removePrefix("/api/file/")
        val decoded = URLDecoder.decode(rawPath, Charsets.UTF_8.name())
        val target = File(sharedDir, decoded.removePrefix("/").replace("/", File.separator))
        if (!target.exists() || !target.isFile) {
            return null
        }
        return buildHttpResponse("application/octet-stream", target.readBytes())
    }

    private fun returnNotFound(): ByteArray {
        return buildHttpResponse("application/json", "{\"error\":\"not_found\"}".toByteArray(Charsets.UTF_8))
    }

    private fun buildHttpResponse(contentType: String, body: ByteArray): ByteArray {
        val header = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        return header + body
    }

    private fun ensureSharedDirectory(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "PhoneSyncShared")
        dir.mkdirs()
        return dir
    }

    private fun ensureSampleFile(sharedDir: File) {
        val sample = File(sharedDir, "sample/README.txt")
        if (!sample.exists()) {
            sample.parentFile?.mkdirs()
            sample.writeText(
                "PhoneSync shared files\n" +
                    "This file is exposed by the Android peer transfer service for hotspot testing.\n",
                Charsets.UTF_8,
            )
        }
    }
}
