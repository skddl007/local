package com.local.ondevicedocumentassistant

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

object SecurePreferences {
    private const val PREFERENCES_NAME = "secure_document_assistant"
    private const val DATABASE_PASSPHRASE = "database_passphrase"
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        if (preferences != null) return

        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encryptedPreferences = EncryptedSharedPreferences.create(
            context,
            PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        if (!encryptedPreferences.contains(DATABASE_PASSPHRASE)) {
            val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
            encryptedPreferences.edit()
                .putString(DATABASE_PASSPHRASE, Base64.encodeToString(passphrase, Base64.NO_WRAP))
                .apply()
            passphrase.fill(0)
        }
        preferences = encryptedPreferences
    }

    fun databasePassphrase(context: Context): ByteArray {
        initialize(context)
        val encoded = preferences?.getString(DATABASE_PASSPHRASE, null)
            ?: error("Database passphrase is unavailable")
        return Base64.decode(encoded, Base64.NO_WRAP)
    }
}