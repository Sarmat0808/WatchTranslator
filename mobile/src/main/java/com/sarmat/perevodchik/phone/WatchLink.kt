package com.sarmat.perevodchik.phone

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.sarmat.perevodchik.LinkProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Состояние часов, как его видит телефон. */
data class WatchInfo(
    val connected: Boolean,       // часы подключены к телефону по Bluetooth
    val watchName: String = "",
    val appInstalled: Boolean = false,
    val appVersion: String = "",
    val watchUsesPhone: Boolean = true,
    val watchSyncs: Boolean = true
)

object WatchLink {
    private const val PREFS = "phone"
    @Volatile
    private var pong: CompletableDeferred<JSONObject>? = null

    fun linkEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("watchLink", true)
    fun syncEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("watchSync", true)

    fun setLinkEnabled(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("watchLink", v).apply()

    fun setSyncEnabled(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("watchSync", v).apply()

    internal fun onPong(data: ByteArray) {
        runCatching { JSONObject(String(data)) }.getOrNull()?.let { pong?.complete(it) }
    }

    suspend fun status(ctx: Context): WatchInfo {
        val nodes = runCatching { Wearable.getNodeClient(ctx).connectedNodes.await() }.getOrDefault(emptyList())
        val watch = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            ?: return WatchInfo(connected = false)
        val cap = runCatching {
            Wearable.getCapabilityClient(ctx)
                .getCapability(LinkProtocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await()
        }.getOrNull()
        val installed = cap?.nodes?.any { it.id == watch.id } == true
        if (!installed) return WatchInfo(true, watch.displayName, appInstalled = false)
        val d = CompletableDeferred<JSONObject>()
        pong = d
        runCatching { Wearable.getMessageClient(ctx).sendMessage(watch.id, LinkProtocol.PING, ByteArray(0)).await() }
        val j = withTimeoutOrNull(4_000) { d.await() }
        return WatchInfo(
            connected = true,
            watchName = watch.displayName,
            appInstalled = true,
            appVersion = j?.optString("version") ?: "",
            watchUsesPhone = j?.optBoolean("usePhone", true) ?: true,
            watchSyncs = j?.optBoolean("sync", true) ?: true
        )
    }

    /** Отправить часам языки (если синхронизация включена). */
    suspend fun sendLangs(ctx: Context, a: String, b: String) {
        if (!syncEnabled(ctx)) return
        val cap = runCatching {
            Wearable.getCapabilityClient(ctx)
                .getCapability(LinkProtocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await()
        }.getOrNull() ?: return
        val data = JSONObject().put("a", a).put("b", b).toString().toByteArray()
        for (n in cap.nodes) runCatching { Wearable.getMessageClient(ctx).sendMessage(n.id, LinkProtocol.SETTINGS, data).await() }
    }

    /** Магазинная версия: открыть страницу приложения в Google Play на часах. */
    suspend fun openPlayOnWatch(ctx: Context): Boolean {
        val nodes = runCatching { Wearable.getNodeClient(ctx).connectedNodes.await() }.getOrDefault(emptyList())
        val watch = nodes.firstOrNull() ?: return false
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            .setData(android.net.Uri.parse("market://details?id=${ctx.packageName}"))
        return runCatching {
            androidx.wear.remote.interactions.RemoteActivityHelper(ctx).startRemoteActivity(intent, watch.id).await()
            true
        }.getOrDefault(false)
    }

    /** Приложение установлено из Google Play? */
    fun fromPlay(ctx: Context): Boolean = runCatching {
        val src = if (android.os.Build.VERSION.SDK_INT >= 30)
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
        src == "com.android.vending"
    }.getOrDefault(false)
}
