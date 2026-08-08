package com.warpy.app.localization

import com.warpy.app.model.AppLanguage
import java.util.Locale

internal fun resolveAppLanguage(
    selected: AppLanguage,
    @Suppress("UNUSED_PARAMETER") systemLanguage: String = Locale.getDefault().language,
): AppLanguage = selected

internal object WarpyLocalization {
    private val english = mapOf(
        "Авто" to "Auto",
        "Блокировать рекламу" to "Block ads",
        "Буфер обмена пуст." to "Clipboard is empty.",
        "Включить VPN" to "Connect VPN",
        "Включен" to "On",
        "Все приложения" to "All applications",
        "Все сайты" to "All websites",
        "Все сайты используют VPN" to "All websites use the VPN",
        "Вставить из буфера" to "Import from clipboard",
        "Выберите приложения для туннелирования" to "Select applications for tunneling",
        "выбран" to "selected",
        "Выбрать режим туннелирования" to "Select tunneling mode",
        "Выбрать, какие приложения используют VPN" to "Choose which applications use the VPN",
        "Выключено" to "Disconnected",
        "Выключен" to "Off",
        "Выключить VPN" to "Disconnect VPN",
        "Добавить профиль" to "Add profile",
        "Добавьте профиль" to "Add a profile",
        "Добавьте профиль кнопкой +" to "Add a profile with the + button",
        "Доступна версия" to "Version available",
        "Добавить сайт" to "Add website",
        "Добавьте сайты для отдельного правила" to "Add websites for a separate rule",
        "Добавьте сайты для туннелирования" to "Add websites for tunneling",
        "Добавленные сайты идут напрямую" to "Added websites connect directly",
        "Скачиваем обновление" to "Downloading update",
        "Разрешите установку обновления" to "Allow update installation",
        "Не удалось установить обновление" to "Could not install update",
        "Warpy обновится и снова откроется" to "Warpy will update and reopen",
        "Позже" to "Later",
        "Обновить" to "Update",
        "Повторить" to "Retry",
        "Загрузка..." to "Downloading...",
        "Дополнительная фильтрация рекламы и трекеров" to "Additional ad and tracker filtering",
        "Дополнительно" to "Advanced",
        "Доступ к домашней сети" to "Local network access",
        "Загрузка подписки..." to "Loading subscription...",
        "Загрузка приложений..." to "Loading applications...",
        "Интерфейс" to "Interface",
        "Закрепить Warpy в системных настройках Android" to "Keep Warpy enabled in Android VPN settings",
        "Закрыть" to "Close",
        "Закрыть список профилей" to "Close profile list",
        "Замерить скорость" to "Run speed test",
        "Запуск вместе с телефоном" to "Start with the phone",
        "Запустить" to "Start",
        "Защита" to "Protection",
        "Идет тест" to "Testing...",
        "Исключить выбранные" to "Exclude selected",
        "Исключить системные" to "Exclude system applications",
        "КБ/с" to "KB/s",
        "Мбит/с" to "Mbps",
        "мс" to "ms",
        "Наведите камеру на QR-код профиля" to "Point the camera at a profile QR code",
        "Нажмите + сверху, чтобы добавить первый профиль." to "Tap + above to add your first profile.",
        "Назад" to "Back",
        "Настройки" to "Settings",
        "Не удалось проверить обновления" to "Could not check for updates",
        "Не удалось восстановить предыдущий профиль" to "Could not restore the previous profile",
        "Не удалось восстановить соединение после переключения профиля" to "Could not restore the connection after switching profiles",
        "Не удалось выбрать активный профиль" to "Could not select the active profile",
        "Не удалось выбрать новый профиль" to "Could not select the new profile",
        "Не удалось запустить VPN" to "Could not start VPN",
        "Не удалось подключиться" to "Could not connect",
        "Не удалось подключиться к новому профилю" to "Could not connect to the new profile",
        "Не удалось подтвердить обмен данными через VPN-туннель" to "Could not verify traffic through the VPN tunnel",
        "Не удалось прочитать настройки VPN" to "Could not read VPN settings",
        "Не удалось разобрать профили из подписки" to "Could not parse profiles from the subscription",
        "Не удалось разобрать ссылки" to "Could not parse the links",
        "Не удалось распознать VPN-профиль." to "Could not recognize the VPN profile.",
        "Не удалось сохранить настройки" to "Could not save settings",
        "Не удалось установить соединение" to "Could not establish a connection",
        "Нет профиля" to "No profile",
        "Ничего не найдено" to "Nothing found",
        "Ограничения батареи" to "Battery restrictions",
        "Ожидание сети" to "Waiting for network",
        "Остановка VPN" to "Stopping VPN",
        "Ответ подписки слишком большой" to "Subscription response is too large",
        "Открывать роутер и устройства в локальной сети напрямую" to
            "Access the router and local devices directly",
        "Открыть список профилей" to "Open profile list",
        "Отмена" to "Cancel",
        "Отменено" to "Cancelled",
        "Отмеченные приложения идут напрямую" to "Selected applications connect directly",
        "Ошибка" to "Error",
        "Ошибка доступа к VPN интерфейсу" to "Could not access the VPN interface",
        "Ошибка запуска ядра VPN" to "VPN core failed to start",
        "Ошибка конфигурации профиля" to "Profile configuration error",
        "Ошибка подключения к VPN" to "VPN connection failed",
        "Ошибка: Порт занят другим приложением" to "Error: the port is used by another application",
        "Пинг" to "Ping",
        "Тип профиля определяется автоматически." to "The profile type is detected automatically.",
        "Поделиться профилем" to "Share profile",
        "Подключение" to "Connection",
        "Подключение..." to "Connecting...",
        "Подключено" to "Connected",
        "ПОДКЛЮЧЕНО" to "CONNECTED",
        "Подробности подключения и отчет для диагностики" to "Connection details and diagnostic report",
        "Поиск" to "Search",
        "Показать QR" to "Show QR code",
        "Последние события" to "Recent events",
        "Постоянный VPN" to "Always-on VPN",
        "Превышено время ожидания сервера" to "Server timed out",
        "Приложения" to "Applications",
        "Проверить обновления" to "Check for updates",
        "Проверяем обновления..." to "Checking for updates...",
        "Проверить профиль" to "Check profile",
        "Проверка..." to "Checking...",
        "Проверяем профиль" to "Checking profile",
        "Проверяем профиль..." to "Checking profile...",
        "Протокол" to "Protocol",
        "Профили" to "Profiles",
        "Профиль" to "Profile",
        "Профиль не выбран." to "No profile selected.",
        "Профиль не настроен или конфиг пустой" to "The profile is incomplete or its configuration is empty",
        "Профиль не подключился: TLS-сертификат не совпадает с SNI" to
            "The profile failed: the TLS certificate does not match the SNI",
        "Профиль не подключился: сервер не отвечает" to "The profile failed: the server is not responding",
        "Профиль не подключился: сервер не принял Hysteria2 handshake; проверьте SNI, пароль и obfs" to
            "The profile failed: the server rejected the Hysteria2 handshake; check SNI, password and obfs",
        "Профиль не подключился: сервер отклонил данные авторизации" to
            "The profile failed: the server rejected the credentials",
        "Профиль не подключился: сервер отклонил соединение" to
            "The profile failed: the server rejected the connection",
        "Профиль удалён" to "Profile deleted",
        "Пустой конфиг VPN" to "VPN configuration is empty",
        "Развернуть" to "Expand",
        "Размер сетевых пакетов. Изменение применится при следующем подключении" to
            "Network packet size. The change applies on the next connection",
        "Разрешить Android не останавливать VPN" to "Allow Android to keep the VPN running",
        "Свернуть" to "Collapse",
        "Сейчас замеряем: загрузку" to "Measuring download speed",
        "Сейчас замеряем: отдачу" to "Measuring upload speed",
        "Сейчас замеряем: пинг" to "Measuring latency",
        "Сервер" to "Server",
        "Сервер временно не отвечает" to "The server is temporarily unavailable",
        "Сервер временно отклонил соединение" to "The server temporarily rejected the connection",
        "Сетевые параметры" to "Network settings",
        "Сканировать QR камерой" to "Scan QR code with camera",
        "Скопировать в буфер" to "Copy to clipboard",
        "Скопировать отчет" to "Copy report",
        "Скорость" to "Speed",
        "Скрывать базовую рекламу и трекеры через DNS" to "Filter common ads and trackers through DNS",
        "Сначала включите VPN" to "Connect the VPN first",
        "Сначала добавьте профиль" to "Add a profile first",
        "Совместимость QUIC" to "QUIC compatibility",
        "Соединение восстанавливается" to "Restoring connection",
        "Соединение отклонено сервером" to "The server rejected the connection",
        "Состояние" to "Status",
        "Состояние и диагностика" to "Status and diagnostics",
        "Спидтест недоступен: локальный proxy не отвечает" to "Speed test unavailable: the local proxy is not responding",
        "Список пуст" to "List is empty",
        "Стабильное соединение" to "Stable connection",
        "Статус" to "Status",
        "Считать QR из изображения" to "Read QR code from image",
        "Тест неполный" to "Test incomplete",
        "Только выбранные" to "Only selected",
        "Туннелирование" to "Tunneling",
        "Отдельные правила для приложений и сайтов" to "Separate rules for applications and websites",
        "Сайты" to "Websites",
        "Скачиваем обновление" to "Downloading update",
        "Установлена версия" to "Installed version",
        "Удалить сайт" to "Remove website",
        "Через VPN идут только добавленные сайты" to "Only added websites use the VPN",
        "Удалить" to "Delete",
        "Удалить профиль" to "Delete profile",
        "Удалить профиль?" to "Delete profile?",
        "Улучшить работу YouTube" to "Improve YouTube access",
        "Устанавливаем туннель" to "Establishing tunnel",
        "Фоновая работа" to "Background operation",
        "Через VPN идут только отмеченные приложения" to "Only selected applications use the VPN",
        "Язык" to "Language",
        "Как в системе" to "System",
        "Язык интерфейса Warpy" to "Warpy interface language",
        "Android не выдал разрешение на VPN" to "Android did not grant VPN permission",
        "Android не открыл запрос разрешения VPN" to "Android could not open the VPN permission request",
        "Android требует разрешение на VPN" to "Android requires VPN permission",
        "Конфиг пустой." to "The configuration is empty.",
        "Автоматически восстанавливать VPN после сна и смены сети" to
            "Automatically restore VPN after sleep and network changes",
        "Включать VPN после перезагрузки, если он работал" to
            "Reconnect after a reboot when VPN was previously active",
        "Использовать TCP вместо QUIC и HTTP/3 при проблемах с сайтами" to
            "Use TCP instead of QUIC and HTTP/3 when websites are unstable",
        "MTU и совместимость соединения" to "MTU and connection compatibility",
        "Warpy может работать в фоне без ограничений" to "Warpy can run in the background without restrictions",
        "VPN выключен" to "VPN is off",
        "VPN не запустился" to "VPN failed to start",
        "VPN не запустился: внутренний сервис не ответил" to "VPN failed to start: the internal service did not respond",
        "VPN не запустился: локальный порт проверки занят" to "VPN failed to start: the local test port is in use",
        "VPN работает" to "VPN is running",
        "VPN работает для всего телефона" to "VPN is used by the entire phone",
        "Запускаем VPN" to "Starting VPN",
        "Восстанавливаем VPN" to "Restoring VPN",
        "QR-код не содержит поддерживаемый VPN-профиль" to "The QR code does not contain a supported VPN profile",
    )

    fun text(
        source: String,
        selected: AppLanguage,
        systemLanguage: String = Locale.getDefault().language,
    ): String {
        val language = resolveAppLanguage(selected, systemLanguage)
        if (language == AppLanguage.Russian) return source
        val translations = english
        translations[source]?.let { return it }

        return when {
            source.startsWith("Профиль «") && source.endsWith("» будет удалён.") ->
                "Profile “${source.removePrefix("Профиль «").removeSuffix("» будет удалён.")}” will be deleted."
            source.startsWith("Импортировано профилей: ") ->
                "Profiles imported: ${source.substringAfter(": ")}"
            source.startsWith("Ошибка загрузки подписки: ") ->
                "Subscription loading failed: ${text(source.substringAfter(": "), selected, systemLanguage)}"
            source.startsWith("Профиль содержит пока неподдерживаемый транспорт: ") ->
                "The profile uses an unsupported transport: ${source.substringAfterLast(": ")}"
            source.startsWith("Профиль содержит неподдерживаемый режим XHTTP: ") ->
                "The profile uses an unsupported XHTTP mode: ${source.substringAfterLast(": ")}"
            source.startsWith("Профиль не подключился: ") ->
                "The profile failed: ${text(source.substringAfter(": "), selected, systemLanguage)}"
            source.startsWith("Не удалось измерить: ") ->
                "Could not measure: ${translateMeasurements(source.substringAfter(": "))}"
            source.startsWith("Конфиг не собирается: ") ->
                "Could not build the configuration: ${source.substringAfter(": ")}"
            source.startsWith("DNS не вернул адреса для ") ->
                "DNS returned no addresses for ${source.substringAfter("для ")}"
            source.startsWith("DNS не нашел сервер ") ->
                "DNS could not resolve ${source.substringAfter("сервер ")}"
            source.startsWith("Базовая проверка пройдена: конфиг собран, DNS нашел ") ->
                "Basic check passed: the configuration is valid and DNS resolved " +
                    source.substringAfter("DNS нашел ").substringBefore('.') +
                    ". This profile uses UDP, so final verification runs when the VPN starts."
            source.startsWith("Базовая проверка пройдена: конфиг собран, DNS работает, TCP ") ->
                "Basic check passed: the configuration and DNS are valid; TCP " +
                    source.substringAfter("TCP ")
                        .replace(" отвечает за ", " responded in ")
                        .replace(" мс", " ms")
            source.startsWith("Сервер ") && source.contains(" не отвечает по TCP: ") ->
                "Server ${source.substringAfter("Сервер ").substringBefore(" не отвечает")} is not responding over TCP: " +
                    source.substringAfter("не отвечает по TCP: ")
            source.startsWith("Исключить выбранные: ") ->
                "Exclude selected: ${source.substringAfterLast(": ")}"
            source.startsWith("Только выбранные: ") ->
                "Only selected: ${source.substringAfterLast(": ")}"
            source.endsWith(" мс") -> source.removeSuffix(" мс") + " ms"
            source.endsWith(" КБ/с") -> source.removeSuffix(" КБ/с") + " KB/s"
            source.endsWith(" МБ/с") -> source.removeSuffix(" МБ/с") + " MB/s"
            source.endsWith(" Мбит/с") -> source.removeSuffix(" Мбит/с") + " Mbps"
            else -> source
        }
    }

    private fun translateMeasurements(value: String): String = value
        .replace("пинг", "latency", ignoreCase = true)
        .replace("загрузка", "download", ignoreCase = true)
        .replace("отдача", "upload", ignoreCase = true)
}
