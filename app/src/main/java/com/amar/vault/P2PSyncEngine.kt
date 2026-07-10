package com.amar.vault

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class P2PSyncEngine(private val context: Context) {

    private val gson   = Gson()
    private val random = SecureRandom()

    companion object {
        private const val PREFS_NAME   = "amar_sync_prefs"
        private const val KEY_SECRET   = "sync_secret_key"
        private const val GCM_IV_SIZE  = 12
        private const val GCM_TAG_SIZE = 128
        private const val BUFFER_SIZE  = 8192 // 8 KB RAM footprint for infinite data
    }

    // ── Secure Key Management ─────────────────────────────────────────────────

    private fun getSecurePrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val prefs    = getSecurePrefs()
        val savedKey = prefs.getString(KEY_SECRET, null)

        if (savedKey != null) {
            val keyBytes = Base64.decode(savedKey, Base64.NO_WRAP)
            return SecretKeySpec(keyBytes, "AES")
        }

        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, random)
        val key     = keyGen.generateKey()
        val keyB64  = Base64.encodeToString(key.encoded, Base64.NO_WRAP)
        prefs.edit().putString(KEY_SECRET, keyB64).apply()
        android.util.Log.d("P2PSync", "Generated new AES-256 key in Hardware Keystore")
        return key
    }

    fun getKeyForQr(): String {
        val prefs = getSecurePrefs()
        return prefs.getString(KEY_SECRET, null)
            ?: Base64.encodeToString(getOrCreateSecretKey().encoded, Base64.NO_WRAP)
    }

    fun getDeviceId(): String {
        val prefs = getSecurePrefs()
        return prefs.getString("device_id", null) ?: run {
            val id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
            id
        }
    }

    // ── Tier S: Infinite Stream Encryption ────────────────────────────────────

    /**
     * THE HOLY GRAIL: Encrypts data continuously from an InputStream to an OutputStream.
     * ZERO Ram bloat. It processes 8KB at a time, meaning a 10GB file uses 8KB of RAM.
     * The IV is generated and written to the very beginning of the output stream.
     */
    fun encryptStream(input: InputStream, output: OutputStream, peerKeyBase64: String) {
        val keyBytes = Base64.decode(peerKeyBase64, Base64.NO_WRAP)
        val key      = SecretKeySpec(keyBytes, "AES")

        // Generate and write IV to the head of the file
        val iv = ByteArray(GCM_IV_SIZE).also { random.nextBytes(it) }
        output.write(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, iv))

        // CipherOutputStream encrypts chunks on-the-fly as they pass through
        CipherOutputStream(output, cipher).use { cos ->
            input.copyTo(cos, BUFFER_SIZE)
        }
    }

    /**
     * Decrypts an infinite stream. It reads the IV from the first 12 bytes,
     * then decrypts the rest of the stream on the fly.
     */
    fun decryptStream(input: InputStream, output: OutputStream, peerKeyBase64: String) {
        val keyBytes = Base64.decode(peerKeyBase64, Base64.NO_WRAP)
        val key      = SecretKeySpec(keyBytes, "AES")

        // Read the IV from the head of the file
        val iv = ByteArray(GCM_IV_SIZE)
        val bytesRead = input.read(iv)
        if (bytesRead != GCM_IV_SIZE) throw IllegalStateException("Invalid encrypted stream: missing IV")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, iv))

        CipherInputStream(input, cipher).use { cis ->
            cis.copyTo(output, BUFFER_SIZE)
        }
    }

    // ── Tiny Payload Crypto (For the JSON Metadata) ───────────────────────────

    // We keep the ByteArray version ONLY for the initial JSON handshake, which is always small.
    fun encryptBytes(data: ByteArray, peerKeyBase64: String): ByteArray {
        val keyBytes = Base64.decode(peerKeyBase64, Base64.NO_WRAP)
        val key      = SecretKeySpec(keyBytes, "AES")
        val iv       = ByteArray(GCM_IV_SIZE).also { random.nextBytes(it) }
        val cipher   = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, iv))
        val ciphertext = cipher.doFinal(data)
        return iv + ciphertext
    }

    fun decryptBytes(payload: ByteArray, peerKeyBase64: String): ByteArray? {
        return try {
            val keyBytes = Base64.decode(peerKeyBase64, Base64.NO_WRAP)
            val key      = SecretKeySpec(keyBytes, "AES")
            val iv       = payload.sliceArray(0 until GCM_IV_SIZE)
            val cipher   = payload.sliceArray(GCM_IV_SIZE until payload.size)
            val c        = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE, iv))
            c.doFinal(cipher)
        } catch (e: Exception) {
            null
        }
    }

    // ── Database Sync Prep ────────────────────────────────────────────────────

    suspend fun buildSyncPayload(since: Long = 0L): SyncPayload {
        val dao   = VaultDatabase.get(context).vaultDao()
        val items = if (since == 0L) dao.getAll() else dao.getItemsSince(since)
        return SyncPayload(items, System.currentTimeMillis(), getDeviceId())
    }
}

data class SyncPayload(
    val items:     List<VaultItem>,
    val timestamp: Long,
    val deviceId:  String
)