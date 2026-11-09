package dev.rubenmoya.traelo.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda el Personal Access Token cifrado con AES-GCM. La clave vive en el Android Keystore
 * (alias [KEY_ALIAS]) y no sale nunca de ahi. En SharedPreferences solo queda IV + texto cifrado
 * en base64.
 *
 * El token no se escribe en logs, ni en toString, ni en mensajes de error: esta clase ni siquiera
 * guarda el token en un campo.
 */
class TokenStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun has(): Boolean = prefs.contains(KEY_BLOB)

    /** Cifra y guarda el token. Lanza [IllegalStateException] (sin datos del token) si falla. */
    @Synchronized
    fun save(token: String) {
        val clean = token.trim()
        require(clean.isNotEmpty()) { "Token vacio" }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
            val blob = ByteArray(1 + iv.size + encrypted.size)
            blob[0] = iv.size.toByte()
            iv.copyInto(blob, destinationOffset = 1)
            encrypted.copyInto(blob, destinationOffset = 1 + iv.size)
            prefs.edit().putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            throw IllegalStateException("No se pudo cifrar el token")
        }
    }

    /** Devuelve el token descifrado, o null si no hay o si ya no se puede descifrar. */
    @Synchronized
    fun read(): String? {
        val stored = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val ivSize = blob[0].toInt()
            val iv = blob.copyOfRange(1, 1 + ivSize)
            val encrypted = blob.copyOfRange(1 + ivSize, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            // Clave perdida o dato corrupto: el token ya no sirve, mejor empezar de cero.
            clear()
            null
        }
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            // Si no se puede borrar la clave, da igual: sin el blob no hay nada que descifrar.
        }
    }

    override fun toString(): String = "TokenStore"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "traelo_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val PREFS_NAME = "traelo_token_store"
        const val KEY_BLOB = "token_blob"
    }
}
