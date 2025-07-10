package com.bluetooth.bluetalk

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel

    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private lateinit var deviceListPopup: AlertDialog

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (!discoveredDevices.contains(it)) {
                            discoveredDevices.add(it)
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    showDeviceList()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val container = findViewById<LinearLayout>(R.id.messageContainer)
        val inputField = findViewById<EditText>(R.id.inputField)
        val btnBT = findViewById<Button>(R.id.btnDiscoverBluetooth)
        val btnWiFi = findViewById<Button>(R.id.btnConnectWifiDirect)

        // Chat logic
        inputField.setOnEditorActionListener { _, _, _ ->
            val msg = inputField.text.toString().trim()
            if (msg.isNotEmpty()) {
                val textView = TextView(this).apply {
                    text = "> $msg"
                    setTextColor(Color.parseColor("#00FF00"))
                    typeface = Typeface.MONOSPACE
                    textSize = 16f
                }
                container.addView(textView)
                inputField.setText("")
            }
            true
        }

        // Bluetooth button
        btnBT.setOnClickListener {
            if (bluetoothAdapter == null) {
                showToast("Bluetooth not supported.")
                return@setOnClickListener
            }
            if (!bluetoothAdapter.isEnabled) {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                return@setOnClickListener
            }
            checkBluetoothPermissions()
        }

        // Wi-Fi Direct placeholder
        btnWiFi.setOnClickListener {
            showToast("Wi-Fi Direct feature is coming soon...")
        }

        // Register receivers
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(btReceiver, filter)
    }

    private fun checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            if (permissions.any {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }) {
                ActivityCompat.requestPermissions(this, permissions, 101)
            } else {
                startDiscovery()
            }
        } else {
            startDiscovery()
        }
    }

    private fun startDiscovery() {
        discoveredDevices.clear()
        bluetoothAdapter?.startDiscovery()
        showToast("Scanning for devices...")
    }

    private fun showDeviceList() {
        if (discoveredDevices.isEmpty()) {
            showToast("No devices found.")
            return
        }

        val deviceNames = discoveredDevices.map {
            if (it.name != null) "${it.name} (${it.address})" else it.address
        }.toTypedArray()

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Discovered Bluetooth Devices")
        builder.setItems(deviceNames) { _, which ->
            val selected = discoveredDevices[which]
            showToast("Selected: ${selected.name ?: selected.address}")
        }
        builder.setNegativeButton("Cancel", null)
        deviceListPopup = builder.create()
        deviceListPopup.show()
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(btReceiver)
    }
}
