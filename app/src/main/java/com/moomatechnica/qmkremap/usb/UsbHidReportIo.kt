package com.moomatechnica.qmkremap.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

/**
 * T4: 実USBデバイスに対する低レベルHID通信（GET_DESCRIPTOR・claimInterface・
 * interrupt転送でのレポート送受信）。plan.mdの`UsbHidTransport`のうち、T2の
 * デバイス列挙・パーミッション部分と対になる実通信部分。
 */
object UsbHidReportIo {

    private const val TAG = "UsbHidReportIo"

    private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
    private const val DESCRIPTOR_TYPE_HID_REPORT = 0x22
    private const val CONTROL_TRANSFER_TIMEOUT_MS = 5000
    private const val REPORT_DESCRIPTOR_BUFFER_SIZE = 4096

    /**
     * 指定したインターフェース番号のHIDレポートディスクリプタを
     * `GET_DESCRIPTOR(type=0x22)`のcontrol transferで取得する。
     * まだ`claimInterface`していない状態でも呼び出せる（標準control transferのため）。
     */
    fun fetchReportDescriptor(connection: UsbDeviceConnection, interfaceNumber: Int): ByteArray? {
        val buffer = ByteArray(REPORT_DESCRIPTOR_BUFFER_SIZE)
        val requestType = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD or USB_RECIPIENT_INTERFACE
        val length = connection.controlTransfer(
            requestType,
            USB_REQUEST_GET_DESCRIPTOR,
            DESCRIPTOR_TYPE_HID_REPORT shl 8,
            interfaceNumber,
            buffer,
            buffer.size,
            CONTROL_TRANSFER_TIMEOUT_MS
        )
        if (length <= 0) {
            Log.w(TAG, "fetchReportDescriptor: interface=$interfaceNumber failed length=$length")
            return null
        }
        return buffer.copyOf(length)
    }

    /** 全インターフェース分のレポートディスクリプタを取得する（`RawHidInterfaceLocator`への入力用）。 */
    fun fetchAllReportDescriptors(device: UsbDevice, connection: UsbDeviceConnection): Map<Int, ByteArray> {
        val result = mutableMapOf<Int, ByteArray>()
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            val descriptor = fetchReportDescriptor(connection, usbInterface.id)
            if (descriptor != null) {
                result[usbInterface.id] = descriptor
            }
        }
        return result
    }

    fun findInterface(device: UsbDevice, interfaceNumber: Int): UsbInterface? =
        (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == interfaceNumber }

    /** `force=true`でカーネルの`usbhid`ドライバから当該インターフェースをデタッチしてclaimする。 */
    fun claimInterface(connection: UsbDeviceConnection, usbInterface: UsbInterface): Boolean =
        connection.claimInterface(usbInterface, true)

    private fun endpointWithDirection(usbInterface: UsbInterface, direction: Int): UsbEndpoint? =
        (0 until usbInterface.endpointCount)
            .map { usbInterface.getEndpoint(it) }
            .firstOrNull { it.direction == direction }

    fun outEndpoint(usbInterface: UsbInterface): UsbEndpoint? =
        endpointWithDirection(usbInterface, UsbConstants.USB_DIR_OUT)

    fun inEndpoint(usbInterface: UsbInterface): UsbEndpoint? =
        endpointWithDirection(usbInterface, UsbConstants.USB_DIR_IN)

    /**
     * interrupt転送でレポートを送信する。AndroidのUSB Host APIには専用の
     * interruptTransferメソッドはなく、`bulkTransfer`がinterruptエンドポイントにも
     * そのまま使える（plan.md「HID通信」参照）。
     */
    fun sendReport(connection: UsbDeviceConnection, endpoint: UsbEndpoint, report: ByteArray, timeoutMs: Int = 2000): Int =
        connection.bulkTransfer(endpoint, report, report.size, timeoutMs)

    fun receiveReport(connection: UsbDeviceConnection, endpoint: UsbEndpoint, bufferSize: Int, timeoutMs: Int = 2000): ByteArray? {
        val buffer = ByteArray(bufferSize)
        val length = connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        return if (length > 0) buffer.copyOf(length) else null
    }

    // UsbConstants.USB_RECIPIENT_INTERFACEはSDKに定数が無いため直接定義(値は0x01、USB 2.0仕様 Table 9-2)
    private const val USB_RECIPIENT_INTERFACE = 0x01
}
