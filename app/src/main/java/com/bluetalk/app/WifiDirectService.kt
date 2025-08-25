package com.bluetalk.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WifiDirectService(private val context: Context, private val listener: Listener) {

    companion object {
        private const val TAG = "WiFiDirect"
        private const val PORT = 8988
    }

    // Exposed so MainActivity can access them
    val manager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    val channel: WifiP2pManager.Channel =
        manager.initialize(context, context.mainLooper, null)

    interface Listener {
        fun onStatus(msg: String)
        fun onPeers(peers: List<WifiP2pDevice>)
        fun onMessage(text: String, incoming: Boolean)
    }

    private val peersList = mutableListOf<WifiP2pDevice>()

    // Networking
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val running = AtomicBoolean(false)
    private var isGroupOwner = false
    private var groupOwnerAddress: InetAddress? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (!hasWifiPermissions()) {
                        listener.onStatus("Wi-Fi Direct: missing permission")
                        return
                    }
                    manager.requestPeers(channel) { peers ->
                        peersList.clear()
                        peersList.addAll(peers.deviceList)
                        listener.onPeers(peersList)
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    // connection changed → ask for connection info to know GO/client
                    val networkInfo: NetworkInfo? =
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        if (!hasWifiPermissions()) {
                            listener.onStatus("Wi-Fi Direct: missing permission")
                            return
                        }
                        manager.requestConnectionInfo(channel) { info ->
                            handleConnectionInfo(info)
                        }
                    } else {
                        listener.onStatus("Wi-Fi Direct: disconnected")
                        stopSockets()
                    }
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    // optional: state toggled
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    // optional
                }
            }
        }
    }

    private val filter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    fun register() {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
        stopSockets()
    }

    fun discoverPeers() {
        if (!hasWifiPermissions()) {
            listener.onStatus("Wi-Fi Direct: missing permission")
            return
        }
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Discovery started")
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Discovery failed: $reason")
                listener.onStatus("Wi-Fi Direct: discovery failed ($reason)")
            }
        })
    }

    fun connect(device: WifiP2pDevice) {
        if (!hasWifiPermissions()) {
            listener.onStatus("Wi-Fi Direct: missing permission")
            return
        }
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection request successful")
                listener.onStatus("Wi-Fi Direct: connection requested…")
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Connection failed: $reason")
                listener.onStatus("Wi-Fi Direct: connection failed ($reason)")
            }
        })
    }

    /** Called after connection when we know GO/client role and the GO IP */
    private fun handleConnectionInfo(info: WifiP2pInfo) {
        isGroupOwner = info.isGroupOwner
        groupOwnerAddress = info.groupOwnerAddress
        listener.onStatus(
            if (isGroupOwner) "✅ Connected as Group Owner (server)"
            else "✅ Connected as Client to ${groupOwnerAddress?.hostAddress}"
        )

        stopSockets() // reset any previous session

        if (isGroupOwner) {
            startServer()
        } else {
            startClient(groupOwnerAddress)
        }
    }

    /** Server: listen and accept one client, then start IO loop */
    private fun startServer() {
        running.set(true)
        acceptExecutor.execute {
            try {
                serverSocket = ServerSocket(PORT).also {
                    Log.d(TAG, "Server: listening on $PORT")
                }
                val client = serverSocket!!.accept()
                Log.d(TAG, "Server: client connected from ${client.inetAddress.hostAddress}")
                setupStreams(client)
                startReaderLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}", e)
                listener.onStatus("❌ Wi-Fi Direct server error: ${e.message}")
                stopSockets()
            }
        }
    }

    /** Client: connect to GO and start IO loop */
    private fun startClient(address: InetAddress?) {
        if (address == null) {
            listener.onStatus("❌ Wi-Fi Direct: GO address unknown")
            return
        }
        running.set(true)
        ioExecutor.execute {
            try {
                val sock = Socket(address, PORT)
                Log.d(TAG, "Client: connected to ${address.hostAddress}:$PORT")
                setupStreams(sock)
                startReaderLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Client error: ${e.message}", e)
                listener.onStatus("❌ Wi-Fi Direct client error: ${e.message}")
                stopSockets()
            }
        }
    }

    /** Prepare text streams */
    private fun setupStreams(sock: Socket) {
        socket = sock
        reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
    }

    /** Continuously read lines and pass to UI */
    private fun startReaderLoop() {
        ioExecutor.execute {
            try {
                while (running.get()) {
                    val line = reader?.readLine() ?: break
                    // Treat everything coming in as "incoming"
                    listener.onMessage(line, true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "IO read error: ${e.message}", e)
                listener.onStatus("❌ Wi-Fi Direct IO error: ${e.message}")
            } finally {
                stopSockets()
            }
        }
    }

    /** Public API: send a line to peer (adds newline if missing) */
    fun sendMessage(message: String) {
        val out = writer
        if (out == null) {
            listener.onStatus("⚠️ Wi-Fi Direct message not sent (not connected)")
            return
        }
        ioExecutor.execute {
            try {
                val line = if (message.endsWith("\n")) message else "$message\n"
                out.write(line)
                out.flush()
                Log.d(TAG, "Sent (Wi-Fi): $line")
                // For parity with Bluetooth, you can notify your own UI if desired:
                // listener.onMessage(message.trimEnd('\n', '\r'), incoming = false)
            } catch (e: Exception) {
                Log.e(TAG, "Send error: ${e.message}", e)
                listener.onStatus("❌ Wi-Fi Direct send failed: ${e.message}")
            }
        }
    }

    /** Close sockets and stop loops */
    private fun stopSockets() {
        running.set(false)
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
        reader = null
        writer = null
        socket = null
        serverSocket = null
    }

    fun stop() {
        stopSockets()
        runCatching { acceptExecutor.shutdownNow() }
        runCatching { ioExecutor.shutdownNow() }
    }

    /** Permissions */
    private fun hasWifiPermissions(): Boolean {
        val fine =
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        val nearbyOk = if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
        return fine && nearbyOk
    }
}
