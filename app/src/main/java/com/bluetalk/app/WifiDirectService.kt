package com.bluetalk.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat

class WifiDirectService(private val context: Context, private val listener: Listener) {

    // Exposed so MainActivity can access them
    val manager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    val channel: WifiP2pManager.Channel =
        manager.initialize(context, context.mainLooper, null)

    interface Listener {
        fun onStatus(msg: String)
        fun onPeers(peers: List<WifiP2pDevice>)
    }

    private val peersList = mutableListOf<WifiP2pDevice>()

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
                    listener.onStatus("Wi-Fi Direct: connection state changed")
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
    }

    fun discoverPeers() {
        if (!hasWifiPermissions()) {
            listener.onStatus("Wi-Fi Direct: missing permission")
            return
        }
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("WiFiDirect", "Discovery started")
            }
            override fun onFailure(reason: Int) {
                Log.e("WiFiDirect", "Discovery failed: $reason")
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
                Log.d("WiFiDirect", "Connection request successful")
            }
            override fun onFailure(reason: Int) {
                Log.e("WiFiDirect", "Connection failed: $reason")
            }
        })
    }

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
