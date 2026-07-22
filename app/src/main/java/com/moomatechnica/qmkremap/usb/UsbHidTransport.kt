package com.moomatechnica.qmkremap.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * T2: USB HIDデバイスの列挙とパーミッション要求。
 *
 * `claimInterface`・レポートディスクリプタ取得・interrupt転送でのHID通信はT4で追加する
 * （plan.mdの`UsbHidTransport`はT2/T4を合わせた最終形）。
 */
class UsbHidTransport(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun connectedDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun openConnection(device: UsbDevice): UsbDeviceConnection? = usbManager.openDevice(device)

    fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        Log.i(TAG, "Requesting USB permission for device=${device.deviceName} vid=${device.vendorId} pid=${device.productId}")
        usbManager.requestPermission(device, pendingIntent)
    }

    /**
     * パーミッションダイアログの結果を受け取るレシーバーを登録する。
     * 呼び出し側（Activity）が`onDestroy`等で`context.unregisterReceiver`する責務を持つ。
     */
    fun registerPermissionReceiver(onResult: (device: UsbDevice, granted: Boolean) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val device = intent.getUsbDeviceExtraCompat()
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.i(TAG, "USB permission result: device=${device?.deviceName} vid=${device?.vendorId} pid=${device?.productId} granted=$granted")
                if (device != null) onResult(device, granted)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        return receiver
    }

    private fun Intent.getUsbDeviceExtraCompat(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "UsbHidTransport"
        private const val ACTION_USB_PERMISSION = "com.moomatechnica.qmkremap.USB_PERMISSION"
    }
}
