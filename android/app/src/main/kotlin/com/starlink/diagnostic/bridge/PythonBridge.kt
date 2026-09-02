package com.starlink.diagnostic.bridge

import com.chaquo.python.Python
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Thin Kotlin -> Python bridge.
 *
 * Everything (gRPC to the dish, diagnostics engine, SQLite history, demo
 * mode) lives in Python: python/bridge.py exposes one entry point
 * rpc(payloadJson) -> resultJson. Kotlin only renders results.
 */
object PythonBridge {

    /** Python calls are serialized on one worker thread (GIL-friendly). */
    private val pyDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "py-bridge") }.asCoroutineDispatcher()

    class BridgeException(
        val errorAr: String,
        val technical: String,
    ) : Exception(errorAr)

    /** Blocking call — invoke from [callSuspend] or a background thread. */
    fun call(op: String, args: JSONObject = JSONObject()): JSONObject {
        val payload = JSONObject().put("op", op).put("args", args)
        val py = Python.getInstance()
        val module = py.getModule("bridge")
        val raw = module.callAttr("rpc", payload.toString()).toString()
        val res = JSONObject(raw)
        if (!res.optBoolean("ok", false)) {
            throw BridgeException(
                res.optString("errorAr", "خطأ غير معروف"),
                res.optString("error", "unknown"),
            )
        }
        return res.optJSONObject("data") ?: JSONObject()
    }

    suspend fun callSuspend(op: String, args: JSONObject = JSONObject()): JSONObject =
        withContext(pyDispatcher) { call(op, args) }
}
