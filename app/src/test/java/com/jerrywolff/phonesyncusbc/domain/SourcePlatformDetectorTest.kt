package com.jerrywolff.phonesyncusbc.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SourcePlatformDetectorTest {
    @Test
    fun `detects a Google phone as Android`() {
        val source = SourcePlatformDetector.detect(
            UsbDescriptor(
                vendorId = 0x18D1,
                productId = 0x4EE1,
                productName = "Pixel 9",
                transports = setOf(UsbTransport.MTP),
                physicalConnection = PhysicalConnection.USB_C,
            ),
        )

        assertEquals(SourcePlatform.ANDROID, source.platform)
        assertEquals(SourceFamily.GOOGLE_PHONE, source.family)
        assertEquals(PhysicalConnection.USB_C, source.physicalConnection)
    }

    @Test
    fun `detects an iPhone from Apple's vendor id`() {
        val source = SourcePlatformDetector.detect(
            UsbDescriptor(vendorId = 0x05AC, productId = 0x12A8, productName = "iPhone"),
        )

        assertEquals(SourcePlatform.IOS, source.platform)
        assertEquals(SourceFamily.IPHONE, source.family)
    }

    @Test
    fun `detects a Lumia as Windows Phone`() {
        val source = SourcePlatformDetector.detect(
            UsbDescriptor(
                vendorId = 0x045E,
                productId = 0x0A00,
                productName = "Lumia Windows Phone",
                transports = setOf(UsbTransport.MTP),
            ),
        )

        assertEquals(SourcePlatform.WINDOWS_PHONE, source.platform)
    }

    @Test
    fun `detects a known Android vendor using MTP`() {
        val source = SourcePlatformDetector.detect(
            UsbDescriptor(
                vendorId = 0x04E8,
                productId = 0x6860,
                productName = "Mobile device",
                transports = setOf(UsbTransport.MTP),
            ),
        )

        assertEquals(SourcePlatform.ANDROID, source.platform)
        assertEquals(SourceFamily.ANDROID_PHONE, source.family)
    }

    @Test
    fun `detects a generic Android tablet as Android`() {
        val source = SourcePlatformDetector.detect(
            UsbDescriptor(
                vendorId = 0x18D1,
                productId = 0x4EE7,
                productName = "Android Tablet",
                transports = setOf(UsbTransport.MTP),
            ),
        )

        assertEquals(SourcePlatform.ANDROID, source.platform)
        assertEquals(SourceFamily.GOOGLE_PHONE, source.family)
    }

    @Test
    fun `does not mislabel a generic MTP camera as Android`() {
        val source = SourcePlatformDetector.detect(
            UsbDescriptor(
                vendorId = 0x1234,
                productId = 0x5678,
                productName = "Digital Camera",
                transports = setOf(UsbTransport.MTP),
            ),
        )

        assertEquals(SourcePlatform.UNKNOWN, source.platform)
        assertEquals(SourceFamily.UNKNOWN, source.family)
    }
}