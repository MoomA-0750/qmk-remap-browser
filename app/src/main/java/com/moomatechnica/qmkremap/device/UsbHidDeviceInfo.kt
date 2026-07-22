package com.moomatechnica.qmkremap.device

/**
 * `requestDevice()`のデバイス選択判断に必要な最小限の情報。
 * 実際の`UsbDevice`（`UsbManager`由来、T2で実装予定）から変換して使う想定。
 */
data class UsbHidDeviceInfo(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val productName: String?
)
