package com.bluetalk.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bluetalk.app.auth.AuthStore

class SignupActivity : AppCompatActivity() {

    private lateinit var fullNameField: EditText
    private lateinit var nickField: EditText
    private lateinit var phoneField: EditText
    private lateinit var passwordField: EditText
    private lateinit var signupButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        fullNameField = findViewById(R.id.fullNameField)
        nickField = findViewById(R.id.nickField)
        phoneField = findViewById(R.id.phoneField)
        passwordField = findViewById(R.id.passwordField)
        signupButton = findViewById(R.id.signupButton)

        val auth = AuthStore.get(this)

        signupButton.setOnClickListener {
            val fullName = fullNameField.text.toString().trim()
            val nick = nickField.text.toString().trim()
            val phone = phoneField.text.toString().trim()
            val password = passwordField.text.toString()

            if (fullName.isEmpty() || nick.isEmpty() || phone.isEmpty() || password.length < 4) {
                Toast.makeText(this, "Fill all fields (password ≥ 4 chars)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val ok = auth.register(phone, password, fullName, nick)
            if (!ok) {
                Toast.makeText(this, "Phone already registered. Try login.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(this, "Account created!", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
