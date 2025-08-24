package com.bluetalk.app.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.util.*

data class UserProfile(
    val phone: String,
    val fullName: String,
    val nick: String
)

class AuthStore private constructor(private val ctx: Context) {

    private val masterKey by lazy {
        MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            ctx,
            "auth_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        @Volatile private var INSTANCE: AuthStore? = null
        fun get(ctx: Context): AuthStore = INSTANCE ?: synchronized(this) {
            INSTANCE ?: AuthStore(ctx.applicationContext).also { INSTANCE = it }
        }

        private fun sha256(input: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    /** Returns true if a user is currently logged in */
    fun isLoggedIn(): Boolean = prefs.getBoolean("logged_in", false)

    fun currentProfile(): UserProfile? {
        if (!isLoggedIn()) return null
        val phone = prefs.getString("cur_phone", null) ?: return null
        val fullName = prefs.getString("cur_fullName", "") ?: ""
        val nick = prefs.getString("cur_nick", "") ?: ""
        return UserProfile(phone, fullName, nick)
    }

    fun logout() {
        prefs.edit()
            .putBoolean("logged_in", false)
            .remove("cur_phone")
            .remove("cur_fullName")
            .remove("cur_nick")
            .apply()
    }

    /**
     * Registers a user locally.
     * Stores salted+hashed password, plus profile fields.
     * Returns false if phone already exists.
     */
    fun register(phone: String, password: String, fullName: String, nick: String): Boolean {
        val keyExists = "user_${phone}_hash"
        if (prefs.contains(keyExists)) return false

        val salt = UUID.randomUUID().toString().replace("-", "")
        val hash = sha256("$salt:$password")

        prefs.edit()
            // credentials
            .putString("user_${phone}_salt", salt)
            .putString("user_${phone}_hash", hash)
            // profile
            .putString("user_${phone}_fullName", fullName)
            .putString("user_${phone}_nick", nick)
            .apply()

        // auto-login after signup
        prefs.edit()
            .putBoolean("logged_in", true)
            .putString("cur_phone", phone)
            .putString("cur_fullName", fullName)
            .putString("cur_nick", nick)
            .apply()

        return true
    }

    /**
     * Logs in a user; returns true on success.
     */
    fun login(phone: String, password: String): Boolean {
        val salt = prefs.getString("user_${phone}_salt", null) ?: return false
        val expected = prefs.getString("user_${phone}_hash", null) ?: return false
        val actual = sha256("$salt:$password")
        if (actual != expected) return false

        val fullName = prefs.getString("user_${phone}_fullName", "") ?: ""
        val nick = prefs.getString("user_${phone}_nick", "") ?: ""

        prefs.edit()
            .putBoolean("logged_in", true)
            .putString("cur_phone", phone)
            .putString("cur_fullName", fullName)
            .putString("cur_nick", nick)
            .apply()

        return true
    }
}
