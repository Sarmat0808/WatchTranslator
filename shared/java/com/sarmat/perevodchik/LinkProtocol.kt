package com.sarmat.perevodchik

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Сообщения между телефоном и часами (Bluetooth, без интернета). */
object LinkProtocol {
    const val CAPABILITY_WATCH = "perevodchik_watch"

    /** Телефон → часы: «кто ты?»; часы отвечают PONG с версией и настройками. */
    const val PING = "/perevodchik/ping"
    const val PONG = "/perevodchik/pong"

    /** Телефон → часы: языки {"a":"ru","b":"fi"}. */
    const val SETTINGS = "/perevodchik/settings"

    /** Языки, пришедшие с телефона (часы применяют, если синхронизация включена). */
    val incomingLangs = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)
    val langsFlow: SharedFlow<Pair<String, String>> = incomingLangs
}
