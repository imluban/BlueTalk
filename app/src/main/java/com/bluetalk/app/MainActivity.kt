package com.bluetalk.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluetalk.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), BluetoothService.Listener, WifiDirectService.Listener {

    private lateinit var binding: ActivityMainBinding
    private val chatAdapter = ChatAdapter()

    private var btService: BluetoothService? = null
    private var wifiService: WifiDirectService? = null

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        // no-op; user result handled implicitly
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecycler()
        setupInput()

        // initialize services
        btService = BluetoothService(this, this)
        wifiService = WifiDirectService(this, this)

        askNeededPermissions()
    }

    private fun setupRecycler() {
        binding.recyclerChat.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = chatAdapter
        }
        statusLine("BlueTalk ready. Open menu (⋮) to connect.")
    }

    private fun setupInput() {
        // Send on button click
        binding.btnSend.setOnClickListener {
            val text = binding.inputMessage.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                btService?.write(text + "\n")
                chatAdapter.submit(ChatMessage(text, isIncoming = false))
                binding.inputMessage.setText("")
            }
        }
        // Send on keyboard action
        binding.inputMessage.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                binding.btnSend.performClick()
                true
            } else false
        }
    }

    private fun statusLine(text: String) {
        chatAdapter.submit(ChatMessage(text, isIncoming = true))
        binding.recyclerChat.scrollToPosition(chatAdapter.itemCount - 1)
    }

    private fun askNeededPermissions() {
        val needs = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needs += Manifest.permission.BLUETOOTH_CONNECT
            needs += Manifest.permission.BLUETOOTH_SCAN
        }
        needs += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) {
            needs += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        requestPermissions.launch(needs.toTypedArray())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    @SuppressLint("MissingPermission")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_connect_bluetooth -> {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null) {
                    statusLine("Bluetooth not supported on this device.")
                } else {
                    if (!adapter.isEnabled) {
                        startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }
                    // Start listening and try connect to first bonded device for demo
                    btService?.startServer()
                    val bonded = adapter.bondedDevices
                    val target: BluetoothDevice? = bonded.firstOrNull()
                    if (target != null) {
                        statusLine("Attempting to connect to ${target.name}…")
                        btService?.connectTo(target)
                    } else {
                        statusLine("No paired devices found. Pair in system settings first.")
                    }
                }
                true
            }
            R.id.action_connect_wifidirect -> {
                wifiService?.register()
                wifiService?.discoverPeers()
                statusLine("Wi‑Fi Direct: discovering peers… (Messaging coming next build)")
                true
            }
            R.id.action_clear -> {
                chatAdapter.clear()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // BluetoothService.Listener
    override fun onStatus(msg: String) {
        runOnUiThread { statusLine(msg) }
    }
    override fun onMessage(text: String, incoming: Boolean) {
        runOnUiThread { chatAdapter.submit(ChatMessage(text, isIncoming = incoming)) }
    }

    // WifiDirectService.Listener
    override fun onPeers(peers: List<WifiP2pDevice>) {
        runOnUiThread {
            if (peers.isEmpty()) statusLine("Wi‑Fi Direct: no peers found")
            else statusLine("Wi‑Fi Direct: found ${peers.size} peers")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        btService?.stop()
        wifiService?.unregister()
    }
}
