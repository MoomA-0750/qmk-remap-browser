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
        }

        val devices = usbHidTransport.connectedDevices()
        Log.i(TAG, "T2: connected USB devices = ${devices.size}")
        for (device in devices) {
            Log.i(TAG, "T2: device name=${device.deviceName} vid=${device.vendorId} pid=${device.productId} product=${device.productName} interfaceCount=${device.interfaceCount} hasPermission=${usbHidTransport.hasPermission(device)}")
            if (!usbHidTransport.hasPermission(device)) {
                usbHidTransport.requestPermission(device)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbPermissionReceiver?.let { unregisterReceiver(it) }
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
