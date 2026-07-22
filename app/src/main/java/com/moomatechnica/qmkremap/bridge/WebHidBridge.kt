package com.moomatechnica.qmkremap.bridge

import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * T5/T8: `webhid-polyfill.js`と`WebHidRpcRouter`を実際につなぐ
 * `WebViewCompat.addWebMessageListener`の実装。
 *
 * JS -> Kotlin: `window.QmkRemapNative.postMessage(jsonString)` が[onPostMessage]を呼ぶ。
 * Kotlin -> JS: 応答は`replyProxy.postMessage(jsonString)`（ポリフィルの`window.QmkRemapNative.onmessage`
 * として届く）。`oninputreport`のような非同期プッシュも同じ`replyProxy`を使い回して送る
 * （最後に受け取った`replyProxy`を保持しておく。ページがナビゲートしない限り有効）。
 */
class WebHidBridge(
    private val router: WebHidRpcRouter
) : WebViewCompat.WebMessageListener {

    @Volatile
    private var latestReplyProxy: JavaScriptReplyProxy? = null

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        latestReplyProxy = replyProxy
        val raw = message.data
        if (raw == null) {
            Log.w(TAG, "onPostMessage: null data")
            return
        }

        val request = try {
            parseRequest(raw)
        } catch (e: Exception) {
            Log.w(TAG, "malformed RPC request: $raw", e)
            return
        }

        router.dispatch(request) { result ->
            try {
                replyProxy.postMessage(serializeResult(result))
            } catch (e: Exception) {
                Log.w(TAG, "failed to post RPC result", e)
            }
        }
    }

    /** T4のUSB受信スレッドから呼ぶ想定。`oninputreport`としてJS側に届く。 */
    fun sendInputReportEvent(deviceId: Int, reportId: Int, data: ByteArray) {
        val proxy = latestReplyProxy ?: run {
            Log.w(TAG, "sendInputReportEvent: no reply proxy yet, dropping event")
            return
        }
        val json = JSONObject().apply {
            put("event", "inputreport")
            put("deviceId", deviceId)
            put("reportId", reportId)
            put("data", JSONArray(data.map { it.toInt() and 0xFF }))
        }
        try {
            proxy.postMessage(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "failed to post inputreport event", e)
        }
    }

    private fun parseRequest(raw: String): WebHidRpcRequest {
        val json = JSONObject(raw)
        val id = json.getLong("id")
        val method = json.getString("method")
        val paramsJson = json.optJSONObject("params") ?: JSONObject()
        @Suppress("UNCHECKED_CAST")
        val params = jsonToKotlin(paramsJson) as Map<String, Any?>
        return WebHidRpcRequest(id, method, params)
    }

    private fun serializeResult(result: WebHidRpcResult): String {
        val json = JSONObject()
        json.put("id", result.id)
        if (result.isSuccess) {
            json.put("result", toJsonValue(result.value))
        } else {
            json.put("error", result.errorMessage)
        }
        return json.toString()
    }

    private fun jsonToKotlin(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { key -> jsonToKotlin(value.get(key)) }
        is JSONArray -> (0 until value.length()).map { i -> jsonToKotlin(value.get(i)) }
        else -> value
    }

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (k, v) -> put(k.toString(), toJsonValue(v)) }
        }
        is List<*> -> JSONArray().apply {
            value.forEach { put(toJsonValue(it)) }
        }
        else -> value
    }

    companion object {
        private const val TAG = "WebHidBridge"
        const val JS_OBJECT_NAME = "QmkRemapNative"
    }
}
