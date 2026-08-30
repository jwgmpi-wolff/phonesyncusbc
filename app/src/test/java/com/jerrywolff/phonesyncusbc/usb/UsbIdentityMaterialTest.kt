package com.jerrywolff.phonesyncusbc.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UsbIdentityMaterialTest {
    @Test
    fun `descriptor identity is stable across volatile Android device paths`() {
        val first = stableUsbIdentityMaterial(
            platform = "IOS",
            vendorId = 0x05AC,
            productId = 0x12A8,
            manufacturerName = "Apple Inc.",
            productName = "iPhone",
            transportSignatures = listOf("6:1:1", "255:0:0"),
        )
        val second = stableUsbIdentityMaterial(
            platform = "IOS",
            vendorId = 0x05AC,
            productId = 0x12A8,
            manufacturerName = "APPLE INC.",
            productName = "iPhone",
            transportSignatures = listOf("255:0:0", "6:1:1"),
        )

        assertEquals(first, second)
        assertFalse(first.contains("deviceId", ignoreCase = true))
        assertFalse(first.contains("/dev/bus/usb"))
    }
}