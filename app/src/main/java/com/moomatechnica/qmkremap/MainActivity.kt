package com.moomatechnica.qmkremap

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.moomatechnica.qmkremap.bridge.WebHidBridge
import com.moomatechnica.qmkremap.bridge.WebHidNativeMethods
import com.moomatechnica.qmkremap.bridge.WebHidRpcRouter
import com.moomatechnica.qmkusbhid.device.UsbHidDeviceInfo
import com.moomatechnica.qmkusbhid.usb.UsbHidTransport

/**
 * T8: usevia.appを表示し、読み込み前に`navigator.hid`ポリフィルを注入するWebView。
 *
 * USB HIDデバイスの許可（T2）・実通信（T4）・JS<->Kotlinブリッジ（T5/T6/T7）は
 * すべて[WebHidNativeMethods]/[WebHidBridge]に委譲する。
 */
private const val USEVIA_URL = "https://usevia.app/"
private const val USEVIA_ORIGIN = "https://usevia.app"
private const val TAG = "MainActivity"

/** T7: 許可済みHIDデバイスが複数あるときのシンプルな選択ダイアログの状態。 */
private class DevicePickerState {
    var candidates by mutableStateOf<List<UsbHidDeviceInfo>?>(null)
    var onChosen: ((UsbHidDeviceInfo?) -> Unit)? = null

    fun show(candidates: List<UsbHidDeviceInfo>, onChosen: (UsbHidDeviceInfo?) -> Unit) {
        this.candidates = candidates
        this.onChosen = onChosen
    }

    fun resolve(chosen: UsbHidDeviceInfo?) {
        onChosen?.invoke(chosen)
        candidates = null
        onChosen = null
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var usbHidTransport: UsbHidTransport
    private var usbPermissionReceiver: BroadcastReceiver? = null
    private val devicePickerState = DevicePickerState()

    // VIAの「Design tab」でのキーボード定義JSONアップロード(<input type=file>)に対応するため、
    // WebChromeClient.onShowFileChooserからAndroidのファイル選択画面を起動する。
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val callback = pendingFileChooserCallback
        pendingFileChooserCallback = null
        callback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }

    private fun launchFileChooser(callback: ValueCallback<Array<Uri>>) {
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = callback
        fileChooserLauncher.launch("application/json")
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbHidTransport = UsbHidTransport(this)

        lateinit var bridge: WebHidBridge
        val nativeMethods = WebHidNativeMethods(
            transport = usbHidTransport,
            onInputReport = { deviceId, reportId, data -> bridge.sendInputReportEvent(deviceId, reportId, data) },
            requestDeviceSelection = { candidates, onChosen ->
                runOnUiThread { devicePickerState.show(candidates, onChosen) }
            }
        )
        bridge = WebHidBridge(WebHidRpcRouter(nativeMethods.asRouterMethods()))

        usbPermissionReceiver = usbHidTransport.registerPermissionReceiver { device, granted ->
            Log.i(TAG, "USB permission result: device=${device.deviceName} granted=$granted")
        }
        for (device in usbHidTransport.connectedDevices()) {
            if (!usbHidTransport.hasPermission(device)) {
                usbHidTransport.requestPermission(device)
            }
        }

        setContent {
            MaterialExpressiveTheme(motionScheme = MotionScheme.expressive()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UseviaWebView(bridge = bridge, onShowFileChooser = ::launchFileChooser)
                }
                DevicePickerDialog(devicePickerState)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbPermissionReceiver?.let { unregisterReceiver(it) }
    }
}

@Composable
private fun DevicePickerDialog(state: DevicePickerState) {
    val candidates = state.candidates ?: return
    AlertDialog(
        onDismissRequest = { state.resolve(null) },
        title = { Text("キーボードを選択") },
        text = {
            androidx.compose.foundation.layout.Column {
                candidates.forEach { device ->
                    TextButton(onClick = { state.resolve(device) }) {
                        Text(device.productName ?: "USB HID device (${device.vendorId}:${device.productId})")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { state.resolve(null) }) { Text("キャンセル") }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun UseviaWebView(
    bridge: WebHidBridge,
    onShowFileChooser: (ValueCallback<Array<Uri>>) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView.setWebContentsDebuggingEnabled(true)
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        view: WebView,
                        filePathCallback: ValueCallback<Array<Uri>>,
                        fileChooserParams: FileChooserParams
                    ): Boolean {
                        onShowFileChooser(filePathCallback)
                        return true
                    }
                }

                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(
                        this,
                        WebHidBridge.JS_OBJECT_NAME,
                        setOf(USEVIA_ORIGIN),
                        bridge
                    )
                } else {
                    Log.e(TAG, "WEB_MESSAGE_LISTENER not supported by this WebView")
                }

                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    val polyfillJs = context.assets.open("webhid-polyfill.js").bufferedReader().use { it.readText() }
                    WebViewCompat.addDocumentStartJavaScript(this, polyfillJs, setOf(USEVIA_ORIGIN))
                } else {
                    Log.e(TAG, "DOCUMENT_START_SCRIPT not supported by this WebView")
                }

                loadUrl(USEVIA_URL)
            }
        }
    )
}
