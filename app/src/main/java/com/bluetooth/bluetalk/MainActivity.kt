package com.bluetooth.bluetalk

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        val container = findViewById<LinearLayout>(R.id.messageContainer)
        val inputField = findViewById<EditText>(R.id.inputField)
        val btnBT = findViewById<Button>(R.id.btnDiscoverBluetooth)
        val btnWiFi = findViewById<Button>(R.id.btnConnectWifiDirect)

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

        btnBT.setOnClickListener {
            if (bluetoothAdapter == null) {
                Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            } else if (!bluetoothAdapter.isEnabled) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivity(enableBtIntent)
            } else {
                Toast.makeText(this, "Discovering Bluetooth devices...", Toast.LENGTH_SHORT).show()
            }
        }

        btnWiFi.setOnClickListener {
            Toast.makeText(this, "Wi-Fi Direct setup initiated (placeholder)", Toast.LENGTH_SHORT).show()
        }
    }
}
