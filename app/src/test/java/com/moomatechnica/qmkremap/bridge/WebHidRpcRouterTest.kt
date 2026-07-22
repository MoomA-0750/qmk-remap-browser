package com.moomatechnica.qmkremap.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebHidRpcRouterTest {

    @Test
    fun `known method resolves with the requesting id and its result value`() {
        val router = WebHidRpcRouter(
            mapOf(
                "getDevices" to WebHidMethodHandler { _, respond -> respond(Result.success(listOf("device-a"))) }
            )
        )

        var captured: WebHidRpcResult? = null
        router.dispatch(WebHidRpcRequest(id = 42, method = "getDevices")) { captured = it }

        assertEquals(42L, captured?.id)
        assertTrue(captured!!.isSuccess)
        assertEquals(listOf("device-a"), captured?.value)
    }

    @Test
    fun `unknown method rejects with the requesting id and does not throw`() {
        val router = WebHidRpcRouter(methods = emptyMap())

        var captured: WebHidRpcResult? = null
        router.dispatch(WebHidRpcRequest(id = 7, method = "noSuchMethod")) { captured = it }

        assertEquals(7L, captured?.id)
        assertFalse(captured!!.isSuccess)
        assertEquals("Unknown method: noSuchMethod", captured?.errorMessage)
    }

    @Test
    fun `handler failure rejects preserving the requesting id`() {
        val router = WebHidRpcRouter(
            mapOf(
                "open" to WebHidMethodHandler { _, respond ->
                    respond(Result.failure(IllegalStateException("device not permitted")))
                }
            )
        )

        var captured: WebHidRpcResult? = null
        router.dispatch(WebHidRpcRequest(id = 1, method = "open")) { captured = it }

        assertFalse(captured!!.isSuccess)
        assertEquals(1L, captured?.id)
        assertEquals("device not permitted", captured?.errorMessage)
    }

    @Test
    fun `concurrent requests resolve in completion order but each keeps its own id`() {
        // 実際のUSB interrupt転送のような非同期処理は呼び出し順と完了順が一致するとは
        // 限らない。id=1(遅い)より先にid=2(速い)が完了しても、それぞれの応答が
        // 正しい呼び出し元のidと結びついたままであることを確認する。
        val pendingCallbacks = mutableMapOf<Long, (Result<Any?>) -> Unit>()
        val router = WebHidRpcRouter(
            mapOf(
                "sendReport" to WebHidMethodHandler { params, respond ->
                    val callId = params["callId"] as Long
                    pendingCallbacks[callId] = respond
                }
            )
        )

        val results = mutableListOf<WebHidRpcResult>()
        router.dispatch(WebHidRpcRequest(id = 1, method = "sendReport", params = mapOf("callId" to 1L))) { results.add(it) }
        router.dispatch(WebHidRpcRequest(id = 2, method = "sendReport", params = mapOf("callId" to 2L))) { results.add(it) }

        // まだどちらも完了していない
        assertTrue(results.isEmpty())

        // id=2の呼び出しが先に完了する
        pendingCallbacks.getValue(2L).invoke(Result.success("second-report-ack"))
        // その後id=1が完了する
        pendingCallbacks.getValue(1L).invoke(Result.success("first-report-ack"))

        assertEquals(2, results.size)
        assertEquals(2L, results[0].id)
        assertEquals("second-report-ack", results[0].value)
        assertEquals(1L, results[1].id)
        assertEquals("first-report-ack", results[1].value)
    }

    @Test
    fun `multiple in-flight requests to the same method do not cross-talk`() {
        val handlers = mutableMapOf<Long, (Result<Any?>) -> Unit>()
        val router = WebHidRpcRouter(
            mapOf(
                "receiveFeatureReport" to WebHidMethodHandler { params, respond ->
                    val id = params["id"] as Long
                    handlers[id] = respond
                }
            )
        )

        val results = mutableMapOf<Long, WebHidRpcResult>()
        for (id in 1L..5L) {
            router.dispatch(
                WebHidRpcRequest(id = id, method = "receiveFeatureReport", params = mapOf("id" to id))
            ) { results[it.id] = it }
        }

        // 完了順をわざと呼び出し順とバラバラにする
        listOf(3L, 1L, 5L, 2L, 4L).forEach { id ->
            handlers.getValue(id).invoke(Result.success("value-$id"))
        }

        for (id in 1L..5L) {
            assertEquals("value-$id", results.getValue(id).value)
            assertEquals(id, results.getValue(id).id)
        }
    }
}
