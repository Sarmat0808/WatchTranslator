package com.sarmat.perevodchik

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/** Часы: отвечают телефону на «пинг» и принимают языки с телефона. */
class WatchLinkService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        when (event.path) {
            LinkProtocol.PING -> {
                val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
                val reply = JSONObject()
                    .put("version", ver)
                    .put("usePhone", prefs.getBoolean("usePhone", true))
                    .put("sync", prefs.getBoolean("syncLangs", true))
                    .toString().toByteArray()
                Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, LinkProtocol.PONG, reply)
            }
            LinkProtocol.SETTINGS -> {
                if (!prefs.getBoolean("syncLangs", true)) return
                val j = runCatching { JSONObject(String(event.data)) }.getOrNull() ?: return
                val a = j.optString("a")
                val b = j.optString("b")
                if (a.isEmpty() || b.isEmpty()) return
                prefs.edit().putString("src", a).putString("tgt", b).apply()
                LinkProtocol.incomingLangs.tryEmit(a to b)
            }
        }
    }
}
