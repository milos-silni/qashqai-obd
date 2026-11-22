package com.qashqai.obd.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** A raw byte pipe to an ELM327 adapter. */
interface ObdTransport : Closeable {
    val input: InputStream
    val output: OutputStream
}

class BluetoothTransport private constructor(private val socket: BluetoothSocket) : ObdTransport {
    override val input: InputStream = socket.inputStream
    override val output: OutputStream = socket.outputStream
    override fun close() = socket.close()

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * Cheap ELM327 clones are picky, so try the standard secure socket, then insecure,
         * then the hidden "channel 1" method that many clones need.
         */
        @SuppressLint("MissingPermission")
        fun connect(adapter: BluetoothAdapter, address: String): BluetoothTransport {
            val device = adapter.getRemoteDevice(address)
            runCatching { adapter.cancelDiscovery() }
            val factories = listOf<() -> BluetoothSocket>(
                { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                {
                    device.javaClass
                        .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                },
            )
            var last: Exception? = null
            for (factory in factories) {
                var socket: BluetoothSocket? = null
                try {
                    socket = factory()
                    socket.connect()
                    return BluetoothTransport(socket)
                } catch (e: Exception) {
                    last = e
                    runCatching { socket?.close() }
                }
            }
            throw IOException("Bluetooth connect failed: ${last?.message}", last)
        }
    }
}
