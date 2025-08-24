package com.bluetalk.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bluetalk.app.auth.AuthStore

class LoginActivity : AppCompatActivity() {

    private lateinit var phoneField: EditText
    private lateinit var passwordField: EditText
    private lateinit var loginButton: Button
    private lateinit var goSignupButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = AuthStore.get(this)

        // If already logged in, skip to Main
        if (auth.isLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        phoneField = findViewById(R.id.phoneField)
        passwordField = findViewById(R.id.passwordField)
        loginButton = findViewById(R.id.loginButton)
        goSignupButton = findViewById(R.id.goSignupButton)

        loginButton.setOnClickListener {
            val phone = phoneField.text.toString().trim()
            val password = passwordField.text.toString()

            if (phone.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Enter phone and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val ok = auth.login(phone, password)
            if (ok) {
                Toast.makeText(this, "Welcome back!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, "Invalid credentials", Toast.LENGTH_SHORT).show()
            }
        }

        goSignupButton.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }
}
