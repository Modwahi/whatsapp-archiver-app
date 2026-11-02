package com.modwahi.waarchiver.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.modwahi.waarchiver.core.Crypt15
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * مفتاح النسخة (64 hex) مخزّن مشفراً بمفتاح AES في Android Keystore (لا يخرج من الجهاز).
 * لا يُطبع ولا يُسجَّل أبداً.
 */
class KeyVault(context: Context) {
    private val sp = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val alias = "wa_archiver_key"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    val hasKey: Boolean get() = sp.contains("data")

    fun save(keyHex: String) {
        val clean = Crypt15.cleanKey(keyHex)
        require(Crypt15.isValidKey(clean)) { "المفتاح يجب أن يكون 64 خانة من 0-9 و a-f" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = c.doFinal(clean.toByteArray(Charsets.US_ASCII))
        sp.edit()
            .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(enc, Base64.NO_WRAP))
            .apply()
    }

    fun load(): String? {
        val iv = sp.getString("iv", null) ?: return null
        val data = sp.getString("data", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.US_ASCII)
        } catch (e: Exception) {
            null
        }
    }
}
