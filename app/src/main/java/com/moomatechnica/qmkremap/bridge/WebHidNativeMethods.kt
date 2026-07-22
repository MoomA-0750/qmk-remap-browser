package com.moomatechnica.qmkremap.bridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import com.moomatechnica.qmkusbhid.device.DeviceSelectionPolicy
import com.moomatechnica.qmkusbhid.device.UsbHidDeviceInfo
import com.moomatechnica.qmkusbhid.hid.RawHidInterfaceLocator
import com.moomatechnica.qmkusbhid.usb.UsbHidReportIo
import com.moomatechnica.qmkusbhid.usb.UsbHidTransport
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * T5/T6/T7/T8: `webhid-polyfill.js`からのRPCを実際に処理するメソッド群。
 * T2〜T4で作った部品（`UsbHidTransport`/`UsbHidReportIo`/`RawHidInterfaceLocator`/
 * `DeviceSelectionPolicy`）をここで結線し、`WebHidRpcRouter`に登録する
 * `WebHidMethodHandler`として提供する。
 *
 * QMK Raw HIDは`usagePage=0xFF60`固定（spec.md/plan.md参照）で、V1では
 * `collections`もこの固定値のみ返す（usevia.appの`requestDevice`フィルタが
 * 要求するのもこの組み合わせだけのため、フィルタの汎用マッチングは実装しない）。
 */
class WebHidNativeMethods(
    private val transport: UsbHidTransport,
    private val onInputReport: (deviceId: Int, reportId: Int, data: ByteArray) -> Unit,
    private val requestDeviceSelection: (candidates: List<UsbHidDeviceInfo>, onChosen: (UsbHidDeviceInfo?) -> Unit) -> Unit
) {

    private data class OpenDeviceState(
        val connection: UsbDeviceConnection,
        val usbInterface: UsbInterface,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint,
        val stopPolling: AtomicBoolean
    )

    private val openDevices = mutableMapOf<Int, OpenDeviceState>()

    private fun permittedDeviceInfos(): List<UsbHidDeviceInfo> =
        transport.connectedDevices()
            .filter { transport.hasPermission(it) && it.hasHidInterface() }
            .map { it.toInfo() }

    /**
     * USBハブ自身など、HIDクラスのインターフェースを一つも持たないデバイスを除外するための判定。
     * T7で「許可済みUSBデバイス全部」を候補にしていたため、USB-Cハブ経由で接続した際に
     * ハブ自体がデバイス選択ダイアログの候補に混ざってしまう不具合があった（tasks.md参照）。
     * `UsbInterface.interfaceClass`はUSB記述子から取得できる値でUSB通信不要のため、
     * この絞り込み自体に`claimInterface`やレポートディスクリプタ取得は要らない。
     */
    private fun UsbDevice.hasHidInterface(): Boolean =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HID }

    private fun UsbDevice.toInfo() = UsbHidDeviceInfo(
        deviceId = deviceId,
        vendorId = vendorId,
        productId = productId,
        productName = productName
    )

    private fun descriptorJson(info: UsbHidDeviceInfo): Map<String, Any?> = mapOf(
        "deviceId" to info.deviceId,
        "vendorId" to info.vendorId,
        "productId" to info.productId,
        "productName" to (info.productName ?: ""),
        "collections" to listOf(
            mapOf("usagePage" to 0xFF60, "usage" to 0x61)
        )
    )

    val requestDevice = WebHidMethodHandler { _, respond ->
        val candidates = permittedDeviceInfos()
        when (val decision = DeviceSelectionPolicy.decide(candidates)) {
            is DeviceSelectionPolicy.Decision.ReturnEmpty ->
                respond(Result.success(emptyList<Map<String, Any?>>()))

            is DeviceSelectionPolicy.Decision.AutoSelect ->
                respond(Result.success(listOf(descriptorJson(decision.device))))

            is DeviceSelectionPolicy.Decision.ShowPicker ->
                requestDeviceSelection(decision.devices) { chosen ->
                    respond(Result.success(if (chosen != null) listOf(descriptorJson(chosen)) else emptyList()))
                }
        }
    }

    val getDevices = WebHidMethodHandler { _, respond ->
        respond(Result.success(permittedDeviceInfos().map { descriptorJson(it) }))
    }

    val open = WebHidMethodHandler { params, respond ->
        val deviceId = (params["deviceId"] as? Number)?.toInt()
        val device = transport.connectedDevices().firstOrNull { it.deviceId == deviceId }
        if (device == null) {
            respond(Result.failure(IllegalArgumentException("device not found: $deviceId")))
            return@WebHidMethodHandler
        }
        try {
            openDeviceInternal(device)
            respond(Result.success(null))
        } catch (e: Exception) {
            Log.e(TAG, "open failed", e)
            respond(Result.failure(e))
        }
    }

    private fun openDeviceInternal(device: UsbDevice) {
        if (openDevices.containsKey(device.deviceId)) return
        val connection = transport.openConnection(device) ?: error("openConnection failed")

        // T4で判明した通り、先に全インターフェースをclaimしてからでないとGET_DESCRIPTORが失敗する。
        for (i in 0 until device.interfaceCount) {
            UsbHidReportIo.claimInterface(connection, device.getInterface(i))
        }

        val descriptors = UsbHidReportIo.fetchAllReportDescriptors(device, connection)
        val rawHidInterfaceNumber = RawHidInterfaceLocator.findRawHidInterfaceNumber(descriptors)
            ?: error("Raw HID interface not found among ${descriptors.keys}")
        val usbInterface = UsbHidReportIo.findInterface(device, rawHidInterfaceNumber)
            ?: error("UsbInterface not found for number=$rawHidInterfaceNumber")
        val outEndpoint = UsbHidReportIo.outEndpoint(usbInterface) ?: error("no OUT endpoint")
        val inEndpoint = UsbHidReportIo.inEndpoint(usbInterface) ?: error("no IN endpoint")

        val stopPolling = AtomicBoolean(false)
        openDevices[device.deviceId] = OpenDeviceState(connection, usbInterface, inEndpoint, outEndpoint, stopPolling)

        thread(name = "hid-poll-${device.deviceId}") {
            while (!stopPolling.get()) {
                val data = UsbHidReportIo.receiveReport(connection, inEndpoint, inEndpoint.maxPacketSize, timeoutMs = 500)
                if (data != null) {
                    onInputReport(device.deviceId, 0, data)
                }
            }
        }
    }

    val close = WebHidMethodHandler { params, respond ->
        val deviceId = (params["deviceId"] as? Number)?.toInt()
        val state = openDevices.remove(deviceId)
        if (state != null) {
            state.stopPolling.set(true)
            try {
                state.connection.releaseInterface(state.usbInterface)
                state.connection.close()
            } catch (e: Exception) {
                Log.w(TAG, "close: cleanup error", e)
            }
        }
        respond(Result.success(null))
    }

    val sendReport = WebHidMethodHandler { params, respond ->
        try {
            val deviceId = (params["deviceId"] as Number).toInt()
            val reportId = (params["reportId"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val dataList = params["data"] as? List<Any?> ?: emptyList()
            val bytes = dataList.map { (it as Number).toInt().toByte() }

            val state = openDevices[deviceId] ?: error("device not open: $deviceId")
            val report = ByteArray(state.outEndpoint.maxPacketSize)
            var offset = 0
            if (reportId != 0) {
                report[0] = reportId.toByte()
                offset = 1
            }
            for (i in bytes.indices) {
                if (offset + i >= report.size) break
                report[offset + i] = bytes[i]
            }
            val sent = UsbHidReportIo.sendReport(state.connection, state.outEndpoint, report)
            if (sent < 0) error("sendReport failed (bulkTransfer returned $sent)")
            respond(Result.success(null))
        } catch (e: Exception) {
            Log.e(TAG, "sendReport failed", e)
            respond(Result.failure(e))
        }
    }

    // QMK Raw HIDはOutput/Inputレポート(sendReport/oninputreport)のみを使い、
    // Feature Reportは使わないため、V1では未対応として明示的にエラーを返す。
    val sendFeatureReport = WebHidMethodHandler { _, respond ->
        respond(Result.failure(UnsupportedOperationException("sendFeatureReport is not supported in V1")))
    }

    val receiveFeatureReport = WebHidMethodHandler { _, respond ->
        respond(Result.failure(UnsupportedOperationException("receiveFeatureReport is not supported in V1")))
    }

    fun asRouterMethods(): Map<String, WebHidMethodHandler> = mapOf(
        "requestDevice" to requestDevice,
        "getDevices" to getDevices,
        "open" to open,
        "close" to close,
        "sendReport" to sendReport,
        "sendFeatureReport" to sendFeatureReport,
        "receiveFeatureReport" to receiveFeatureReport
    )

    companion object {
        private const val TAG = "WebHidNativeMethods"
    }
}
