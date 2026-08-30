package com.jerrywolff.phonesyncusbc.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeviceKeyManager {
    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    fun currentProof(): String {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(hmacKey())
        return Base64.encodeToString(mac.doFinal(PROOF_CONTEXT), Base64.NO_WRAP)
    }

    fun encrypt(plainText: ByteArray): String {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(plainText)
        return listOf(cipher.iv, encrypted)
            .joinToString(PAYLOAD_SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    fun decrypt(payload: String): ByteArray {
        val parts = payload.split(PAYLOAD_SEPARATOR, limit = 2)
        require(parts.size == 2) { "Invalid encrypted trust payload" }
        val initializationVector = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            encryptionKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, initializationVector),
        )
        return cipher.doFinal(encrypted)
    }

    private fun encryptionKey(): SecretKey {
        (keyStore.getKey(ENCRYPTION_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    ENCRYPTION_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun hmacKey(): SecretKey {
        (keyStore.getKey(PROOF_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    PROOF_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val ENCRYPTION_ALIAS = "phone_sync_usb_trust_encryption_v1"
        const val PROOF_ALIAS = "phone_sync_usb_trust_proof_v1"
        const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val PAYLOAD_SEPARATOR = "."
        val PROOF_CONTEXT = "phone-sync-usb-trust-v1".toByteArray(Charsets.UTF_8)
    }
}