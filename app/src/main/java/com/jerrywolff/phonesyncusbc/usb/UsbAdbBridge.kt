package com.jerrywolff.phonesyncusbc.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal fun isAdbInterface(
    interfaceClass: Int,
    interfaceSubclass: Int,
    interfaceProtocol: Int,
): Boolean {
    return interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
        interfaceSubclass == ADB_INTERFACE_SUBCLASS &&
        interfaceProtocol == ADB_INTERFACE_PROTOCOL
}

internal class UsbAdbBridge private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inputEndpoint: UsbEndpoint,
    private val outputEndpoint: UsbEndpoint,
    private val serverSocket: ServerSocket,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "phone-sync-usb-adb").apply { isDaemon = true }
    }

    @Volatile
    private var clientSocket: Socket? = null

    val port: Int = serverSocket.localPort

    init {
        executor.execute {
            runCatching {
                val socket = serverSocket.accept()
                if (closed.get()) {
                    socket.close()
                    return@runCatching
                }
                clientSocket = socket
                if (closed.get()) {
                    socket.close()
                    return@runCatching
                }
                executor.execute { copyTcpToUsb(socket) }
                executor.execute { copyUsbToTcp(socket) }
            }.onFailure {
                close()
            }
        }
    }

    private fun copyTcpToUsb(socket: Socket) {
        try {
            val buffer = ByteArray(USB_TRANSFER_BUFFER_BYTES)
            val input = socket.getInputStream()
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) break
                var offset = 0
                while (offset < count && !closed.get()) {
                    val written = connection.bulkTransfer(
                        outputEndpoint,
                        buffer,
                        offset,
                        count - offset,
                        USB_WRITE_TIMEOUT_MILLIS,
                    )
                    if (written <= 0) throw IOException("The source stopped accepting ADB data over USB.")
                    offset += written
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    private fun copyUsbToTcp(socket: Socket) {
        try {
            val buffer = ByteArray(USB_TRANSFER_BUFFER_BYTES)
            val output = socket.getOutputStream()
            while (!closed.get()) {
                val count = connection.bulkTransfer(
                    inputEndpoint,
                    buffer,
                    buffer.size,
                    USB_READ_POLL_TIMEOUT_MILLIS,
                )
                if (count > 0) {
                    output.write(buffer, 0, count)
                    output.flush()
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { clientSocket?.close() }
        runCatching { serverSocket.close() }
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
        executor.shutdownNow()
    }

    companion object {
        fun open(usbManager: UsbManager, device: UsbDevice): UsbAdbBridge? {
            val usbInterface = (0 until device.interfaceCount)
                .map(device::getInterface)
                .firstOrNull { candidate ->
                    isAdbInterface(
                        candidate.interfaceClass,
                        candidate.interfaceSubclass,
                        candidate.interfaceProtocol,
                    )
                } ?: return null
            val inputEndpoint = (0 until usbInterface.endpointCount)
                .map(usbInterface::getEndpoint)
                .firstOrNull { endpoint ->
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_IN
                } ?: return null
            val outputEndpoint = (0 until usbInterface.endpointCount)
                .map(usbInterface::getEndpoint)
                .firstOrNull { endpoint ->
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT
                } ?: return null
            val connection = usbManager.openDevice(device) ?: return null
            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                return null
            }
            return try {
                UsbAdbBridge(
                    connection = connection,
                    usbInterface = usbInterface,
                    inputEndpoint = inputEndpoint,
                    outputEndpoint = outputEndpoint,
                    serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress()),
                )
            } catch (throwable: Throwable) {
                runCatching { connection.releaseInterface(usbInterface) }
                connection.close()
                throw throwable
            }
        }
    }
}

private const val ADB_INTERFACE_SUBCLASS = 0x42
private const val ADB_INTERFACE_PROTOCOL = 0x01
private const val USB_TRANSFER_BUFFER_BYTES = 16 * 1024
private const val USB_READ_POLL_TIMEOUT_MILLIS = 1_000
private const val USB_WRITE_TIMEOUT_MILLIS = 30_000