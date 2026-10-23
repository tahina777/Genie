package com.genie.signals

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Données de marché gratuites via l'API WebSocket publique de Deriv (forex + indices synthétiques). */
object Deriv {
    private const val URL = "wss://ws.derivws.com/websockets/v3?app_id=1089"
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun candles(code: String, granularity: Int, count: Int = 300): List<Candle> {
        val result = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<List<Candle>> { cont ->
                val req = Request.Builder().url(URL).build()
                val ws = client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val q = JSONObject()
                        q.put("ticks_history", code)
                        q.put("adjust_start_time", 1)
                        q.put("count", count)
                        q.put("end", "latest")
                        q.put("granularity", granularity)
                        q.put("style", "candles")
                        webSocket.send(q.toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val obj = JSONObject(text)
                            if (obj.has("error")) {
                                val msg = obj.getJSONObject("error").optString("message", "Erreur Deriv")
                                webSocket.close(1000, null)
                                if (cont.isActive) cont.resumeWithException(IOException(msg))
                                return
                            }
                            val arr = obj.optJSONArray("candles")
                            if (arr == null) {
                                return
                            }
                            val list = ArrayList<Candle>(arr.length())
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                list.add(
                                    Candle(
                                        o.getLong("epoch"),
                                        o.getDouble("open"),
                                        o.getDouble("high"),
                                        o.getDouble("low"),
                                        o.getDouble("close")
                                    )
                                )
                            }
                            webSocket.close(1000, null)
                            if (cont.isActive) cont.resume(list)
                        } catch (e: Exception) {
                            webSocket.close(1000, null)
                            if (cont.isActive) cont.resumeWithException(IOException("Réponse invalide"))
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (cont.isActive) cont.resumeWithException(IOException(t.message ?: "Connexion impossible"))
                    }
                })
                cont.invokeOnCancellation { ws.cancel() }
            }
        }
        return result ?: throw IOException("Délai dépassé")
    }
}
