package com.moomatechnica.qmkremap.hid

/**
 * 1個のトップレベルCollection(Main)アイテムから抽出したUsage Page/Usage。
 *
 * USB HIDのCollectionはネスト可能（例: キーボードのApplication Collectionの中に
 * LED出力用のPhysical Collectionがネストされる等）だが、WebHIDの`requestDevice`の
 * `filters`（および実際のOSのHIDサブシステム）が識別に使うのは常に
 * 「そのインターフェースの最初のトップレベルCollection」のUsage Page/Usageなので、
 * ネストされた内側のCollectionはここには含めない。
 */
data class HidTopLevelCollection(
    val usagePage: Int,
    val usage: Int,
    val collectionType: Int
)

/**
 * USB HIDレポートディスクリプタ（GET_DESCRIPTOR type=0x22で取得する生バイト列）から
 * トップレベルCollectionのUsage Page/Usageを抽出するパーサー。
 *
 * QMKのRaw HIDインターフェース（`usagePage=0xFF60`, `usage=0x61`）を、キーボードが
 * 同時に持つ他のHIDインターフェース（Boot Keyboard, Consumer Control等）から
 * 判別することだけを目的にしており、汎用HIDディスクリプタパーサーとしての完全性
 * （Push/Pop、Report ID分岐、Delimiter等の完全サポート）は狙っていない
 * （plan.md「主要な技術的リスク」参照）。
 */
object HidReportDescriptorParser {

    private const val ITEM_TYPE_MAIN = 0
    private const val ITEM_TYPE_GLOBAL = 1
    private const val ITEM_TYPE_LOCAL = 2

    private const val GLOBAL_TAG_USAGE_PAGE = 0x0
    private const val LOCAL_TAG_USAGE = 0x0
    private const val MAIN_TAG_COLLECTION = 0xA
    private const val MAIN_TAG_END_COLLECTION = 0xC

    private const val LONG_ITEM_PREFIX = 0xFE

    fun parseTopLevelCollections(descriptor: ByteArray): List<HidTopLevelCollection> {
        val collections = mutableListOf<HidTopLevelCollection>()

        var currentUsagePage = 0
        val pendingUsages = mutableListOf<Int>()
        var collectionDepth = 0

        var i = 0
        while (i < descriptor.size) {
            val prefix = descriptor[i].toInt() and 0xFF

            if (prefix == LONG_ITEM_PREFIX) {
                // Long item (USB HID 6.2.2.3). QMKのディスクリプタでは使われないが、
                // 未知のディスクリプタでクラッシュしないようフォーマット通り読み飛ばす。
                if (i + 1 >= descriptor.size) break
                val dataSize = descriptor[i + 1].toInt() and 0xFF
                i += 3 + dataSize
                continue
            }

            val dataSize = shortItemDataSize(prefix and 0x03)
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            i += 1

            if (i + dataSize > descriptor.size) break
            val data = readLittleEndian(descriptor, i, dataSize)
            i += dataSize

            when (type) {
                ITEM_TYPE_GLOBAL -> {
                    if (tag == GLOBAL_TAG_USAGE_PAGE) {
                        currentUsagePage = data
                    }
                    // Report Size/Count/Logical Min-Max等の他のGlobalタグはここでは不要
                }

                ITEM_TYPE_LOCAL -> {
                    if (tag == LOCAL_TAG_USAGE) {
                        if (dataSize == 4) {
                            // 32bit Usageは上位16bitにUsage Pageを内包する拡張形式
                            currentUsagePage = (data ushr 16) and 0xFFFF
                            pendingUsages.add(data and 0xFFFF)
                        } else {
                            pendingUsages.add(data)
                        }
                    }
                }

                ITEM_TYPE_MAIN -> {
                    when (tag) {
                        MAIN_TAG_COLLECTION -> {
                            if (collectionDepth == 0) {
                                collections.add(
                                    HidTopLevelCollection(
                                        usagePage = currentUsagePage,
                                        usage = pendingUsages.firstOrNull() ?: 0,
                                        collectionType = data
                                    )
                                )
                            }
                            collectionDepth += 1
                        }

                        MAIN_TAG_END_COLLECTION -> {
                            if (collectionDepth > 0) collectionDepth -= 1
                        }
                    }
                    // Local items（Usage等）はMainアイテムの後にクリアされる仕様（USB HID 6.2.2.8）
                    pendingUsages.clear()
                }
            }
        }

        return collections
    }

    private fun shortItemDataSize(sizeCode: Int): Int = when (sizeCode) {
        0 -> 0
        1 -> 1
        2 -> 2
        else -> 4 // sizeCode == 3 は4byteを意味する（HID仕様の特例）
    }

    private fun readLittleEndian(bytes: ByteArray, offset: Int, size: Int): Int {
        var value = 0
        for (j in 0 until size) {
            value = value or ((bytes[offset + j].toInt() and 0xFF) shl (8 * j))
        }
        return value
    }
}
