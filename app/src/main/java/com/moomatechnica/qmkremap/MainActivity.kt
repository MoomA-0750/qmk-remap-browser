package com.moomatechnica.qmkremap

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.hardware.usb.UsbDevice
import com.moomatechnica.qmkremap.hid.RawHidInterfaceLocator
import com.moomatechnica.qmkremap.usb.UsbHidReportIo
import com.moomatechnica.qmkremap.usb.UsbHidTransport

/**
 * T1: 最小構成。WebViewでusevia.appを表示するだけ。
 *
 * この時点ではnavigator.hidポリフィルを注入していないため、usevia.app側は
 * 「WebHID未対応ブラウザ」として振る舞う（VIA側デバイス接続不可）のが期待挙動。
 * ポリフィル注入・USB Host APIとの結線はT2以降のタスクで追加する。
 */
private const val USEVIA_URL = "https://usevia.app/"
private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private lateinit var usbHidTransport: UsbHidTransport
    private var usbPermissionReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UseviaWebView()
                }
            }
        }

        // T2: USB HIDデバイスの列挙・パーミッション要求（動作確認用の最小配線）。
        usbHidTransport = UsbHidTransport(this)
        usbPermissionReceiver = usbHidTransport.registerPermissionReceiver { device, granted ->
            Log.i(TAG, "T2 permission callback: device=${device.deviceName} granted=$granted hasPermission=${usbHidTransport.hasPermission(device)}")
            if (granted) {
                runT4RawHidProbe(device)
            }
        }

        val devices = usbHidTransport.connectedDevices()
        Log.i(TAG, "T2: connected USB devices = ${devices.size}")
        for (device in devices) {
            Log.i(TAG, "T2: device name=${device.deviceName} vid=${device.vendorId} pid=${device.productId} product=${device.productName} interfaceCount=${device.interfaceCount} hasPermission=${usbHidTransport.hasPermission(device)}")
            if (usbHidTransport.hasPermission(device)) {
                runT4RawHidProbe(device)
            } else {
                usbHidTransport.requestPermission(device)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbPermissionReceiver?.let { unregisterReceiver(it) }
    }

    /**
     * T4動作確認用: レポートディスクリプタ取得→Raw HIDインターフェース特定→claim→
     * QMK/VIAの`id_get_protocol_version`(0x01)コマンド送受信を一気通貫で試す。
     */
    private fun runT4RawHidProbe(device: UsbDevice) {
        val connection = usbHidTransport.openConnection(device)
        if (connection == null) {
            Log.e(TAG, "T4: openConnection failed")
            return
        }

        // 先に全インターフェースをforce=trueでclaimし、カーネルのusbhidドライバをデタッチしてから
        // GET_DESCRIPTORを投げる（クレーム前だと control transfer が失敗する現象を切り分け中）。
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val claimResult = UsbHidReportIo.claimInterface(connection, iface)
            Log.i(TAG, "T4: pre-claim interface=${iface.id} result=$claimResult")
        }

        val descriptors = UsbHidReportIo.fetchAllReportDescriptors(device, connection)
        Log.i(TAG, "T4: fetched report descriptors for interfaces=${descriptors.keys}")

        val rawHidInterfaceNumber = RawHidInterfaceLocator.findRawHidInterfaceNumber(descriptors)
        if (rawHidInterfaceNumber == null) {
            Log.e(TAG, "T4: Raw HID interface not found among ${descriptors.keys}")
            return
        }
        Log.i(TAG, "T4: Raw HID interface = $rawHidInterfaceNumber")

        val usbInterface = UsbHidReportIo.findInterface(device, rawHidInterfaceNumber)
        if (usbInterface == null) {
            Log.e(TAG, "T4: UsbInterface not found for number=$rawHidInterfaceNumber")
            return
        }

        val claimed = UsbHidReportIo.claimInterface(connection, usbInterface)
        Log.i(TAG, "T4: claimInterface(force=true) result=$claimed")
        if (!claimed) return

        val outEndpoint = UsbHidReportIo.outEndpoint(usbInterface)
        val inEndpoint = UsbHidReportIo.inEndpoint(usbInterface)
        if (outEndpoint == null || inEndpoint == null) {
            Log.e(TAG, "T4: missing endpoints out=$outEndpoint in=$inEndpoint")
            return
        }

        // QMK/VIAプロトコル: id_get_protocol_version = 0x01。RAW_EPSIZE分をゼロ埋めして送る。
        val report = ByteArray(outEndpoint.maxPacketSize)
        report[0] = 0x01
        val sent = UsbHidReportIo.sendReport(connection, outEndpoint, report)
        Log.i(TAG, "T4: sendReport(id_get_protocol_version) bytesSent=$sent")

        val response = UsbHidReportIo.receiveReport(connection, inEndpoint, inEndpoint.maxPacketSize)
        Log.i(TAG, "T4: response=${response?.joinToString(" ") { "%02x".format(it) }}")
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun UseviaWebView(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl(USEVIA_URL)
            }
        }
    )
}
