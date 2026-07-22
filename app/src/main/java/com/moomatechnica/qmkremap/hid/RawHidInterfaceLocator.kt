package com.moomatechnica.qmkremap.hid

/**
 * QMKの`RAW_ENABLE`機能（`raw_hid.h`）が使うUsage Page/Usage。
 * usevia.app / VIA本体が`navigator.hid.requestDevice({filters:[{usagePage, usage}]})`で
 * 要求するのもこの組み合わせ固定（vial.rocks等の他サイトは対象外、context.md参照）。
 */
object QmkRawHidProtocol {
    const val USAGE_PAGE = 0xFF60
    const val USAGE = 0x61
}

fun HidTopLevelCollection.isQmkRawHidCollection(): Boolean =
    usagePage == QmkRawHidProtocol.USAGE_PAGE && usage == QmkRawHidProtocol.USAGE

/**
 * キーボード1台が複数のUSB HIDインターフェース（Boot Keyboard, Consumer Control,
 * QMK Raw HID等）を同時に持つ場合に、その中からRaw HIDインターフェースを特定する。
 *
 * `UsbHidTransport`（T2/T4で実装予定）が実デバイスから各インターフェース番号ごとに
 * `controlTransfer()`でレポートディスクリプタを取得したあと、ここに渡す想定。
 */
object RawHidInterfaceLocator {

    /**
     * @param descriptorsByInterfaceNumber インターフェース番号 -> そのインターフェースの
     *   HIDレポートディスクリプタ生バイト列
     * @return Raw HIDインターフェースのインターフェース番号。見つからなければnull
     */
    fun findRawHidInterfaceNumber(descriptorsByInterfaceNumber: Map<Int, ByteArray>): Int? {
        for ((interfaceNumber, descriptor) in descriptorsByInterfaceNumber) {
            val collections = HidReportDescriptorParser.parseTopLevelCollections(descriptor)
            if (collections.any { it.isQmkRawHidCollection() }) {
                return interfaceNumber
            }
        }
        return null
    }
}
