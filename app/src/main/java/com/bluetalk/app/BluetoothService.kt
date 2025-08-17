package com.bluetalk.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

class BluetoothService(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onStatus(msg: String)
        fun onMessage(text: String, incoming: Boolean)
    }

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val executor = Executors.newCachedThreadPool()

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    private val appName = "BlueTalk"
    private val appUuid: UUID = UUID.fromString("6f6a0b2c-5c7a-4d21-9c7f-0f2a4a4c1a10")

    fun hasPermissions(): Boolean {
        if (adapter == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ok = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            return ok
        }
        return true
    }

    fun startServer() {
        stop()
        acceptThread = AcceptThread().also { executor.execute(it) }
        listener.onStatus("Bluetooth: waiting for connection…")
    }

    fun connectTo(device: BluetoothDevice) {
        stop()
        connectThread = ConnectThread(device).also { executor.execute(it) }
    }

    fun write(text: String) {
        connectedThread?.write(text.toByteArray())
    }

    fun stop() {
        try { acceptThread?.cancel() } catch (_: Exception) {}
        try { connectThread?.cancel() } catch (_: Exception) {}
        try { connectedThread?.cancel() } catch (_: Exception) {}
        acceptThread = null
        connectThread = null
        connectedThread = null
    }

    private inner class AcceptThread : Runnable {
        private val serverSocket: BluetoothServerSocket? = try {
            adapter?.listenUsingRfcommWithServiceRecord(appName, appUuid)
        } catch (e: IOException) {
            listener.onStatus("Bluetooth: server failed: ${e.message}")
            null
        }

        override fun run() {
            var socket: BluetoothSocket?
            while (true) {
                socket = try {
                    serverSocket?.accept()
                } catch (e: IOException) {
                    listener.onStatus("Bluetooth: accept failed: ${e.message}")
                    break
                }
                if (socket != null) {
                    manageConnectedSocket(socket, incoming = true)
                    try { serverSocket?.close() } catch (_: Exception) {}
                    break
                }
            }
        }

        fun cancel() {
            try { serverSocket?.close() } catch (_: IOException) { }
        }
    }

    private inner class ConnectThread(private val device: BluetoothDevice) : Runnable {
        private var socket: BluetoothSocket? = null

        @SuppressLint("MissingPermission")
        override fun run() {
            try {
                socket = device.createRfcommSocketToServiceRecord(appUuid)
                adapter?.cancelDiscovery()
                listener.onStatus("Bluetooth: connecting to ${device.name}…")
                socket?.connect()
                manageConnectedSocket(socket!!, incoming = false)
            } catch (e: IOException) {
                listener.onStatus("Bluetooth: connection failed: ${e.message}")
                try { socket?.close() } catch (_: Exception) {}
            }
        }

        fun cancel() { try { socket?.close() } catch (_: Exception) {} }
    }

    private fun manageConnectedSocket(socket: BluetoothSocket, incoming: Boolean) {
        listener.onStatus("Bluetooth: connected to ${socket.remoteDevice.name}")
        connectedThread = ConnectedThread(socket).also { executor.execute(it) }
    }

    private inner class ConnectedThread(private val socket: BluetoothSocket) : Runnable {
        private val inStream: InputStream? = try { socket.inputStream } catch (e: IOException){ null }
        private val outStream: OutputStream? = try { socket.outputStream } catch (e: IOException){ null }

        override fun run() {
            val buffer = ByteArray(1024)
            while (true) {
                try {
                    val bytes = inStream?.read(buffer) ?: break
                    if (bytes > 0) {
                        val text = String(buffer, 0, bytes)
                        listener.onMessage(text, true)
                    }
                } catch (e: IOException) {
                    listener.onStatus("Bluetooth: disconnected")
                    break
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                outStream?.write(bytes)
                listener.onMessage(String(bytes), false)
            } catch (e: IOException) {
                listener.onStatus("Bluetooth: send failed: ${e.message}")
            }
        }

        fun cancel() { try { socket.close() } catch (_: IOException) {} }
    }
}
