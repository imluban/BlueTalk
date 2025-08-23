package com.bluetalk.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluetalk.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(),
    BluetoothService.Listener,
    WifiDirectService.Listener {

    private lateinit var binding: ActivityMainBinding
    private val chatAdapter = ChatAdapter()

    private var btService: BluetoothService? = null
    var wifiService: WifiDirectService? = null // keep public for connect()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // No-op
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecycler()
        setupInput()
        setupMenuButton() // 🔹 Matrix-style popup menu

        // Initialize services
        btService = BluetoothService(this, this)
        wifiService = WifiDirectService(this, this)

        askNeededPermissions()
    }

    private fun setupRecycler() {
        binding.recyclerChat.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = chatAdapter
        }
        statusLine("✅ BlueTalk ready. Open menu (⋮) to connect.")
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener {
            val text = binding.inputMessage.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                btService?.write(text + "\n")
                chatAdapter.submit(ChatMessage(text, isIncoming = false))
                binding.inputMessage.setText("")
            }
        }

        binding.inputMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                binding.btnSend.performClick()
                true
            } else false
        }
    }

    // 🔹 NEW: Custom floating ⋮ menu with Matrix styling
    private fun setupMenuButton() {
        val btnMenu: ImageButton = findViewById(R.id.btnMenu)
        btnMenu.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

            // Apply Matrix theme colors
            try {
                val style = R.style.MatrixPopup
                val themedContext = android.view.ContextThemeWrapper(this, style)
                val styledPopup = PopupMenu(themedContext, view)
                styledPopup.menuInflater.inflate(R.menu.main_menu, styledPopup.menu)

                styledPopup.setOnMenuItemClickListener { item ->
                    handleMenuClick(item)
                }
                styledPopup.show()
            } catch (e: Exception) {
                // fallback in case style fails
                popup.setOnMenuItemClickListener { item ->
                    handleMenuClick(item)
                }
                popup.show()
            }
        }
    }

    private fun handleMenuClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_connect_bluetooth -> {
                showBluetoothDevices()
                true
            }
            R.id.action_connect_wifidirect -> {
                wifiService?.register()
                wifiService?.discoverPeers()
                statusLine("📡 Wi-Fi Direct: discovering peers…")
                true
            }
            R.id.action_clear -> {
                chatAdapter.clear()
                true
            }
            R.id.action_exit -> {
                finishAffinity()
                true
            }
            else -> false
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

        if (needs.any {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            requestPermissions.launch(needs.toTypedArray())
        }
    }

    // ❌ ActionBar menus disabled
    override fun onCreateOptionsMenu(menu: Menu): Boolean = false
    override fun onOptionsItemSelected(item: MenuItem): Boolean = false

    /** ---------------- Bluetooth Device Picker ---------------- */
    @SuppressLint("MissingPermission")
    private fun showBluetoothDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            statusLine("Bluetooth not supported on this device.")
            return
        }
        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        btService?.startServer()
        val devices = adapter.bondedDevices.toList()
        if (devices.isEmpty()) {
            statusLine("No paired devices found. Pair in system settings first.")
            return
        }

        val names = devices.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Bluetooth Device")
            .setItems(names) { _, which ->
                val target = devices[which]
                statusLine("Attempting to connect to ${target.name}…")
                btService?.connectTo(target)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** ---------------- Wi-Fi Direct Peer Picker ---------------- */
    override fun onPeers(peers: List<WifiP2pDevice>) {
        runOnUiThread {
            if (peers.isEmpty()) {
                statusLine("📡 Wi-Fi Direct: no peers found")
            } else {
                val names = peers.map { it.deviceName }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Select Wi-Fi Direct Peer")
                    .setItems(names) { _, which ->
                        val chosen = peers[which]
                        statusLine("🔗 Connecting to ${chosen.deviceName}…")

                        val config = WifiP2pConfig().apply {
                            deviceAddress = chosen.deviceAddress
                        }

                        wifiService?.manager?.connect(
                            wifiService?.channel,
                            config,
                            object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    statusLine("✅ Connected to ${chosen.deviceName}")
                                }

                                override fun onFailure(reason: Int) {
                                    statusLine("❌ Connection failed: $reason")
                                }
                            }
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /** ---------------- Listeners ---------------- */
    override fun onStatus(msg: String) {
        runOnUiThread { statusLine(msg) }
    }

    override fun onMessage(text: String, incoming: Boolean) {
        runOnUiThread { chatAdapter.submit(ChatMessage(text, isIncoming = incoming)) }
    }

    override fun onDestroy() {
        super.onDestroy()
        btService?.stop()
        wifiService?.unregister()
    }
}
