package com.sarmat.perevodchik

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class PhoneResult(val text: String, val translation: String)

/** Часы → телефон: телефон распознаёт и переводит (быстрее и точнее), всё по Bluetooth. */
class PhoneLink(private val context: Context) {

    /** Id телефона с приложением «Переводчик», если он рядом. */
    suspend fun findPhone(): String? = try {
        withTimeout(4_000) {
            val info = Wearable.getCapabilityClient(context)
                .getCapability(Bridge.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await()
            info.nodes.firstOrNull { it.isNearby }?.id ?: info.nodes.firstOrNull()?.id
        }
    } catch (e: Exception) {
        null
    }

    suspend fun recognizeAndTranslate(
        nodeId: String,
        samples: FloatArray,
        src: String,
        tgt: String
    ): PhoneResult {
        val id = System.currentTimeMillis().toString()
        val messages = Wearable.getMessageClient(context)
        val answer = CompletableDeferred<PhoneResult>()
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == Bridge.RESULT_PATH) {
                val json = runCatching { JSONObject(String(event.data)) }.getOrNull()
                if (json != null && json.optString("id") == id) {
                    val err = json.optString("error")
                    if (err.isNotEmpty()) {
                        answer.completeExceptionally(IllegalStateException(err))
                    } else {
                        answer.complete(
                            PhoneResult(json.optString("text"), json.optString("translation"))
                        )
                    }
                }
            }
        }
        messages.addListener(listener).await()
        try {
            withContext(Dispatchers.IO) {
                val channels = Wearable.getChannelClient(context)
                val channel = channels.openChannel(nodeId, Bridge.AUDIO_PATH).await()
                val out = channels.getOutputStream(channel).await()
                out.use {
                    val header = JSONObject()
                        .put("id", id).put("src", src).put("tgt", tgt)
                        .toString() + "\n"
                    it.write(header.toByteArray())
                    it.write(Bridge.floatsToPcm16(samples))
                    it.flush()
                }
            }
            return withTimeout(45_000) { answer.await() }
        } finally {
            messages.removeListener(listener)
        }
    }
}
