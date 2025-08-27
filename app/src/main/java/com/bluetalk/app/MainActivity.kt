package com.bluetalk.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.bluetalk.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), BluetoothService.Listener {

    private lateinit var binding: ActivityMainBinding
    private val chatAdapter = ChatAdapter()

    private var btService: BluetoothService? = null
    // wifiService removed

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecycler()
        setupInput()
        setupMenuButton()

        // Initialize Bluetooth service only
        btService = BluetoothService(this, this)

        askNeededPermissions()
    }

    private fun setupRecycler() {
        binding.recyclerChat.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = chatAdapter
        }
        statusLine("BlueTalk is ready. Open menu (⋮) to connect!")
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener {
            val text = binding.inputMessage.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                val profile = com.bluetalk.app.auth.AuthStore.get(this).currentProfile()
                val nick = profile?.nick ?: "Me"
                val payload = "$nick::$text\n"

                // Only send via Bluetooth
                btService?.write(payload)

                // do NOT add to adapter here — onMessage() will receive it and render (prevents duplicates)
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
            "Clear chat",
            "Profile",
            "Log out",
            "Exit"
        )

        AlertDialog.Builder(this, R.style.MatrixMenuDialog)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showBluetoothDevices()
                    1 -> chatAdapter.clear()
                    2 -> startActivity(Intent(this, ProfileActivity::class.java))
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
        // Keep location only if your Bluetooth flow needs it — otherwise remove it
        needs += Manifest.permission.ACCESS_FINE_LOCATION

        if (needs.any {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            requestPermissions.launch(needs.toTypedArray())
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean = false
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean = false

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
    }
}
