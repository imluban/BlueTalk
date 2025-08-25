package com.bluetalk.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.util.UUID

class BluetoothService(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStatus(msg: String)
        fun onMessage(text: String, incoming: Boolean)
    }

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var serverThread: Thread? = null
    private var clientThread: Thread? = null
    @Volatile private var socket: BluetoothSocket? = null

    fun startServer() {
        if (!hasBtConnect()) {
            listener.onStatus("Bluetooth: permission missing (CONNECT).")
            return
        }
        if (adapter == null) {
            listener.onStatus("Bluetooth not supported on this device.")
            return
        }
        if (serverThread?.isAlive == true) return

        serverThread = Thread {
            try {
                val server = adapter.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
                listener.onStatus("Bluetooth: waiting for incoming connection…")
                val s = server.accept()
                server.close()
                socket = s
                listener.onStatus("Bluetooth: connected.")
                readLoop(s)
            } catch (_: SecurityException) {
                listener.onStatus("Bluetooth: operation blocked by permissions.")
            } catch (e: IOException) {
                listener.onStatus("Bluetooth server error: ${e.message ?: "I/O error"}")
            }
        }.also { it.start() }
    }

    fun connectTo(device: BluetoothDevice) {
        if (!hasBtConnect()) {
            listener.onStatus("Bluetooth: permission missing (CONNECT).")
            return
        }
        clientThread?.interrupt()
        clientThread = Thread {
            try {
                val s = device.createRfcommSocketToServiceRecord(APP_UUID)

                // Discovery cancel requires SCAN on Android 12+
                if (hasBtScan() && adapter?.isDiscovering == true) {
                    adapter.cancelDiscovery()
                }

                s.connect()
                socket = s
                listener.onStatus("Bluetooth: connected to ${safeName(device)}")
                readLoop(s)
            } catch (_: SecurityException) {
                listener.onStatus("Bluetooth: operation blocked by permissions.")
            } catch (e: IOException) {
                listener.onStatus("Bluetooth connect failed: ${e.message ?: "I/O error"}")
            }
        }.also { it.start() }
    }

    fun write(text: String) {
        try {
            socket?.outputStream?.write(text.toByteArray())
            listener.onMessage(text, false)
        } catch (e: IOException) {
            listener.onStatus("Bluetooth write error: ${e.message ?: "I/O error"}")
        }
    }

    fun stop() {
        try { socket?.close() } catch (_: IOException) {}
        serverThread?.interrupt()
        clientThread?.interrupt()
    }

    /** -------- helpers -------- */

    private fun hasBtConnect(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasBtScan(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            device.address
        } else {
            device.name ?: device.address
        }
    } catch (_: SecurityException) {
        device.address
    }

    companion object {
        private const val APP_NAME = "BlueTalk"
        private val APP_UUID: UUID =
            UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    }

    @SuppressLint("MissingPermission")
    private fun readLoop(socket: BluetoothSocket) {
        try {
            val input = socket.inputStream
            val buffer = ByteArray(1024)

            while (true) {
                val bytes = input.read(buffer)
                if (bytes > 0) {
                    val msg = String(buffer, 0, bytes)
                    listener.onMessage(msg, incoming = true)
                } else {
                    break // connection closed
                }
            }
        } catch (e: Exception) {
            listener.onStatus("❌ Connection lost: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
