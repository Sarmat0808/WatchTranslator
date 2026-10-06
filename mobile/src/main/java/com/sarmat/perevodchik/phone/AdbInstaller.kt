package com.sarmat.perevodchik.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import kotlin.coroutines.resume

/**
 * Установка приложения на часы прямо с телефона — по Wi‑Fi, через «Отладку по Wi‑Fi» на часах
 * (тот же механизм, что у программ Bugjaeger / Wear Installer). Работает на любых Wear OS 3+.
 * Один раз нужно ввести 6-значный код с часов, дальше обновления ставятся одной кнопкой.
 */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val dir = File(context.filesDir, "adb").apply { mkdirs() }
        val keyFile = File(dir, "private.key")
        val certFile = File(dir, "cert.der")
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=Perevodchik")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 3650L * 86_400_000L), name, kp.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            key = kp.private
            cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "Perevodchik"

    companion object {
        @Volatile
        private var instance: AdbManager? = null
        fun get(context: Context): AdbManager =
            instance ?: synchronized(this) { instance ?: AdbManager(context.applicationContext).also { instance = it } }
    }
}

object WatchInstaller {
    const val WATCH_APK_URL =
        "https://github.com/Sarmat0808/WatchTranslator/releases/download/latest/Perevodchik.apk"

    /** Адреса самого телефона — чтобы не перепутать его с часами при поиске. */
    private fun ownAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    /** Ищет в Wi‑Fi сети часы с включённой отладкой: type = "adb-tls-pairing" или "adb-tls-connect". */
    suspend fun discover(context: Context, type: String, timeoutMs: Long = 15_000): Pair<String, Int>? {
        val nsd = context.getSystemService(NsdManager::class.java)
        val own = ownAddresses()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var listener: NsdManager.DiscoveryListener? = null
                listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

                    @Suppress("DEPRECATION")
                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(si: NsdServiceInfo) {
                                val host: InetAddress = si.host ?: return
                                val addr = host.hostAddress ?: return
                                if (addr in own || host.isLoopbackAddress) return
                                if (cont.isActive) {
                                    runCatching { nsd.stopServiceDiscovery(listener) }
                                    cont.resume(addr to si.port)
                                }
                            }
                        })
                    }
                }
                nsd.discoverServices("_$type._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
                cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
            }
        }
    }

    /** Сопряжение: код с экрана часов «Подключить устройство». */
    suspend fun pair(context: Context, code: String, host: String?, port: Int?): Boolean = withContext(Dispatchers.IO) {
        val target = if (host != null && port != null) host to port
        else discover(context, "adb-tls-pairing") ?: throw IllegalStateException(
            "Часы не найдены. Откройте на часах «Подключить новое устройство» и держите экран включённым"
        )
        AdbManager.get(context).pair(target.first, target.second, code.trim())
    }

    /** Подключение к часам (после сопряжения). */
    suspend fun connect(context: Context, host: String? = null, port: Int? = null): Boolean = withContext(Dispatchers.IO) {
        val m = AdbManager.get(context)
        if (m.isConnected) return@withContext true
        val target = if (host != null && port != null) host to port
        else discover(context, "adb-tls-connect") ?: throw IllegalStateException(
            "Часы не найдены в сети. Включите на часах «Отладку по Wi‑Fi» и подключите их к той же сети Wi‑Fi"
        )
        m.connect(target.first, target.second)
    }

    /** Скачивает свежую версию для часов (один раз нужен интернет). */
    suspend fun downloadWatchApk(context: Context, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "watch.apk")
        var url = URL(WATCH_APK_URL)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 6) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            if (code != 200) throw IllegalStateException("Не удалось скачать (HTTP $code)")
            break
        }
        val total = conn.contentLengthLong
        var done = 0L
        conn.inputStream.use { input ->
            out.outputStream().use { os ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
        }
        out
    }

    /** Устанавливает APK на подключённые часы. Возвращает ответ системы ("Success" при успехе). */
    suspend fun install(context: Context, apk: File, onProgress: (Long, Long) -> Unit): String = withContext(Dispatchers.IO) {
        val m = AdbManager.get(context)
        val size = apk.length()
        m.openStream("exec:cmd package install -r -S $size").use { stream ->
            val os = stream.openOutputStream()
            apk.inputStream().use { input -> copy(input, size, onProgress) { b, n -> os.write(b, 0, n) } }
            os.flush()
            val reply = stream.openInputStream().bufferedReader().readText().trim()
            reply
        }
    }

    private inline fun copy(input: InputStream, total: Long, onProgress: (Long, Long) -> Unit, write: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            write(buf, n)
            done += n
            onProgress(done, total)
        }
    }

    fun disconnect(context: Context) {
        runCatching { AdbManager.get(context).disconnect() }
    }
}
