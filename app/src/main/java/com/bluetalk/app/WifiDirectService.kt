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

    interface Listener {
        fun onStatus(msg: String)
        fun onPeers(peers: List<WifiP2pDevice>)
    }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(context, context.mainLooper, null)

    private val peersList = mutableListOf<WifiP2pDevice>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        listener.onStatus("Wi-Fi Direct: location permission missing")
                        return
                    }
                    manager.requestPeers(channel) { peers ->
                        peersList.clear()
                        peers.deviceList?.let { peersList.addAll(it) }
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
        context.registerReceiver(receiver, filter)
    }

    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    fun discoverPeers() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            listener.onStatus("Wi-Fi Direct: location permission missing")
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ may require NEARBY_WIFI_DEVICES permission depending on use
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
}
