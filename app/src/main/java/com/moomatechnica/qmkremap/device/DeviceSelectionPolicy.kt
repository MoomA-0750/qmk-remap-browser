package com.moomatechnica.qmkremap.device

/**
 * `navigator.hid.requestDevice()`が呼ばれた際、許可済みUSB HIDデバイスの台数に応じて
 * どう振る舞うかを決める純粋なロジック（spec.mdの「デバイス選択（最小限UI）」の
 * 受け入れ条件そのもの）。
 *
 * - 0台 -> 空配列を返す（標準WebHID仕様と同様の挙動）
 * - 1台 -> 選択ダイアログを出さず自動選択
 * - 複数台 -> シンプルな一覧UIを表示し、ユーザーの選択結果を返す
 *
 * 実際にリスト表示してユーザー入力を待つUI部分（Compose）は本ロジックの外側の責務。
 * ここでは「どの分岐を取るべきか」だけを決定する。
 */
object DeviceSelectionPolicy {

    sealed class Decision {
        /** 許可済みデバイスが0台。JS側の`requestDevice()`には空配列を返す。 */
        data object ReturnEmpty : Decision()

        /** 許可済みデバイスが1台のみ。選択ダイアログなしでこのデバイスを自動選択する。 */
        data class AutoSelect(val device: UsbHidDeviceInfo) : Decision()

        /** 許可済みデバイスが複数。一覧UIを表示してユーザーに選ばせる。 */
        data class ShowPicker(val devices: List<UsbHidDeviceInfo>) : Decision()
    }

    fun decide(permittedDevices: List<UsbHidDeviceInfo>): Decision = when (permittedDevices.size) {
        0 -> Decision.ReturnEmpty
        1 -> Decision.AutoSelect(permittedDevices.single())
        else -> Decision.ShowPicker(permittedDevices)
    }
}
