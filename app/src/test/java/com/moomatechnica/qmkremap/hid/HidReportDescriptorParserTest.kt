package com.moomatechnica.qmkremap.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Int]の可変長引数からHIDディスクリプタの[ByteArray]を組み立てるヘルパー。 */
private fun descriptorBytes(vararg values: Int): ByteArray =
    ByteArray(values.size) { values[it].toByte() }

/**
 * QMKの`RAW_ENABLE`機能が生成するRaw HIDレポートディスクリプタ（`raw_hid.h`/
 * `usb_descriptor.c`のUsage Page 0xFF60, Usage 0x61固定の実装を再現したもの）。
 * RAW_EPSIZEは32（QMKのデフォルト）。
 */
private val QMK_RAW_HID_DESCRIPTOR = descriptorBytes(
    0x06, 0x60, 0xFF, // Usage Page (Vendor Defined 0xFF60)
    0x09, 0x61,       // Usage (Vendor Usage 0x61)
    0xA1, 0x01,       // Collection (Application)
    0x75, 0x08,       //   Report Size (8)
    0x95, 0x20,       //   Report Count (32 = RAW_EPSIZE)
    0x15, 0x00,       //   Logical Minimum (0)
    0x26, 0xFF, 0x00, //   Logical Maximum (255)
    0x09, 0x62,       //   Usage (Vendor Usage 0x62, Input)
    0x81, 0x02,       //   Input (Data, Var, Abs)
    0x95, 0x20,       //   Report Count (32 = RAW_EPSIZE)
    0x09, 0x63,       //   Usage (Vendor Usage 0x63, Output)
    0x91, 0x02,       //   Output (Data, Var, Abs)
    0xC0              // End Collection
)

/** Boot Protocol Keyboardの標準ディスクリプタ（USB HIDUsage Tables準拠の一般的な形）。 */
private val BOOT_KEYBOARD_DESCRIPTOR = descriptorBytes(
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x06,       // Usage (Keyboard)
    0xA1, 0x01,       // Collection (Application)
    0x05, 0x07,       //   Usage Page (Keyboard/Keypad)
    0x19, 0xE0,       //   Usage Minimum (0xE0)
    0x29, 0xE7,       //   Usage Maximum (0xE7)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x01,       //   Logical Maximum (1)
    0x75, 0x01,       //   Report Size (1)
    0x95, 0x08,       //   Report Count (8)
    0x81, 0x02,       //   Input (Data, Var, Abs) ; Modifier byte
    0x95, 0x01,       //   Report Count (1)
    0x75, 0x08,       //   Report Size (8)
    0x81, 0x01,       //   Input (Const) ; Reserved byte
    0x95, 0x06,       //   Report Count (6)
    0x75, 0x08,       //   Report Size (8)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x65,       //   Logical Maximum (101)
    0x05, 0x07,       //   Usage Page (Keyboard/Keypad)
    0x19, 0x00,       //   Usage Minimum (0)
    0x29, 0x65,       //   Usage Maximum (101)
    0x81, 0x00,       //   Input (Data, Array)
    0xC0              // End Collection
)

/** Consumer Control（メディアキー）インターフェースの簡略ディスクリプタ。 */
private val CONSUMER_CONTROL_DESCRIPTOR = descriptorBytes(
    0x05, 0x0C,       // Usage Page (Consumer)
    0x09, 0x01,       // Usage (Consumer Control)
    0xA1, 0x01,       // Collection (Application)
    0x15, 0x00,       //   Logical Minimum (0)
    0x25, 0x01,       //   Logical Maximum (1)
    0x75, 0x01,       //   Report Size (1)
    0x95, 0x10,       //   Report Count (16)
    0x09, 0xB5,       //   Usage (Scan Next Track)
    0x09, 0xB6,       //   Usage (Scan Previous Track)
    0x81, 0x02,       //   Input (Data, Var, Abs)
    0xC0              // End Collection
)

/**
 * ネストされたCollectionを含むディスクリプタ（例: キーボードのApplication Collectionの
 * 中に、LED出力用のPhysical CollectionがネストされるケースをQMKの一部キーボード実装に
 * 倣って再現）。トップレベルCollectionが1個だけ検出されることを確認するための入力。
 */
private val KEYBOARD_WITH_NESTED_LED_COLLECTION = descriptorBytes(
    0x05, 0x01,       // Usage Page (Generic Desktop)
    0x09, 0x06,       // Usage (Keyboard)
    0xA1, 0x01,       // Collection (Application)  <- トップレベル(depth 0->1)
    0x05, 0x08,       //   Usage Page (LEDs)
    0x09, 0x01,       //   Usage (Num Lock)
    0xA1, 0x02,       //   Collection (Physical)    <- ネスト(depth 1->2、記録対象外)
    0x75, 0x01,       //     Report Size (1)
    0x95, 0x01,       //     Report Count (1)
    0x91, 0x02,       //     Output (Data, Var, Abs)
    0xC0,             //   End Collection (depth 2->1)
    0xC0              // End Collection (depth 1->0)
)

class HidReportDescriptorParserTest {

    @Test
    fun `QMK Raw HID descriptor is detected as usagePage 0xFF60 usage 0x61`() {
        val collections = HidReportDescriptorParser.parseTopLevelCollections(QMK_RAW_HID_DESCRIPTOR)

        assertEquals(1, collections.size)
        val collection = collections.single()
        assertEquals(0xFF60, collection.usagePage)
        assertEquals(0x61, collection.usage)
        assertEquals(0x01, collection.collectionType) // Application
        assertTrue(collection.isQmkRawHidCollection())
    }

    @Test
    fun `Boot Keyboard descriptor is not mistaken for Raw HID`() {
        val collections = HidReportDescriptorParser.parseTopLevelCollections(BOOT_KEYBOARD_DESCRIPTOR)

        assertEquals(1, collections.size)
        val collection = collections.single()
        assertEquals(0x01, collection.usagePage) // Generic Desktop
        assertEquals(0x06, collection.usage) // Keyboard
        assertFalse(collection.isQmkRawHidCollection())
    }

    @Test
    fun `Consumer Control descriptor is not mistaken for Raw HID`() {
        val collections = HidReportDescriptorParser.parseTopLevelCollections(CONSUMER_CONTROL_DESCRIPTOR)

        assertEquals(1, collections.size)
        assertFalse(collections.single().isQmkRawHidCollection())
    }

    @Test
    fun `nested collections are not counted as additional top-level collections`() {
        val collections = HidReportDescriptorParser.parseTopLevelCollections(KEYBOARD_WITH_NESTED_LED_COLLECTION)

        // ネストされたLEDのPhysical Collectionを含めて2個ではなく、
        // トップレベルのApplication Collection 1個だけが検出されるべき
        assertEquals(1, collections.size)
        assertEquals(0x01, collections.single().usagePage)
        assertEquals(0x06, collections.single().usage)
    }

    @Test
    fun `findRawHidInterfaceNumber picks the raw hid interface among multiple keyboard interfaces`() {
        val descriptorsByInterfaceNumber = mapOf(
            0 to BOOT_KEYBOARD_DESCRIPTOR,
            1 to CONSUMER_CONTROL_DESCRIPTOR,
            2 to QMK_RAW_HID_DESCRIPTOR
        )

        val result = RawHidInterfaceLocator.findRawHidInterfaceNumber(descriptorsByInterfaceNumber)

        assertEquals(2, result)
    }

    @Test
    fun `findRawHidInterfaceNumber returns null when no interface matches`() {
        val descriptorsByInterfaceNumber = mapOf(
            0 to BOOT_KEYBOARD_DESCRIPTOR,
            1 to CONSUMER_CONTROL_DESCRIPTOR
        )

        val result = RawHidInterfaceLocator.findRawHidInterfaceNumber(descriptorsByInterfaceNumber)

        assertNull(result)
    }
}
