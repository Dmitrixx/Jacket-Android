package app.jacket.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ConnectionConfig(
    val serverUrl: String = "",
    val apiKey: String = ""
)

/** Stores the Jackett address and API key encrypted with an Android Keystore AES key. */
class SecureSettings(context: Context) {
    private val preferences = context.getSharedPreferences("jacket_private_settings", Context.MODE_PRIVATE)

    fun load(): ConnectionConfig {
        return ConnectionConfig(
            serverUrl = decrypt(preferences.getString(KEY_SERVER, null)),
            apiKey = decrypt(preferences.getString(KEY_API_KEY, null))
        )
    }

    fun save(config: ConnectionConfig) {
        preferences.edit()
            .putString(KEY_SERVER, encrypt(config.serverUrl.trim()))
            .putString(KEY_API_KEY, encrypt(config.apiKey.trim()))
            .apply()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(cipher.iv.size + encrypted.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return try {
            val payload = Base64.decode(value, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(payload)
            val iv = ByteArray(GCM_IV_LENGTH).also(buffer::get)
            val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            // A reset or restore can invalidate the Android Keystore key. Ask the user to enter it again.
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = java.security.KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_SERVER = "server_url_enc"
        const val KEY_API_KEY = "api_key_enc"
        const val KEY_ALIAS = "jacket_settings_aes_key"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
