package com.moomatechnica.qmkremap.bridge

/**
 * `webhid-polyfill.js`からのRPC呼び出し（[WebHidRpcRequest]）を、登録済みメソッドハンドラに
 * ディスパッチし、`id`を保ったまま結果（[WebHidRpcResult]）を組み立てるルーター。
 *
 * T5の「JSON-RPC風の非同期ブリッジ」のうち、**実機・実WebViewを必要としない部分**
 * （idの相関・resolve/reject相当の判定ロジック）だけを切り出したもの。
 * 実際のJSON文字列のパース/シリアライズや`WebViewCompat.addWebMessageListener`との結線は
 * `WebHidBridge`（未実装、T8で実配線）側の責務。
 *
 * ハンドラをコールバック形式にしているのは、実際のUSB interrupt転送のような非同期・
 * 完了順序が呼び出し順と一致するとは限らない処理を想定しているため。この順序保証の
 * 有無に関わらず、応答が必ず正しい呼び出し元の`id`と共に返ることを
 * [com.moomatechnica.qmkremap.bridge.WebHidRpcRouterTest]で検証する。
 */
class WebHidRpcRouter(
    private val methods: Map<String, WebHidMethodHandler>
) {

    fun dispatch(request: WebHidRpcRequest, onResult: (WebHidRpcResult) -> Unit) {
        val handler = methods[request.method]
        if (handler == null) {
            onResult(WebHidRpcResult.failure(request.id, "Unknown method: ${request.method}"))
            return
        }

        handler.handle(request.params) { result ->
            result.fold(
                onSuccess = { value -> onResult(WebHidRpcResult.success(request.id, value)) },
                onFailure = { error ->
                    onResult(WebHidRpcResult.failure(request.id, error.message ?: error.toString()))
                }
            )
        }
    }
}
