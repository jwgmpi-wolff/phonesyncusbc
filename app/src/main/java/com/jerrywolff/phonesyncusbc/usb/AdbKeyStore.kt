package com.jerrywolff.phonesyncusbc.usb

import android.content.Context
import dadb.AdbKeyPair
import java.io.File

internal class AdbKeyStore(context: Context) {
    private val keyDirectory = File(context.noBackupFilesDir, "adb")
    private val privateKeyFile = File(keyDirectory, "adbkey")
    private val publicKeyFile = File(keyDirectory, "adbkey.pub")

    @Synchronized
    fun loadOrCreate(): AdbKeyPair {
        if (privateKeyFile.isFile && publicKeyFile.isFile) {
            runCatching { AdbKeyPair.read(privateKeyFile, publicKeyFile) }
                .getOrNull()
                ?.let { return it }
        }
        privateKeyFile.delete()
        publicKeyFile.delete()
        AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        privateKeyFile.setReadable(false, false)
        privateKeyFile.setWritable(false, false)
        check(privateKeyFile.setReadable(true, true)) { "Could not protect the ADB private key." }
        check(privateKeyFile.setWritable(true, true)) { "Could not protect the ADB private key." }
        return AdbKeyPair.read(privateKeyFile, publicKeyFile)
    }
}