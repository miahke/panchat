package com.bitchat.android.net.route

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkStage(val label: String) {
    DTLS_HANDSHAKE("DTLS: защищённое рукопожатие с VPS"),
    DTLS_READY("DTLS с VPS установлен"),
    DTLS_TIMEOUT("VPS не подтвердил DTLS-рукопожатие"),
    DTLS_ERROR("Ошибка DTLS-рукопожатия"),
    PEER_PACKET("Получен защищённый ответ VPS"),
    CONFIG_REQUESTED("Запрос конфигурации туннеля"),
    CONFIG_RECEIVED("Конфигурация туннеля получена"),
    CONFIG_PENDING("VPS пока не выдал конфигурацию"),
    CONFIG_TIMEOUT("VPS не ответил на запрос конфигурации"),
    WIREGUARD_STARTING("Запуск WireGuard"),
    WIREGUARD_HANDSHAKE("Проверка WireGuard-рукопожатия через TURN"),
    WIREGUARD_READY("WireGuard подтвердил защищённый канал"),
    WG_CONFIG_ERROR("VPS выдал несовместимую конфигурацию WG"),
    WG_HANDSHAKE_ERROR("WireGuard не подтвердил рабочий канал"),
    PEER_TIMEOUT("VPS перестал отвечать; восстановление канала"),
    RAW_HANDSHAKE_RETRY("Повтор защищённого рукопожатия Raw"),
    RAW_HANDSHAKE("Защищённое рукопожатие Raw"),
    RAW_READY("Raw-канал настроен"),
    RAW_PROTOCOL_ERROR("Не удалось согласовать защищённый протокол Raw"),
    SESSION_TIMEOUT("Выход не подтвердил защищённый сеанс"),
    VK_CONFIRMATION_REQUIRED("VK запросил интерактивное подтверждение"),
    RECONNECTING("Восстановление выбранного туннеля"),
    TRANSPORT_READY("Защищённый туннель восстановлен"),
    MODE_DTLS("Выбран DTLS через TURN"),
    MODE_RTP("Выбран RTP AEAD через TURN"),
    VK_CREDENTIALS("Получение реквизитов VK TURN"),
    TURN_ALLOCATING("Выделение канала TURN"),
    TURN_READY("Канал TURN выделен"),
    AUTHENTICATING("Авторизация на выходном узле"),
    SESSION_HANDSHAKE("Установление защищённого сеанса"),
    AUTH_ERROR("Выходной узел отклонил авторизацию"),
    STARTING("Запуск"),
    CONFIGURING("Проверка конфигурации"),
    LISTENING("Запуск локального прокси"),
    BOOTSTRAPPING("Соединение с сетью Tor"),
    READY("Готово к передаче"),
    STOPPED("Остановлено"),
    CONFIGURATION_ERROR("Ошибка конфигурации или запуска ядра"),
    BOOTSTRAP_ERROR("Ошибка подключения к сети Tor"),
    TIMEOUT("Время подключения истекло"),
    CONNECTION_ERROR("Соединение завершилось с ошибкой"),
}

data class NetworkDiagnosticEvent(
    val sequence: Long,
    val route: NetworkRoute,
    val stage: NetworkStage,
    val progress: Int?,
    val elapsedMillis: Long = 0,
)

/** Accepts only predefined stages and numeric progress, never upstream logs, URLs or secrets. */
class NetworkDiagnosticLog(private val capacity: Int = 128) {
    init {
        require(capacity in 1..512)
    }

    private var sequence = 0L
    private var started = System.nanoTime()
    private val mutableEvents = MutableStateFlow<List<NetworkDiagnosticEvent>>(emptyList())
    val events = mutableEvents.asStateFlow()

    @Synchronized
    fun record(route: NetworkRoute, stage: NetworkStage, progress: Int? = null) {
        require(progress == null || progress in 0..100)
        val previous = mutableEvents.value.lastOrNull()
        if (previous?.route == route && previous.stage == stage && previous.progress == progress)
            return
        mutableEvents.value =
            (mutableEvents.value +
                    NetworkDiagnosticEvent(
                        ++sequence,
                        route,
                        stage,
                        progress,
                        (System.nanoTime() - started) / 1_000_000,
                    ))
                .takeLast(capacity)
    }

    @Synchronized
    fun clear() {
        mutableEvents.value = emptyList()
        started = System.nanoTime()
    }
}

object NetworkDiagnostics {
    val log = NetworkDiagnosticLog()
}

fun diagnosticHint(events: List<NetworkDiagnosticEvent>): String? {
    val start = events.indexOfLast { it.stage == NetworkStage.STARTING }.coerceAtLeast(0)
    val recent = events.drop(start)
    if (recent.lastOrNull()?.stage in setOf(NetworkStage.READY, NetworkStage.TRANSPORT_READY))
        return null
    return when ((recent.lastOrNull { it.stage in diagnosticFailures })?.stage) {
        NetworkStage.DTLS_TIMEOUT,
        NetworkStage.DTLS_ERROR ->
            "TURN выделен, но VPS не подтвердил DTLS. Проверьте режим и порт сервера, пароль WRAP. Для сервера с RTP AEAD выберите «Авто» либо RTP AEAD (обычно порт 56002)."
        NetworkStage.CONFIG_PENDING,
        NetworkStage.CONFIG_TIMEOUT ->
            "Защищённый канал достиг VPS, но конфигурация не получена. Проверьте выдачу WG/Raw-профиля на сервере и его совместимость."
        NetworkStage.WG_CONFIG_ERROR ->
            "WG должен использовать только локальный диспетчер TURN. Проверьте совместимость серверной конфигурации; прямое подключение к VPS не разрешается."
        NetworkStage.WG_HANDSHAKE_ERROR ->
            "Конфигурация получена, но WG не подтвердил обмен через туннель. Проверьте серверный WireGuard и маршрут внутри выхода."
        NetworkStage.RAW_PROTOCOL_ERROR ->
            "Нужна серверная версия с новым защищённым Raw-протоколом panchat. Небезопасный старый Raw автоматически не включается."
        NetworkStage.SESSION_TIMEOUT ->
            "Проверьте, что выход запущен, а комнаты, ключ и контекст шифрования совпадают с его настройками."
        NetworkStage.VK_CONFIRMATION_REQUIRED ->
            "Откройте вход VK и получите свежий хэш либо вставьте другой тестовый хэш. Ожидание невидимого WebView не запускается."
        NetworkStage.AUTH_ERROR ->
            "VPS отклонил реквизиты: проверьте пароль, срок доступа и привязку к устройству."
        else -> null
    }
}

private val diagnosticFailures =
    setOf(
        NetworkStage.DTLS_TIMEOUT,
        NetworkStage.DTLS_ERROR,
        NetworkStage.CONFIG_PENDING,
        NetworkStage.CONFIG_TIMEOUT,
        NetworkStage.WG_CONFIG_ERROR,
        NetworkStage.WG_HANDSHAKE_ERROR,
        NetworkStage.RAW_PROTOCOL_ERROR,
        NetworkStage.SESSION_TIMEOUT,
        NetworkStage.VK_CONFIRMATION_REQUIRED,
        NetworkStage.AUTH_ERROR,
    )
