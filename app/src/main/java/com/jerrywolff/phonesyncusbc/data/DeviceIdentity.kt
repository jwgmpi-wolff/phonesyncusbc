package com.jerrywolff.phonesyncusbc.data

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object DeviceIdentity {
    fun localId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()
        return sha256("${context.packageName}|$androidId")
    }

    fun peerId(
        platform: String,
        vendorId: Int,
        productId: Int,
        serialNumber: String,
    ): String = sha256("$platform|$vendorId|$productId|$serialNumber")

    fun profileId(platform: String, vendorId: Int, productId: Int): String {
        return sha256("$platform|$vendorId|$productId")
    }

    fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}