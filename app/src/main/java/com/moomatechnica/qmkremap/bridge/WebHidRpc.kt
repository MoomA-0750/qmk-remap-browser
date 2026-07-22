package com.moomatechnica.qmkremap.bridge

/**
 * usevia.app側（`webhid-polyfill.js`）からKotlin側へのRPC呼び出し1件。
 *
 * `id`はJS側が呼び出しごとに発行する連番で、対応する応答（[WebHidRpcResult]）と
 * この`id`で相関させる（JSON-RPC風。plan.mdの「WebHidBridge」参照）。
 * 実際のJSONパース（生の`postMessage`文字列 -> このデータクラス）は
 * `WebHidBridge`（`WebViewCompat.addWebMessageListener`実装、T8で実配線）側の責務とし、
 * ここでは既にパース済みの呼び出し内容だけを扱う。
 */
data class WebHidRpcRequest(
    val id: Long,
    val method: String,
    val params: Map<String, Any?> = emptyMap()
)

/** [WebHidRpcRequest.id]と対になる応答。成功時は[value]、失敗時は[errorMessage]を持つ。 */
data class WebHidRpcResult private constructor(
    val id: Long,
    val isSuccess: Boolean,
    val value: Any?,
    val errorMessage: String?
) {
    companion object {
        fun success(id: Long, value: Any?): WebHidRpcResult =
            WebHidRpcResult(id = id, isSuccess = true, value = value, errorMessage = null)

        fun failure(id: Long, errorMessage: String): WebHidRpcResult =
            WebHidRpcResult(id = id, isSuccess = false, value = null, errorMessage = errorMessage)
    }
}

/** [WebHidRpcRouter]に登録するメソッドハンドラ。USB IO等の非同期処理を想定し、結果はコールバックで返す。 */
fun interface WebHidMethodHandler {
    fun handle(params: Map<String, Any?>, respond: (Result<Any?>) -> Unit)
}
