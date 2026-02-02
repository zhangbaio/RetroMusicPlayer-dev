/*
 * Copyright (c) 2025 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.webdav

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import code.name.monkey.retromusic.App

/**
 * Utility for encrypting and decrypting WebDAV credentials using AndroidX Security library
 * Singleton object for easy access without DI
 */
object WebDAVCryptoUtil {

    private const val TAG = "WebDAVCryptoUtil"
    private const val SHARED_PREFS_FILE = "webdav_encrypted_prefs"

    private val context: Context
        get() = App.getContext()

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Get encrypted SharedPreferences for storing sensitive data
     * If the file exists but was created with a different key, delete it and create a new one
     */
    private fun getEncryptedSharedPreferences(): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                SHARED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open encrypted prefs, clearing and recreating: ${e.message}")
            // Delete the corrupted file and try again
            context.deleteSharedPreferences(SHARED_PREFS_FILE)
            EncryptedSharedPreferences.create(
                context,
                SHARED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    /**
     * Encrypt a password for storage
     * @param password Plain text password
     * @param configId Unique identifier for the config
     * @return Encrypted password placeholder
     */
    fun encryptPassword(password: String, configId: Long): String {
        val prefs = getEncryptedSharedPreferences()
        val key = "password_$configId"
        prefs.edit().putString(key, password).apply()
        // Return a placeholder - the actual encrypted value is in the shared prefs
        return "encrypted://$configId"
    }

    /**
     * Decrypt a password from storage
     * @param encryptedPassword Encrypted password string (or placeholder)
     * @param configId Unique identifier for the config
     * @return Plain text password
     */
    fun decryptPassword(encryptedPassword: String, configId: Long): String {
        Log.d(TAG, "decryptPassword called: encryptedPassword='$encryptedPassword', configId=$configId")
        if (encryptedPassword.startsWith("encrypted://")) {
            val prefs = getEncryptedSharedPreferences()
            val key = "password_$configId"
            val decrypted = prefs.getString(key, "") ?: ""
            Log.d(TAG, "decryptPassword: key='$key', found password length=${decrypted.length}")
            if (decrypted.isEmpty()) {
                Log.w(TAG, "decryptPassword: Password not found in encrypted prefs for key=$key")
            }
            return decrypted
        }
        // Fallback for passwords not yet migrated
        Log.d(TAG, "decryptPassword: returning original password (not encrypted format)")
        return encryptedPassword
    }

    /**
     * Remove encrypted password for a config
     */
    fun removePassword(configId: Long) {
        val prefs = getEncryptedSharedPreferences()
        val key = "password_$configId"
        prefs.edit().remove(key).apply()
    }
}
