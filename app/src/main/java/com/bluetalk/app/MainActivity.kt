package com.bluetalk.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
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
    var wifiService: WifiDirectService? = null

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecycler()
        setupInput()
        setupMenuButton()

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
                val profile = com.bluetalk.app.auth.AuthStore.get(this).currentProfile()
                val nick = profile?.nick ?: "Me"
                val payload = "$nick::$text\n"

                // 🔹 Only send, don't add duplicate locally.
                btService?.write(payload)
                wifiService?.sendMessage(payload)

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

    /** --------- Matrix-style menu --------- */
    private fun setupMenuButton() {
        val btnMenu: ImageButton = findViewById(R.id.btnMenu)
        btnMenu.setOnClickListener { showMatrixMenu() }
    }

    private fun showMatrixMenu() {
        val items = arrayOf(
            "Connect via Bluetooth",
            "Connect via Wi-Fi Direct",
            "Clear chat",
            "Log out",
            "Exit"
        )

        AlertDialog.Builder(this, R.style.MatrixMenuDialog)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showBluetoothDevices()
                    1 -> {
                        if (!hasWifiDirectPermission()) {
                            statusLine("⚠️ Missing Wi-Fi permission. Requesting…")
                            askNeededPermissions()
                            return@setItems
                        }
                        wifiService?.register()
                        wifiService?.discoverPeers()
                        statusLine("📡 Wi-Fi Direct: discovering peers…")
                    }
                    2 -> chatAdapter.clear()
                    3 -> {
                        com.bluetalk.app.auth.AuthStore.get(this).logout()
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                    }
                    4 -> finishAffinity()
                }
            }
            .show()
    }

    private fun statusLine(text: String) {
        chatAdapter.submit(ChatMessage(text, isIncoming = true))
        binding.recyclerChat.post {
            binding.recyclerChat.scrollToPosition(chatAdapter.itemCount - 1)
        }
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean = false
    override fun onOptionsItemSelected(item: MenuItem): Boolean = false

    /** ---------------- Bluetooth Device Picker ---------------- */
    @SuppressLint("MissingPermission")
    private fun showBluetoothDevices() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            statusLine("Bluetooth not supported on this device.")
            return
        }
        if (!adapter.isEnabled) {
            startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            statusLine("⚠️ Bluetooth permission missing (CONNECT). Requesting…")
            askNeededPermissions()
            return
        }

        btService?.startServer()

        val devices = adapter.bondedDevices.toList()
        if (devices.isEmpty()) {
            statusLine("No paired devices found. Pair in system settings first.")
            return
        }

        val names = devices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Bluetooth Device")
            .setItems(names) { _, which ->
                val target = devices[which]
                statusLine("Attempting to connect to ${target.name ?: target.address}…")
                try {
                    btService?.connectTo(target)
                } catch (se: SecurityException) {
                    statusLine("⚠️ SecurityException: Bluetooth permission denied.")
                }
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

                        if (hasWifiDirectPermission()) {
                            try {
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
                            } catch (se: SecurityException) {
                                statusLine("⚠️ SecurityException: Wi-Fi Direct permission denied.")
                            }
                        } else {
                            statusLine("⚠️ Missing Wi-Fi Direct permission. Requesting…")
                            askNeededPermissions()
                        }
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
        runOnUiThread {
            val (nick, msg) = parseNickMessage(text)
            val display = if (nick != null) "$nick: $msg" else msg
            chatAdapter.submit(ChatMessage(display, isIncoming = incoming))
            binding.recyclerChat.post {
                binding.recyclerChat.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }

    private fun parseNickMessage(raw: String): Pair<String?, String> {
        val clean = raw.trimEnd('\n', '\r')
        val idx = clean.indexOf("::")
        return if (idx in 1 until clean.length - 2) {
            val nick = clean.substring(0, idx)
            val msg = clean.substring(idx + 2)
            nick to msg
        } else {
            null to clean
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        btService?.stop()
        wifiService?.unregister()
    }

    // ---- Helpers ----
    private fun hasWifiDirectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
