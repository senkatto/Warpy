import {
  buildRuntimeSingBoxConfig,
  parseProfileLink,
} from './vpn-config.js';
import {
  findProfileIndexAfterSubscriptionUpdate,
  parseSubscriptionPayload,
  replaceSubscriptionProfiles,
  subscriptionDisplayName,
  subscriptionProfilesEqual,
  subscriptionRefreshDue,
} from './subscription.js';
import { buildTrayProfileSnapshots } from './tray-profiles.js';
import {
  autoSwitchNotificationEvent,
  connectivityNotificationTransition,
  createNotificationDedupe,
} from './significant-notifications.js';
import {
  networkProtectionDecision,
  normalizeNetworkContext,
} from './network-policy.js';
import { classifyClipboardImport } from './clipboard-import.js';
import {
  buildConnectionRecommendations,
  classifyConnection,
  summarizeLatencySamples,
} from './connection-diagnostics.js';
import {
  cloudflareDownloadUrl,
  fetchWithTimeout,
  measureLatencySamples,
  median,
  toMbps,
} from './network-measurements.js';
import {
  isTransientServiceStatus,
  reconcileServiceConnection,
} from './vpn-session-state.js';

// Uses window.__TAURI__.core.invoke (injected by withGlobalTauri).

const invoke = window.__TAURI__ ? window.__TAURI__.core.invoke : async function(cmd, args) {
  console.warn('Tauri not available, mock invoke:', cmd, args);
  if (cmd === 'load_settings') return '{}';
  if (cmd === 'get_vpn_status') return 'Stopped';
  if (cmd === 'get_vpn_runtime_snapshot') {
    return { status: 'Stopped', network: { trust: 'unknown', internet: false, generation: 0 } };
  }
  return null;
};

document.addEventListener('wheel', event => {
  if (Math.abs(event.deltaX) > Math.abs(event.deltaY)) event.preventDefault();
}, { capture: true, passive: false });

async function logMsg(msg) {
  console.log(msg);
  try {
    if (window.__TAURI__) {
      const timestamp = new Date().toISOString();
      await invoke('log_message', { message: `[${timestamp}] [WebUI] ${msg}` });
    }
  } catch(e) { /* ignore */ }
}
/* ── Translation Dictionary ─────────────────────── */
const T = {
  ru: {
    settings: 'Настройки',
    connectionSection: 'Подключение',
    protectionSection: 'Защита',
    routingSection: 'Туннелирование',
    routingDescription: 'Приложения и сайты с отдельными правилами',
    profilesAndSubscriptions: 'Профили и подписки',
    appSection: 'Приложение',
    advancedSection: 'Расширенные настройки',
    connectionCheckTitle: 'Проверка соединения',
    connectionCheckIdle: 'Замерим качество VPN и покажем рекомендации',
    connectionCheckRunning: 'Подготавливаем проверку...',
    connectionCheckLatency: 'Замеряем задержку: {current} из {total}',
    connectionCheckDownload: 'Проверяем стабильность загрузки...',
    connectionCheckNoProfiles: 'Сначала добавьте профиль',
    connectionCheckFailed: 'Не удалось подтвердить работу VPN',
    connectionCheckComplete: 'Проверка завершена',
    connectionCheckApplied: 'Рекомендованные параметры применены',
    checkAndTune: 'Проверить',
    connectionReportTitle: 'Результат проверки',
    connectionReportStable: 'Соединение стабильное',
    connectionReportImpaired: 'Есть заметные колебания',
    connectionReportPoor: 'Соединение нестабильное',
    connectionReportStableDetail: 'Потерь не обнаружено, задержка и её разброс в норме.',
    connectionReportLatencyDetail: 'Основная проблема — высокая задержка ответа.',
    connectionReportJitterDetail: 'Основная проблема — нестабильная задержка.',
    connectionReportLossDetail: 'Основная проблема — часть проверочных запросов не получила ответ.',
    connectionReportLatency: 'Задержка',
    connectionReportJitter: 'Разброс',
    connectionReportLoss: 'Потери',
    connectionReportDownload: 'Загрузка',
    connectionReportRecommendations: 'Предлагаемые изменения',
    connectionReportNoChanges: 'Менять настройки не требуется.',
    connectionReportAutoTitle: 'Включить выбор лучшего сервера',
    connectionReportAutoDescription: 'Warpy продолжит сравнивать серверы и переключится только после нескольких подтверждённых замеров.',
    connectionReportMtuTitle: 'Вернуть автоматический MTU',
    connectionReportMtuDescription: 'Warpy перестанет использовать заданный вручную размер пакета.',
    connectionReportApply: 'Применить',
    connectionReportDone: 'Готово',
    lang: 'Язык',
    langRu: 'Русский',
    langEn: 'English',
    langDescription: 'Язык интерфейса Warpy',
    adblock: 'Блокировать рекламу и трекеры',
    adblockDescription: 'Отсекает известные рекламные домены внутри VPN',
    quic: 'Совместимость QUIC',
    quicDescription: 'Отключает QUIC, если сайты нестабильно работают через UDP',
    quicTooltip: 'QUIC ускоряет некоторые сайты, но в отдельных сетях работает нестабильно. Включите этот параметр только при проблемах.',
    lan: 'Доступ к домашней сети',
    lanDescription: 'Принтеры, телевизоры и другие устройства остаются доступны',
    resumeOnBoot: 'Подключаться после запуска Windows',
    resumeOnBootDescription: 'Восстанавливает защищённое соединение автоматически',
    networkAutoProtect: 'Защищать в публичных сетях',
    networkAutoProtectDescription: 'Подключает VPN в незнакомой общедоступной сети',
    networkAutoProtectOff: 'Выключена',
    networkAutoProtectTrusted: 'Доверенная сеть',
    networkAutoProtectPublic: 'VPN включится автоматически',
    networkAutoProtectProtected: 'Общедоступная сеть защищена',
    networkAutoProtectBlocked: 'Приостановлена после ручного отключения',
    networkAutoProtectFailed: 'Автоподключение не удалось',
    networkAutoProtectUnknown: 'Тип сети не определен',
    networkAutoProtectOffline: 'Нет подключения к сети',
    networkAutoProtectNoProfiles: 'Добавьте профиль для автозащиты',
    warpyAuto: 'Выбирать лучший сервер',
    warpyAutoDescription: 'Warpy сравнивает доступные серверы и переключается только при заметной разнице',
    warpyAutoOff: 'Выключен',
    warpyAutoWatching: 'Следит за качеством соединения',
    warpyAutoFaster: 'Переключено на более быстрый сервер',
    warpyAutoUnavailable: 'Переключено: сервер перестал отвечать',
    warpyAutoReturned: 'Возвращено на выбранный сервер',
    warpyAutoPreferred: 'Предпочтительный сервер',
    warpyAutoFailed: 'Автопереключение не удалось',
    killSwitch: 'Блокировать интернет при обрыве VPN',
    killSwitchDescription: 'Не позволяет трафику незаметно уйти напрямую',
    killSwitchTooltip: 'Если VPN неожиданно отключится, Warpy временно заблокирует интернет до восстановления защиты.',
    killSwitchOff: 'Выключена',
    killSwitchReady: 'Включится вместе с VPN',
    killSwitchArmed: 'Активна',
    killSwitchSuppressed: 'Трафик защищён другим VPN',
    killSwitchSplitSuppressed: 'Не применяется при обходе VPN',
    killSwitchError: 'Защита недоступна',
    mtuDescription: 'Оставьте автоматически, если поддержка не попросила другое значение',
    mtuTooltip: 'MTU определяет размер сетевых пакетов. Неверное значение может замедлить или нарушить загрузку сайтов.',
    apps: 'Приложения',
    appsDescription: 'Выберите, какие программы используют VPN',
    sites: 'Сайты',
    sitesDescription: 'Добавьте сайты для отдельного правила',
    appsOff: 'Все через VPN',
    appsOnly: 'Только выбранные',
    appsBypass: 'Кроме выбранных',
    sitesOff: 'Все через VPN',
    sitesOnly: 'Только выбранные',
    sitesBypass: 'Кроме выбранных',
    allVpn: 'Все через VPN',
    onlySelected: 'Только выбранные',
    bypassVpn: 'В обход VPN',
    browse: 'Выбрать файл',
    running: 'Из запущенных',
    appsListPlaceholder: 'Пример: chrome.exe, telegram.exe',
    sitesListPlaceholder: 'Пример: instagram.com, youtube.com',

    // Main UI
    connected: 'Подключено',
    connecting: 'Подключение...',
    disconnected: 'Отключено',
    ping: 'Пинг',
    speed: 'Скорость',

    // Dialogs
    addProfile: 'Добавить профиль',
    clipboardHint: 'Warpy сам определит конфигурацию или HTTPS-ссылку подписки',
    addClipboardBtn: 'Добавить из буфера обмена',
    importLoading: 'Добавление...',
    subscriptionsTitle: 'Подписки',
    updatesTitle: 'Обновления',
    updateChannel: 'Канал',
    updateChannelDescription: 'Stable рекомендуется большинству пользователей',
    updateStable: 'Stable',
    updateBeta: 'Beta',
    checkUpdates: 'Проверить обновления',
    updateChecking: 'Проверяем обновления...',
    updateLatest: 'Установлена актуальная версия',
    updateAvailable: 'Доступна версия',
    updateRollbackAvailable: 'Доступно восстановление версии',
    updateInstall: 'Установить',
    updateLater: 'Позже',
    updateInstalling: 'Устанавливаем обновление...',
    updateFailed: 'Не удалось проверить обновления',
    updateInstallFailed: 'Не удалось установить обновление',
    updateSaveSettings: 'Сначала сохраните изменения в настройках',
    updateInstallConfirm: 'Установить версию',
    updateRollbackConfirm: 'Восстановить версию',
    updateRestartNotice: 'Warpy и VPN будут перезапущены.',
    diagnosticsTitle: 'Диагностика',
    exportDiagnostics: 'Сохранить диагностику',
    diagnosticsExporting: 'Подготовка архива...',
    diagnosticsSaved: 'Сохранено в Загрузки',
    diagnosticsError: 'Не удалось сохранить диагностику',
    subscriptionsEmpty: 'Нет подписок',
    subscriptionUpdated: 'Обновлено',
    subscriptionUnchanged: 'Без изменений',
    subscriptionUpdateError: 'Ошибка обновления',
    subscriptionUpdating: 'Обновление...',
    refreshSubscription: 'Обновить подписку',
    cancel: 'Отмена',
    confirm: 'Добавить',
    close: 'Закрыть',
    back: 'Назад',
    profilesTitle: 'Профили',
    emptyMsg: 'Список пуст',
    shareTitle: 'Поделиться',
    copy: 'Копировать',
    runningAppsTitle: 'Выберите программы',
    excludeSystem: 'Исключить системные',
    searchPlaceholder: 'Поиск по названию...',
    nothingFound: 'Ничего не найдено',
    speedtestBtnStart: 'Запустить',
    speedtestRunning: 'Идет тест',

    // Alerts & dynamic texts
    copySuccess: 'Ссылка скопирована!',
    fileSelectError: 'Ошибка при выборе файла: ',
    clipboardEmpty: 'Буфер обмена пуст',
    clipboardError: 'Не удалось использовать буфер обмена: ',
    invalidFormat: 'В буфере должна быть конфигурация vless://, trojan://, hysteria2:// или HTTPS-ссылка подписки',
    loadingProcesses: 'Загрузка процессов...',
    errorPrefix: 'ОШИБКА: ',
    errorLabel: 'Ошибка: ',
    failedToStart: 'Не удалось запустить',
    failedToConnect: 'Не удалось подключиться',
    serviceUnavailable: 'Служба VPN временно не отвечает',
    failedToDisconnect: 'Не удалось отключиться',
    establishingTunnel: 'Устанавливаем туннель',
    addProfileHint: 'Добавьте профиль кнопкой +',
    cancelled: 'ОТМЕНЕНО',
    mbps: 'Мбит/с',
    kbps: 'КБ/с',
    ms: 'мс',
    saveBtn: 'Сохранить',
    unsavedSettings: 'Сохранить изменения в настройках?',
    discardChanges: 'Не сохранять',
    restartConfirm: 'Чтобы изменения вступили в силу, необходимо перезапустить VPN. Перезапустить сейчас?',
    deleteConfirm: 'Вы уверены, что хотите удалить профиль',
    deleteGroupConfirm: 'Удалить группу и все ее серверы',
    yes: 'Да',
    ok: 'ОК',
    duplicateProfile: 'Этот профиль уже добавлен',
    connectImportedProfile: 'Подключиться к новому профилю?',
    profileSwitchError: 'Не удалось переключить сервер: ',
    settingsSaveError: 'Не удалось сохранить настройки: ',
    settingsLoadError: 'Не удалось открыть профили. Warpy не будет перезаписывать данные. Перезапустите приложение; если ошибка повторится, сохраните диагностику.',
    settingsUnavailable: 'Хранилище профилей недоступно',
    trafficCheckFailed: 'Нет обмена данными через VPN-туннель',
    notificationVpnRestoredTitle: 'VPN восстановлен',
    notificationVpnRestoredBody: 'Защищённое соединение снова работает',
    notificationVpnFailedTitle: 'VPN отключён',
    notificationVpnFailedBody: 'Warpy не смог восстановить защищённое соединение',
    speedtestFailed: 'Не удалось выполнить тест',
    otherVpnActive: 'Отключите другой VPN перед подключением Warpy',
    subscriptionInvalidUrl: 'В буфере должна быть HTTPS-ссылка подписки',
    subscriptionError: 'Не удалось обновить подписку: ',
  },
  en: {
    settings: 'Settings',
    connectionSection: 'Connection',
    protectionSection: 'Protection',
    routingSection: 'Tunneling',
    routingDescription: 'Separate rules for applications and websites',
    profilesAndSubscriptions: 'Profiles and subscriptions',
    appSection: 'Application',
    advancedSection: 'Advanced settings',
    connectionCheckTitle: 'Connection check',
    connectionCheckIdle: 'Measure VPN quality and review recommendations',
    connectionCheckRunning: 'Preparing the check...',
    connectionCheckLatency: 'Measuring latency: {current} of {total}',
    connectionCheckDownload: 'Checking download stability...',
    connectionCheckNoProfiles: 'Add a profile first',
    connectionCheckFailed: 'Could not verify the VPN connection',
    connectionCheckComplete: 'Check complete',
    connectionCheckApplied: 'Recommended settings applied',
    checkAndTune: 'Check',
    connectionReportTitle: 'Connection check result',
    connectionReportStable: 'Connection is stable',
    connectionReportImpaired: 'Noticeable fluctuations detected',
    connectionReportPoor: 'Connection is unstable',
    connectionReportStableDetail: 'No loss was detected, and latency is consistent.',
    connectionReportLatencyDetail: 'High response latency is the main issue.',
    connectionReportJitterDetail: 'Inconsistent latency is the main issue.',
    connectionReportLossDetail: 'Some diagnostic requests did not receive a response.',
    connectionReportLatency: 'Latency',
    connectionReportJitter: 'Jitter',
    connectionReportLoss: 'Loss',
    connectionReportDownload: 'Download',
    connectionReportRecommendations: 'Suggested changes',
    connectionReportNoChanges: 'No settings need to be changed.',
    connectionReportAutoTitle: 'Enable best server selection',
    connectionReportAutoDescription: 'Warpy will keep comparing servers and switch only after several confirmed measurements.',
    connectionReportMtuTitle: 'Restore automatic MTU',
    connectionReportMtuDescription: 'Warpy will stop using the manually configured packet size.',
    connectionReportApply: 'Apply',
    connectionReportDone: 'Done',
    lang: 'Language',
    langRu: 'Русский',
    langEn: 'English',
    langDescription: 'Warpy interface language',
    adblock: 'Block ads and trackers',
    adblockDescription: 'Filters known advertising domains inside the VPN',
    quic: 'QUIC compatibility',
    quicDescription: 'Disables QUIC when websites are unstable over UDP',
    quicTooltip: 'QUIC can speed up some websites, but it is unstable on certain networks. Enable this only when needed.',
    lan: 'Local network access',
    lanDescription: 'Printers, TVs and other devices remain accessible',
    resumeOnBoot: 'Connect after Windows starts',
    resumeOnBootDescription: 'Restores the protected connection automatically',
    networkAutoProtect: 'Protect on public networks',
    networkAutoProtectDescription: 'Connects the VPN on an unfamiliar public network',
    networkAutoProtectOff: 'Off',
    networkAutoProtectTrusted: 'Trusted network',
    networkAutoProtectPublic: 'VPN will connect automatically',
    networkAutoProtectProtected: 'Public network is protected',
    networkAutoProtectBlocked: 'Paused after manual disconnect',
    networkAutoProtectFailed: 'Automatic connection failed',
    networkAutoProtectUnknown: 'Network type is unknown',
    networkAutoProtectOffline: 'No network connection',
    networkAutoProtectNoProfiles: 'Add a profile to enable protection',
    warpyAuto: 'Choose the best server',
    warpyAutoDescription: 'Warpy compares available servers and switches only when the difference is meaningful',
    warpyAutoOff: 'Off',
    warpyAutoWatching: 'Watching connection quality',
    warpyAutoFaster: 'Switched to a faster server',
    warpyAutoUnavailable: 'Switched because the server stopped responding',
    warpyAutoReturned: 'Returned to the selected server',
    warpyAutoPreferred: 'Preferred server',
    warpyAutoFailed: 'Automatic switch failed',
    killSwitch: 'Block internet if VPN disconnects',
    killSwitchDescription: 'Prevents traffic from silently going direct',
    killSwitchTooltip: 'If the VPN disconnects unexpectedly, Warpy temporarily blocks internet access until protection is restored.',
    killSwitchOff: 'Off',
    killSwitchReady: 'Activates with VPN',
    killSwitchArmed: 'Active',
    killSwitchSuppressed: 'Traffic is protected by another VPN',
    killSwitchSplitSuppressed: 'Unavailable while VPN bypass is active',
    killSwitchError: 'Protection unavailable',
    mtuDescription: 'Leave this on automatic unless support asks for a specific value',
    mtuTooltip: 'MTU controls network packet size. A wrong value can slow down or break website loading.',
    apps: 'Applications',
    appsDescription: 'Choose which applications use the VPN',
    sites: 'Websites',
    sitesDescription: 'Add websites that need a separate rule',
    appsOff: 'All through VPN',
    appsOnly: 'Only selected',
    appsBypass: 'Except selected',
    sitesOff: 'All through VPN',
    sitesOnly: 'Only selected',
    sitesBypass: 'Except selected',
    allVpn: 'All through VPN',
    onlySelected: 'Only selected',
    bypassVpn: 'Bypass VPN',
    browse: 'Choose file',
    running: 'From running apps',
    appsListPlaceholder: 'Example: chrome.exe, telegram.exe',
    sitesListPlaceholder: 'Example: instagram.com, youtube.com',

    // Main UI
    connected: 'Connected',
    connecting: 'Connecting...',
    disconnected: 'Disconnected',
    ping: 'Ping',
    speed: 'Speed',

    // Dialogs
    addProfile: 'Add Profile',
    clipboardHint: 'Warpy will detect a configuration or an HTTPS subscription URL',
    addClipboardBtn: 'Import from Clipboard',
    importLoading: 'Importing...',
    subscriptionsTitle: 'Subscriptions',
    updatesTitle: 'Updates',
    updateChannel: 'Channel',
    updateChannelDescription: 'Stable is recommended for most users',
    updateStable: 'Stable',
    updateBeta: 'Beta',
    checkUpdates: 'Check for updates',
    updateChecking: 'Checking for updates...',
    updateLatest: 'Warpy is up to date',
    updateAvailable: 'Version available',
    updateRollbackAvailable: 'Recovery version available',
    updateInstall: 'Install',
    updateLater: 'Later',
    updateInstalling: 'Installing update...',
    updateFailed: 'Could not check for updates',
    updateInstallFailed: 'Could not install update',
    updateSaveSettings: 'Save settings changes before updating',
    updateInstallConfirm: 'Install version',
    updateRollbackConfirm: 'Restore version',
    updateRestartNotice: 'Warpy and the VPN will restart.',
    diagnosticsTitle: 'Diagnostics',
    exportDiagnostics: 'Save diagnostics',
    diagnosticsExporting: 'Preparing archive...',
    diagnosticsSaved: 'Saved to Downloads',
    diagnosticsError: 'Could not save diagnostics',
    subscriptionsEmpty: 'No subscriptions',
    subscriptionUpdated: 'Updated',
    subscriptionUnchanged: 'No changes',
    subscriptionUpdateError: 'Update failed',
    subscriptionUpdating: 'Updating...',
    refreshSubscription: 'Refresh subscription',
    cancel: 'Cancel',
    confirm: 'Add',
    close: 'Close',
    back: 'Back',
    profilesTitle: 'Profiles',
    emptyMsg: 'List is empty',
    shareTitle: 'Share',
    copy: 'Copy',
    runningAppsTitle: 'Select Applications',
    excludeSystem: 'Exclude system apps',
    searchPlaceholder: 'Search by name...',
    nothingFound: 'Nothing found',
    speedtestBtnStart: 'Start',
    speedtestRunning: 'Testing...',

    // Alerts & dynamic texts
    copySuccess: 'Link copied!',
    fileSelectError: 'Error selecting file: ',
    clipboardEmpty: 'Clipboard is empty',
    clipboardError: 'Could not use the clipboard: ',
    invalidFormat: 'The clipboard must contain a vless://, trojan://, hysteria2:// configuration or an HTTPS subscription URL',
    loadingProcesses: 'Loading processes...',
    errorPrefix: 'ERROR: ',
    errorLabel: 'Error: ',
    failedToStart: 'Failed to start',
    failedToConnect: 'Failed to connect',
    serviceUnavailable: 'The VPN service is temporarily unavailable',
    failedToDisconnect: 'Failed to disconnect',
    establishingTunnel: 'Establishing tunnel',
    addProfileHint: 'Add profile with the + button',
    cancelled: 'CANCELLED',
    mbps: 'Mbps',
    kbps: 'KB/s',
    ms: 'ms',
    saveBtn: 'Save',
    unsavedSettings: 'Save changes to settings?',
    discardChanges: "Don't save",
    restartConfirm: 'To apply changes, VPN needs to be restarted. Restart now?',
    deleteConfirm: 'Are you sure you want to delete profile',
    deleteGroupConfirm: 'Delete the group and all of its servers',
    yes: 'Yes',
    ok: 'OK',
    duplicateProfile: 'This profile is already added',
    connectImportedProfile: 'Connect to the new profile?',
    profileSwitchError: 'Could not switch server: ',
    settingsSaveError: 'Could not save settings: ',
    settingsLoadError: 'Profiles could not be opened. Warpy will not overwrite the data. Restart the app; if the error persists, save diagnostics.',
    settingsUnavailable: 'Profile storage is unavailable',
    trafficCheckFailed: 'No traffic passed through the VPN tunnel',
    notificationVpnRestoredTitle: 'VPN restored',
    notificationVpnRestoredBody: 'The secure connection is working again',
    notificationVpnFailedTitle: 'VPN disconnected',
    notificationVpnFailedBody: 'Warpy could not restore the secure connection',
    speedtestFailed: 'Could not complete the test',
    otherVpnActive: 'Disconnect the other VPN before connecting Warpy',
    subscriptionInvalidUrl: 'The clipboard must contain an HTTPS subscription URL',
    subscriptionError: 'Could not update subscription: ',
  }
};

T['zh-CN'] = {
  ...T.en,
  settings: '设置', connectionSection: '连接', protectionSection: '保护', routingSection: '隧道设置',
  profilesAndSubscriptions: '配置与订阅', appSection: '应用', advancedSection: '高级设置',
  lang: '语言', langRu: 'Русский', langEn: 'English',
  adblock: '拦截广告和跟踪器', adblockDescription: '过滤 VPN 内的已知广告域名',
  quic: 'QUIC 兼容性', quicDescription: '当网站通过 UDP 不稳定时禁用 QUIC',
  quicTooltip: 'QUIC 可以加速部分网站，但在某些网络中可能不稳定。仅在遇到问题时启用。',
  lan: '访问本地网络', lanDescription: '打印机、电视和其他本地设备仍可访问',
  resumeOnBoot: 'Windows 启动后连接', resumeOnBootDescription: '自动恢复受保护的连接',
  networkAutoProtect: '在公共网络中自动保护', networkAutoProtectDescription: '连接陌生公共网络时自动启用 VPN',
  warpyAuto: '选择最佳服务器', warpyAutoDescription: 'Warpy 比较可用服务器，仅在差异明显时切换',
  killSwitch: 'VPN 断开时阻止网络', killSwitchDescription: '防止流量在未提示的情况下直连',
  killSwitchTooltip: '如果 VPN 意外断开，Warpy 会暂时阻止网络，直到保护恢复。',
  apps: '应用', sites: '网站', appsOff: '全部使用 VPN', appsOnly: '仅所选项', appsBypass: '排除所选项',
  sitesOff: '全部使用 VPN', sitesOnly: '仅所选项', sitesBypass: '排除所选项',
  browse: '选择文件', running: '从运行中的应用选择',
  appsListPlaceholder: '例如：chrome.exe, telegram.exe', sitesListPlaceholder: '例如：instagram.com, youtube.com',
  connected: '已连接', connecting: '正在连接…', disconnected: '未连接', ping: '延迟', speed: '速度',
  addProfile: '添加配置', clipboardHint: 'Warpy 会自动识别配置或 HTTPS 订阅链接',
  addClipboardBtn: '从剪贴板导入', importLoading: '正在导入…', subscriptionsTitle: '订阅',
  updatesTitle: '更新', updateChannel: '更新通道', updateChannelDescription: '建议大多数用户使用 Stable',
  checkUpdates: '检查更新', updateChecking: '正在检查更新…', updateLatest: 'Warpy 已是最新版本',
  updateAvailable: '发现新版本', updateInstall: '安装', updateInstalling: '正在安装更新…',
  diagnosticsTitle: '诊断', exportDiagnostics: '保存诊断信息', diagnosticsSaved: '已保存到下载目录',
  subscriptionsEmpty: '没有订阅', subscriptionUpdated: '已更新', subscriptionUnchanged: '无变化',
  subscriptionUpdateError: '更新失败', subscriptionUpdating: '正在更新…', refreshSubscription: '刷新订阅',
  cancel: '取消', confirm: '添加', close: '关闭', back: '返回', profilesTitle: '配置', emptyMsg: '列表为空',
  shareTitle: '分享', copy: '复制', runningAppsTitle: '选择应用', excludeSystem: '排除系统应用',
  searchPlaceholder: '按名称搜索…', nothingFound: '未找到内容', speedtestBtnStart: '开始', speedtestRunning: '测试中…',
  copySuccess: '链接已复制！', clipboardEmpty: '剪贴板为空', invalidFormat: '剪贴板中必须包含 VPN 配置或 HTTPS 订阅链接',
  loadingProcesses: '正在加载进程…', failedToStart: '启动失败', failedToConnect: '连接失败',
  failedToDisconnect: '断开失败', establishingTunnel: '正在建立隧道', addProfileHint: '点击 + 添加配置',
  cancelled: '已取消', saveBtn: '保存', unsavedSettings: '保存设置更改？', discardChanges: '不保存',
  restartConfirm: '应用更改需要重启 VPN。现在重启吗？', deleteConfirm: '确定要删除配置',
  deleteGroupConfirm: '删除分组及其所有服务器', yes: '是', ok: '确定', duplicateProfile: '该配置已添加',
  connectImportedProfile: '连接到新配置？', otherVpnActive: '连接 Warpy 前请断开其他 VPN',
  connectionCheckTitle: '连接检查', connectionCheckIdle: '测量 VPN 质量并查看建议',
  connectionCheckRunning: '正在准备检查…', connectionCheckLatency: '正在测量延迟：{current}/{total}',
  connectionCheckDownload: '正在检查下载稳定性…', connectionCheckNoProfiles: '请先添加配置',
  connectionCheckFailed: '无法验证 VPN 连接', connectionCheckComplete: '检查完成',
  connectionCheckApplied: '已应用建议设置', checkAndTune: '检查', connectionReportTitle: '连接检查结果',
  connectionReportStable: '连接稳定', connectionReportImpaired: '检测到明显波动', connectionReportPoor: '连接不稳定',
  connectionReportStableDetail: '未检测到丢包，延迟保持稳定。', connectionReportLatencyDetail: '主要问题是响应延迟较高。',
  connectionReportJitterDetail: '主要问题是延迟不稳定。', connectionReportLossDetail: '部分诊断请求未收到响应。',
  connectionReportLatency: '延迟', connectionReportJitter: '抖动', connectionReportLoss: '丢包',
  connectionReportDownload: '下载', connectionReportRecommendations: '建议更改', connectionReportNoChanges: '无需更改设置。',
  connectionReportAutoTitle: '启用最佳服务器选择',
  connectionReportAutoDescription: 'Warpy 将持续比较服务器，并仅在多次测量确认后切换。',
  connectionReportMtuTitle: '恢复自动 MTU', connectionReportMtuDescription: 'Warpy 将停止使用手动设置的数据包大小。',
  connectionReportApply: '应用', connectionReportDone: '完成', langDescription: 'Warpy 界面语言',
  networkAutoProtectOff: '已关闭', networkAutoProtectTrusted: '受信任的网络',
  networkAutoProtectPublic: 'VPN 将自动连接', networkAutoProtectProtected: '公共网络已受保护',
  networkAutoProtectBlocked: '手动断开后已暂停', networkAutoProtectFailed: '自动连接失败',
  networkAutoProtectUnknown: '无法识别网络类型', networkAutoProtectOffline: '没有网络连接',
  networkAutoProtectNoProfiles: '添加配置以启用自动保护', warpyAutoOff: '已关闭',
  warpyAutoWatching: '正在监测连接质量', warpyAutoFaster: '已切换到更快的服务器',
  warpyAutoUnavailable: '服务器停止响应，已自动切换', warpyAutoReturned: '已返回所选服务器',
  warpyAutoPreferred: '首选服务器', warpyAutoFailed: '自动切换失败', killSwitchOff: '已关闭',
  killSwitchReady: '随 VPN 一起启用', killSwitchArmed: '已启用', killSwitchSuppressed: '流量由其他 VPN 保护',
  killSwitchSplitSuppressed: '绕过 VPN 时不可用', killSwitchError: '保护不可用',
  mtuDescription: '除非技术支持要求，否则请保持自动设置',
  mtuTooltip: 'MTU 控制网络数据包大小。错误的数值可能导致网站加载变慢或失败。',
  appsDescription: '选择哪些应用使用 VPN', sitesDescription: '添加需要单独规则的网站',
  allVpn: '全部使用 VPN', onlySelected: '仅所选项', bypassVpn: '绕过 VPN',
  updateStable: '稳定版', updateBeta: '测试版', updateRollbackAvailable: '发现可恢复版本',
  updateFailed: '无法检查更新', updateInstallFailed: '无法安装更新',
  updateSaveSettings: '更新前请先保存设置', updateInstallConfirm: '安装版本',
  updateRollbackConfirm: '恢复版本', updateRestartNotice: 'Warpy 和 VPN 将重新启动。',
  diagnosticsExporting: '正在准备压缩包…', diagnosticsError: '无法保存诊断信息',
  fileSelectError: '选择文件时出错：', clipboardError: '无法使用剪贴板：',
  errorPrefix: '错误：', errorLabel: '错误：', serviceUnavailable: 'VPN 服务暂时不可用',
  mbps: 'Mbps', kbps: 'KB/s', ms: '毫秒', profileSwitchError: '无法切换服务器：',
  settingsSaveError: '无法保存设置：',
  settingsLoadError: '无法打开配置。Warpy 不会覆盖数据。请重启应用；如果问题仍然存在，请保存诊断信息。',
  settingsUnavailable: '配置存储不可用', trafficCheckFailed: 'VPN 隧道中没有数据传输',
  notificationVpnRestoredTitle: 'VPN 已恢复', notificationVpnRestoredBody: '安全连接已恢复工作',
  notificationVpnFailedTitle: 'VPN 已断开', notificationVpnFailedBody: 'Warpy 无法恢复安全连接',
  speedtestFailed: '无法完成测试', subscriptionInvalidUrl: '剪贴板中必须包含 HTTPS 订阅链接',
  subscriptionError: '无法更新订阅：',
};

function systemLanguage() {
  const language = String(navigator.language || '').toLowerCase();
  if (language.startsWith('ru')) return 'ru';
  return 'en';
}

function effectiveLanguage() {
  return S.lang === 'ru' ? 'ru' : 'en';
}

function resolvedLanguageChoice(choice) {
  return choice === 'ru' ? 'ru' : 'en';
}

function t(key) {
  const dict = T[effectiveLanguage()] || T.en;
  return dict[key] || key;
}

function serverCountLabel(count) {
  const currentLang = effectiveLanguage();

  if (currentLang !== 'ru') return `${count} ${count === 1 ? 'server' : 'servers'}`;

  const mod10 = count % 10;
  const mod100 = count % 100;
  const noun = mod10 === 1 && mod100 !== 11
    ? 'сервер'
    : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)
      ? 'сервера'
      : 'серверов';
  return `${count} ${noun}`;
}

function localizeUI() {
  const currentLang = effectiveLanguage();

  const dict = T[currentLang] || T.en;
  document.documentElement.lang = currentLang;

  document.querySelectorAll('[data-i18n]').forEach(el => {
    const key = el.getAttribute('data-i18n');
    if (dict[key]) {
      if (el.tagName === 'INPUT' && (el.type === 'text' || el.type === 'number')) {
        el.placeholder = dict[key];
      } else if (el.tagName === 'TEXTAREA') {
        el.placeholder = dict[key];
      } else {
        el.textContent = dict[key];
      }
    }
  });
  document.querySelectorAll('[data-tooltip-i18n]').forEach(el => {
    const key = el.getAttribute('data-tooltip-i18n');
    if (dict[key]) el.dataset.tooltip = dict[key];
  });
  renderLanguageSetting();
  if (activeAppTooltipTarget && !activeAppTooltipTarget.isConnected) hideAppTooltip();

  // Localize main UI status texts
  const uiStatus = uiConnectionStatus();
  const conng = uiStatus === 'connecting';
  const conn = uiStatus === 'connected';
  const p = S.profiles[S.active];

  if (conn && p) $('server-name').textContent = p.name;
  else if (conng) $('server-name').textContent = dict.establishingTunnel;
  else if (!p) $('server-name').textContent = dict.addProfileHint;
  else $('server-name').textContent = p.name;

  $('status-alert').textContent = dict.connected;

  if ($('m-ping-label')) $('m-ping-label').textContent = dict.ping;
  if ($('m-speed-label')) $('m-speed-label').textContent = dict.speed;
  if ($('m-speed-unit')) $('m-speed-unit').textContent = dict.kbps;
  if ($('m-ping-unit')) $('m-ping-unit').textContent = dict.ms;

  // Localize bottom bar
  if (p) {
    const display = getProfileDisplay(p);
    const flag = createFlagElement(display.countryCode, display.flagEmoji);
    $('bottom-chip').textContent = p.protocol.charAt(0).toUpperCase() + p.protocol.slice(1);
    $('bottom-flag').replaceChildren();
    $('bottom-flag').classList.toggle('hidden', !flag);
    if (flag) $('bottom-flag').appendChild(flag);
    $('bottom-name').textContent = display.name;
  } else {
    $('bottom-chip').textContent = '';
    $('bottom-flag').replaceChildren();
    $('bottom-flag').classList.add('hidden');
    $('bottom-name').textContent = dict.emptyMsg;
  }
  if (!profilesViewGroup) $('profiles-drawer-title').textContent = dict.profilesTitle;
  renderKillSwitchStatus();
  renderWarpyAutoStatus();
  renderSubscriptions();
  renderUpdateControl();
}

let activeAppTooltipTarget = null;
let appTooltipHideTimer = null;

function positionAppTooltip(target) {
  const tooltip = $('app-tooltip');
  if (!tooltip || !target) return;

  const margin = 10;
  const gap = 8;
  const targetRect = target.getBoundingClientRect();
  const tooltipRect = tooltip.getBoundingClientRect();
  let left = targetRect.left + (targetRect.width - tooltipRect.width) / 2;
  left = Math.max(margin, Math.min(left, window.innerWidth - tooltipRect.width - margin));

  let top = targetRect.top - tooltipRect.height - gap;
  if (top < margin) top = targetRect.bottom + gap;
  top = Math.max(margin, Math.min(top, window.innerHeight - tooltipRect.height - margin));

  tooltip.style.left = `${Math.round(left)}px`;
  tooltip.style.top = `${Math.round(top)}px`;
}

function showAppTooltip(target) {
  const tooltip = $('app-tooltip');
  const text = target?.dataset.tooltip;
  if (!tooltip || !text) return;
  if (appTooltipHideTimer) clearTimeout(appTooltipHideTimer);
  activeAppTooltipTarget = target;
  tooltip.textContent = text;
  tooltip.classList.add('visible');
  positionAppTooltip(target);
}

function hideAppTooltip() {
  const tooltip = $('app-tooltip');
  if (tooltip) tooltip.classList.remove('visible');
  activeAppTooltipTarget = null;
}

function scheduleAppTooltipHide(target) {
  if (appTooltipHideTimer) clearTimeout(appTooltipHideTimer);
  appTooltipHideTimer = setTimeout(() => {
    const tooltip = $('app-tooltip');
    if (target?.matches(':hover') || tooltip?.matches(':hover')) return;
    hideAppTooltip();
  }, 140);
}

function bindAppTooltips() {
  document.querySelectorAll('.setting-help').forEach(target => {
    target.addEventListener('pointerenter', () => showAppTooltip(target));
    target.addEventListener('pointerleave', () => scheduleAppTooltipHide(target));
    target.addEventListener('focus', () => showAppTooltip(target));
    target.addEventListener('blur', hideAppTooltip);
  });
  const tooltip = $('app-tooltip');
  tooltip?.addEventListener('pointerenter', () => {
    if (appTooltipHideTimer) clearTimeout(appTooltipHideTimer);
  });
  tooltip?.addEventListener('pointerleave', () => scheduleAppTooltipHide(activeAppTooltipTarget));
  $('overlay-settings').addEventListener('scroll', hideAppTooltip, true);
  window.addEventListener('resize', () => {
    if (activeAppTooltipTarget) positionAppTooltip(activeAppTooltipTarget);
  });
}

function renderLanguageSetting() {
  const value = $('language-setting-value');
  if (!value) return;
  const selected = $('s-lang')?.value || S.lang || systemLanguage();
  const resolved = resolvedLanguageChoice(selected);
  value.textContent = resolved === 'ru' ? 'Русский' : 'English';
  document.querySelectorAll('[data-language]').forEach(button => {
    button.classList.toggle('active', button.dataset.language === selected);
  });
}

/* ── State ─────────────────────────────────────── */
const S = {
  profiles: [],
  subscriptions: [],
  active: 0,
  preferredProfileKey: '',
  adblock: false, quic: false, lan: false, killSwitch: false,
  resumeOnBoot: false, networkAutoProtect: false, warpyAuto: false, mtu: 0,
  updateChannel: 'stable',
  appsMode: 'off',
  appsList: [],
  sitesMode: 'off',
  sitesList: [],
  status: 'stopped',       // service-owned: stopped | connecting | connected | error
  commandPending: null,    // renderer-owned: start | stop | null
  commandError: '',
  connectedAt: 0,
  uptimeTimer: null,
  speedTimer: null,
  pingTimer: null,
  lastNetworkStats: null,
  statusCheckRunning: false,
  statusCheckFailures: 0,
  startCommandAttempt: 0,
  networkMetricsRunning: false,
  resumeCheckRunning: false,
  connectAttempt: 0,
  showConnectedAlert: false,
  killSwitchServiceStatus: 'Off',
  vpnHealth: null,
  healthCheckRunning: false,
  lastHandledAutoSwitchAt: 0,
  recoveryNotificationPending: false,
  systemNotificationsReady: false,
  runtimeProfileKeys: [],
  networkContext: normalizeNetworkContext(null),
  networkAutoHandledGeneration: -1,
  networkAutoBlocked: false,
  networkAutoBlockedReason: '',
  networkAutoConnectRunning: false,
  settingsLoaded: false,
  lang: systemLanguage(),
};
const refreshingSubscriptions = new Set();
const SUBSCRIPTION_AUTO_POLL_INTERVAL_MS = 5 * 60 * 1000;
let subscriptionAutoRefreshTimer = null;
let subscriptionAutoRefreshTimeout = null;
let trayCommandRunning = false;
let trayMenuSyncRunning = false;
let pendingTrayMenuUpdate = null;
let lastTrayMenuSignature = '';
let failedTrayMenuSignature = '';
let subscriptionAutoRefreshRunning = false;
let profilesViewGroup = null;
let connectionCheckRunning = false;
let pendingConnectionRecommendations = [];
const shouldDeliverSystemNotification = createNotificationDedupe();

function uiConnectionStatus() {
  if (S.commandPending === 'start' && S.status === 'stopped') return 'connecting';
  return S.status;
}

/* ── DOM refs ──────────────────────────────────── */
const $ = id => document.getElementById(id);
const powerBtn       = $('power-btn');
const uptimeEl       = $('uptime');
const serverName     = $('server-name');
const protocolChip   = $('protocol-chip');
const metricsEl      = $('metrics');
const mSpeed         = $('m-speed');
const mPing          = $('m-ping');
const errorMsg       = $('error-msg');
const bottomChip     = $('bottom-chip');
const bottomName     = $('bottom-name');
const canvas         = $('particles');
const ctx            = canvas.getContext('2d');

/* ── High-DPI Canvas Scaling ───────────────────── */
const dpr = window.devicePixelRatio || 1;
canvas.width = 340 * dpr;
canvas.height = 340 * dpr;
ctx.scale(dpr, dpr);

/* ── Particle system (1:1 port from Android Kotlin) ── */
function pseudoRandom(index, salt) {
  const v = ((index + 1) * 1103515245 + salt * 12345) & 0x7fffffff;
  return (v % 1000) / 1000;
}

const PARTICLE_COUNT = 86;
const particles = [];
for (let i = 0; i < PARTICLE_COUNT; i++) {
  particles.push({
    angle: pseudoRandom(i * i + 19, 11) * 360,
    introDelay: pseudoRandom(i * 17 + 5, 41) * 0.42,
    cycleSeconds: 1.85 + pseudoRandom(i * 13 + 7, 29) * 1.15,
    pullDelay: pseudoRandom(i * 31 + 9, 47) * 0.42,
    radius: 1.4 + (i % 4) * 0.35,
    edgeOffset: -12 + pseudoRandom(i, 37) * 24,
    endRadius: 15 + pseudoRandom(i, 51) * 12,
    wobbleDistance: 0.8 + pseudoRandom(i, 83) * 3.5,
    phase: pseudoRandom(i, 89) * 360,
    secondPhase: pseudoRandom(i, 103) * 360,
    colorDelay: pseudoRandom(i * 23 + 3, 71) * 0.18,
    colorIndex: i % 3,
  });
}

const PALETTE_PAIRS = [
  { start: {r: 255, g: 255, b: 255}, end: {r: 0, g: 192, b: 127} },
  { start: {r: 221, g: 221, b: 221}, end: {r: 68, g: 215, b: 164} },
  { start: {r: 170, g: 170, b: 170}, end: {r: 0, g: 144, b: 94} }
];

let animStart = null;
let animActive = false;
let rafId = null;
let connectedStart = null;
let disconnectTime = null;
let isWindowVisible = true;
let lastFrameTime = 0;

function drawFrame(timestamp) {
  if (!isWindowVisible) {
    rafId = null;
    return;
  }

  const connectionStatus = uiConnectionStatus();
  const isParticlesActive = connectionStatus === 'connected' || connectionStatus === 'connecting';

  // Throttle particles loop to ~30fps (wait at least 30ms between draws)
  // But let other transition animations (like disconnect) run at full 60fps
  if (isParticlesActive && lastFrameTime && timestamp - lastFrameTime < 30) {
    if (animActive || disconnectTime) {
      rafId = requestAnimationFrame(drawFrame);
    } else {
      rafId = null;
    }
    return;
  }
  lastFrameTime = timestamp;

  ctx.clearRect(0, 0, 340, 340);

  const cx = 170, cy = 170;
  const buttonRadius = 86; // 172 / 2

  let visibleCircleFillProgress = 1;
  let showParticles = false;

  if (!animStart) animStart = timestamp;
  const elapsed = (timestamp - animStart) / 1000;

  // ── 1. Circle fill & hole animations (1:1 Kotlin logic) ──
  if (connectionStatus === 'connected') {
    visibleCircleFillProgress = 0;
    showParticles = true;
    if (connectedStart === null) {
      connectedStart = elapsed;
    }
  } else if (connectionStatus === 'connecting') {
    visibleCircleFillProgress = 0;
    showParticles = true;
  } else if (connectionStatus === 'stopped' || connectionStatus === 'error') {
    if (disconnectTime) {
      const elapsedDisconnect = (timestamp - disconnectTime) / 1000;
      const stopFillDuration = 0.34;
      const progress = Math.min(elapsedDisconnect / stopFillDuration, 1);
      const easedFill = 1 - Math.pow(1 - progress, 3); // easeOutCubic

      // Draw the full grey circle background
      ctx.beginPath();
      ctx.arc(cx, cy, buttonRadius, 0, Math.PI * 2);
      ctx.fillStyle = '#1c1c1e';
      ctx.fill();

      // Draw the shrinking black circle on top
      const shrinkRadius = buttonRadius * 1.16 * (1 - easedFill);
      if (shrinkRadius > 0) {
        ctx.beginPath();
        ctx.arc(cx, cy, shrinkRadius, 0, Math.PI * 2);
        ctx.fillStyle = '#000000';
        ctx.fill();
      }

      visibleCircleFillProgress = easedFill;
      if (progress >= 1) {
        disconnectTime = null; // Animation finished
      }
    } else {
      visibleCircleFillProgress = 1;
      // Draw the static full grey circle
      ctx.beginPath();
      ctx.arc(cx, cy, buttonRadius, 0, Math.PI * 2);
      ctx.fillStyle = '#1c1c1e';
      ctx.fill();
    }
    showParticles = false;
  }

  // ── 2. Particles field drawing (1:1 Kotlin logic) ──
  if (showParticles) {
    const edgeRadius = 90;
    const isConn = connectionStatus === 'connected';

    // 180ms delay before color animation starts
    const pullStartedAt = connectedStart !== null ? connectedStart + 0.18 : null;

    let colorProgress = 0;
    if (isConn && pullStartedAt !== null) {
      colorProgress = Math.min(Math.max((elapsed - pullStartedAt) / 0.76, 0), 1);
    }

    for (const p of particles) {
      const edgeTime = Math.max(elapsed - p.introDelay, 0);
      const introAlpha = Math.min(edgeTime / 0.2, 1);

      const pullElapsed = pullStartedAt !== null ? elapsed - pullStartedAt - p.pullDelay : -1;
      const pulling = pullStartedAt !== null && pullElapsed > 0;
      const local = pulling ? (pullElapsed / p.cycleSeconds) % 1 : 0;
      const firstPullCycle = pulling && pullElapsed < p.cycleSeconds;
      const fadeIn = (!pulling || firstPullCycle) ? 1 : Math.min(local / 0.1, 1);
      const fadeOut = pulling ? Math.min((1 - local) / 0.22, 1) : 1;

      const blink = 0.78 + 0.22 * Math.sin((p.phase + elapsed * 80) * Math.PI / 180);

      const particleColorProgress = Math.min(Math.max((colorProgress - p.colorDelay) / (1 - p.colorDelay), 0), 1);
      const pair = PALETTE_PAIRS[p.colorIndex];
      const colorR = Math.round(pair.start.r + (pair.end.r - pair.start.r) * particleColorProgress);
      const colorG = Math.round(pair.start.g + (pair.end.g - pair.start.g) * particleColorProgress);
      const colorB = Math.round(pair.start.b + (pair.end.b - pair.start.b) * particleColorProgress);

      const activeAlpha = 0.64 + (0.82 - 0.64) * particleColorProgress;

      const angleRad = p.angle * Math.PI / 180;
      const rx = Math.cos(angleRad);
      const ry = Math.sin(angleRad);
      const tx = -ry, ty = rx;

      const startR = edgeRadius + p.edgeOffset;

      const pullStart = 0.34;
      const pull = pulling ? Math.min(Math.max((local - pullStart) / (1 - pullStart), 0), 1) : 0;
      const accelerated = Math.pow(pull, 4);
      const travelRadius = startR + (p.endRadius - startR) * accelerated;
      const edgeOrbit = (1 - pull) * 4.2;

      const tangentDrift = (p.wobbleDistance + edgeOrbit) * Math.sin((p.phase + elapsed * 65) * Math.PI / 180);
      const radialDrift = p.wobbleDistance * 0.25 * Math.sin((p.secondPhase + elapsed * 55) * Math.PI / 180);

      const x = cx + rx * travelRadius + tx * tangentDrift + rx * radialDrift;
      const y = cy + ry * travelRadius + ty * tangentDrift + ry * radialDrift;

      const r = p.radius * (1 - accelerated * 0.67) * (0.8 + 0.35 * blink);
      const alpha = activeAlpha * introAlpha * fadeIn * fadeOut * blink;

      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fillStyle = `rgba(${colorR},${colorG},${colorB},${alpha})`;
      ctx.fill();
    }
  }

  if ((animActive || disconnectTime) && isWindowVisible) {
    rafId = requestAnimationFrame(drawFrame);
  } else {
    rafId = null;
  }
}



function startAnimation() {
  if (animActive) return;
  animActive = true;
  animStart = null;
  connectedStart = null;
  disconnectTime = null;
  if (rafId) cancelAnimationFrame(rafId);
  rafId = requestAnimationFrame(drawFrame);
}

function stopAnimation() {
  animActive = false;
  connectedStart = null;
  disconnectTime = performance.now();
  if (rafId) cancelAnimationFrame(rafId);
  rafId = requestAnimationFrame(drawFrame);
}

/* ── Init ──────────────────────────────────────── */
async function init() {
  const settingsLoaded = await loadSettings();
  bindEvents();
  if (!settingsLoaded) showMessage('settingsLoadError');
  await bindTrayCommands();
  await bindUpdateProgressEvents();
  syncUI();
  let autostartLaunch = false;
  try {
    $('settings-version').textContent = `v${await invoke('get_app_version')}`;
  } catch (error) {
    console.error('Version lookup failed:', error);
  }
  try {
    autostartLaunch = (await invoke('is_autostart_launch')) === true;
    if (settingsLoaded) {
      await updateResumeOnBootPolicy();
      await updateWarpyAutoPolicy();
    }
  } catch (error) {
    console.error('Startup policy initialization failed:', error);
    await logMsg(`Startup policy initialization failed: ${error}`);
  }
  isWindowVisible = !document.hidden;
  await initializeSystemNotifications(!autostartLaunch && isWindowVisible);
  await restoreBackendState();
  try {
    if (await invoke('is_post_update_launch')) {
      await invoke('confirm_launch_health');
    }
  } catch (error) {
    await logMsg(`Post-update launch confirmation failed: ${error}`);
  }
  requestAnimationFrame(drawFrame);
  if (autostartLaunch && S.resumeOnBoot && S.profiles.length && S.status === 'stopped') {
    await startVpnAfterSystemBoot();
  } else if (S.networkAutoProtect) {
    await checkStatus();
  }
  setInterval(checkStatus, 2000);
  startSubscriptionAutoRefreshScheduler();
  startAutomaticUpdateChecks();
}

/* ── Events ────────────────────────────────────── */
function bindEvents() {
  powerBtn.onclick = () => {
    if (S.profiles.length) void toggleVpn();
    else openAddProfile();
  };
  $('btn-add').onclick = openAddProfile;
  $('btn-settings').onclick = () => show('overlay-settings');
  $('btn-profiles').onclick = () => {
    profilesViewGroup = null;
    renderProfiles();
    show('overlay-profiles');
  };
  $('btn-speed').onclick = () => { showSpeedtest(); };
  $('btn-check-update').onclick = () => { void handleUpdateAction(); };
  $('update-banner-install').onclick = () => { void installPendingUpdate(); };
  $('update-banner-later').onclick = () => {
    if (pendingUpdate) dismissedUpdateVersion = pendingUpdate.version;
    renderUpdateControl();
  };
  $('profiles-add-btn').onclick = openAddProfile;
  $('profiles-back-btn').onclick = () => {
    profilesViewGroup = null;
    renderProfiles();
  };
  $('close-profiles').onclick = () => hide('overlay-profiles');
  $('close-settings').onclick = () => { void requestSettingsClose(); };
  $('btn-open-tunneling').onclick = () => showSettingsPage('tunneling');
  $('close-tunneling').onclick = () => showSettingsPage('main');
  $('btn-language').onclick = () => {
    renderLanguageSetting();
    show('overlay-language');
  };
  $('close-language').onclick = () => hide('overlay-language');
  document.querySelectorAll('[data-language]').forEach(button => {
    button.onclick = () => {
      $('s-lang').value = button.dataset.language;
      renderLanguageSetting();
      updateSettingsSaveState();
      hide('overlay-language');
    };
  });
  $('close-share').onclick = () => hide('overlay-share');
  $('cancel-add').onclick = () => hide('overlay-add');

  $('btn-confirm-ok').onclick = () => {
    hide('overlay-confirm');
    if (confirmResolver) {
      confirmResolver(true);
      confirmResolver = null;
    }
  };
  $('btn-confirm-cancel').onclick = () => {
    hide('overlay-confirm');
    if (confirmResolver) {
      confirmResolver(false);
      confirmResolver = null;
    }
  };
  $('btn-message-ok').onclick = () => hide('overlay-message');
  $('connection-report-x').onclick = () => hide('overlay-connection-report');
  $('connection-report-close').onclick = () => hide('overlay-connection-report');
  $('connection-report-apply').onclick = () => { void applyConnectionRecommendations(); };
  $('settings-unsaved-cancel').onclick = () => hide('overlay-settings-unsaved');
  $('settings-unsaved-discard').onclick = discardSettingsChanges;
  $('settings-unsaved-save').onclick = async () => {
    hide('overlay-settings-unsaved');
    await saveSettingsFromInputs();
  };

  // Profile and subscription clipboard import
  $('btn-clipboard-import').onclick = addFromClipboard;

  // App Exclusions Browse & Running buttons
  $('btn-app-browse').onclick = async () => {
    try {
      const selected = await invoke('select_executable');
      if (selected) {
        const filename = selected.split(/[\\/]/).pop();
        const listEl = $('s-apps-list');
        let val = listEl.value.trim();
        if (val) {
          const items = val.split(',').map(x => x.trim()).filter(Boolean);
          if (!items.includes(filename)) {
            items.push(filename);
            listEl.value = items.join(', ');
          }
        } else {
          listEl.value = filename;
        }
        listEl.dispatchEvent(new Event('change'));
      }
    } catch(e) {
      showMessage(t('fileSelectError') + e, false);
    }
  };

  $('btn-app-running').onclick = async () => {
    show('overlay-running-apps');
    await loadRunningProcesses();
  };

  $('close-running-apps').onclick = () => hide('overlay-running-apps');
  $('cancel-running-apps').onclick = () => hide('overlay-running-apps');
  $('confirm-running-apps').onclick = confirmRunningAppsSelection;
  $('running-apps-exclude-system').onchange = loadRunningProcesses;
  $('running-apps-search').oninput = renderRunningProcessesList;

  // Paste handler on window (for Ctrl+V links import)
  window.addEventListener('paste', e => {
    if (!$('overlay-add').classList.contains('hidden')) {
      const text = e.clipboardData.getData('text');
      if (text) {
        void importClipboardText(text);
      }
    }
  });

  // Speedtest buttons
  $('speedtest-btn-close').onclick = () => hideSpeedtest();
  $('speedtest-btn-action').onclick = () => { toggleSpeedtestRun(); };
  document.querySelectorAll('#overlay-settings input, #overlay-settings select, #overlay-settings textarea')
    .forEach(control => {
      control.addEventListener('input', updateSettingsSaveState);
      control.addEventListener('change', updateSettingsSaveState);
    });
  bindAppTooltips();

  // Window controls and dragging
  if (window.__TAURI__ && window.__TAURI__.window) {
    try {
      const appWindow = window.__TAURI__.window.getCurrentWindow();
      $('win-min').onclick = () => appWindow.minimize();
      $('win-close').onclick = () => appWindow.close();

      // Bulletproof JS dragging handler for borderless custom window
      document.onmousedown = e => {
        const dragRegion = e.target.closest('.top-bar, .settings-drag-bar, .dialog-settings .drawer-head');
        if (dragRegion && !e.target.closest('button, input, select, textarea, a')) {
          appWindow.startDragging();
        }
      };
    } catch(e) { console.error('Tauri window API error:', e); }
  }

  // Click outside to close overlays
  const overlays = ['overlay-profiles', 'overlay-settings', 'overlay-language', 'overlay-share', 'overlay-add', 'overlay-speedtest', 'overlay-running-apps', 'overlay-settings-unsaved', 'overlay-message', 'overlay-connection-report'];
  overlays.forEach(id => {
    $(id).onclick = e => {
      if (e.target === $(id) || e.target.classList.contains('overlay-bg')) {
        if (id === 'overlay-speedtest') { hideSpeedtest(); }
        else if (id === 'overlay-settings') { void requestSettingsClose(); }
        else { hide(id); }
      }
    };
  });

  window.addEventListener('keydown', e => {
    if (e.key !== 'Escape') return;
    if (!$('overlay-connection-report').classList.contains('hidden')) {
      e.preventDefault();
      hide('overlay-connection-report');
    } else if (!$('overlay-message').classList.contains('hidden')) {
      e.preventDefault();
      hide('overlay-message');
    } else if (!$('overlay-settings-unsaved').classList.contains('hidden')) {
      e.preventDefault();
      hide('overlay-settings-unsaved');
    } else if (!$('overlay-settings').classList.contains('hidden')) {
      e.preventDefault();
      if (!$('settings-tunneling-page').classList.contains('hidden')) showSettingsPage('main');
      else void requestSettingsClose();
    }
  });

  $('btn-copy-share').onclick = async () => {
    try {
      await navigator.clipboard.writeText($('share-link').value);
      showMessage('copySuccess');
    } catch (error) {
      showMessage(t('clipboardError') + error, false);
    }
  };

  // Settings Save Button Click
  $('btn-save-settings').onclick = () => { void saveSettingsFromInputs(); };
}

async function bindTrayCommands() {
  const eventApi = window.__TAURI__?.event;
  if (typeof eventApi?.listen !== 'function') return;
  try {
    await eventApi.listen('warpy://tray-command', event => {
      void handleTrayCommand(event.payload);
    });
  } catch (error) {
    await logMsg(`Tray command listener failed: ${error}`);
  }
}

async function handleTrayCommand(command) {
  if (trayCommandRunning || !command || typeof command !== 'object') return;
  trayCommandRunning = true;
  try {
    if (command.type === 'toggle') {
      await toggleVpn();
      if (S.status === 'error') await revealMainWindow();
      return;
    }
    if (command.type === 'selectProfile') {
      const index = Number(command.index);
      if (!Number.isInteger(index) || index < 0 || index >= S.profiles.length) return;
      if (index === S.active) return;
      const selected = await selectProfile(index, { closeProfiles: false });
      if (!selected) await revealMainWindow();
    }
  } catch (error) {
    await logMsg(`Tray command failed: ${error}`);
    await revealMainWindow();
  } finally {
    trayCommandRunning = false;
    scheduleTrayMenuUpdate();
  }
}

async function revealMainWindow() {
  try {
    const appWindow = window.__TAURI__?.window?.getCurrentWindow?.();
    if (!appWindow) return;
    await appWindow.show();
    await appWindow.unminimize();
    await appWindow.setFocus();
  } catch (error) {
    await logMsg(`Could not show Warpy window: ${error}`);
  }
}

function trayMenuSnapshot() {
  const language = effectiveLanguage();
  return {
    status: S.status,
    language,
    active: S.active,
    profiles: buildTrayProfileSnapshots(S.profiles, language, getProfileDisplay),
  };
}

function scheduleTrayMenuUpdate() {
  if (!window.__TAURI__) return;
  const snapshot = trayMenuSnapshot();
  const signature = JSON.stringify(snapshot);
  if (
    (signature === lastTrayMenuSignature || signature === failedTrayMenuSignature)
    && !pendingTrayMenuUpdate
  ) return;
  pendingTrayMenuUpdate = { snapshot, signature };
  if (!trayMenuSyncRunning) void flushTrayMenuUpdate();
}

async function flushTrayMenuUpdate() {
  trayMenuSyncRunning = true;
  try {
    while (pendingTrayMenuUpdate) {
      const update = pendingTrayMenuUpdate;
      pendingTrayMenuUpdate = null;
      if (update.signature === lastTrayMenuSignature) continue;
      try {
        await invoke('update_tray_menu', { snapshot: update.snapshot });
        lastTrayMenuSignature = update.signature;
        failedTrayMenuSignature = '';
      } catch (error) {
        failedTrayMenuSignature = update.signature;
        await logMsg(`Tray menu update failed: ${error}`);
      }
    }
  } finally {
    trayMenuSyncRunning = false;
  }
}

async function saveSettingsFromInputs() {
  const previousSettings = settingsStateSnapshot();
  const previousTunnelSettings = tunnelSettingsSnapshot();
  const cleanSites = $('s-sites-list').value.split(',')
    .map(s => {
      let clean = s.trim().toLowerCase();
      clean = clean.replace(/^(https?:\/\/)?(www\.)?/, '');
      clean = clean.split('/')[0].split(':')[0];
      return clean;
    })
    .filter(s => s.length > 0);

  const cleanApps = $('s-apps-list').value.split(',').map(s => s.trim()).filter(s => s.length > 0);

  S.quic = $('s-quic').checked;
  S.lan = $('s-lan').checked;
  S.killSwitch = $('s-kill-switch').checked;
  S.resumeOnBoot = $('s-resume-on-boot').checked;
  S.networkAutoProtect = false;
  S.mtu = normalizeMtu($('s-mtu').value);
  S.lang = normalizeChoice($('s-lang').value, ['ru', 'en'], systemLanguage());
  S.appsMode = normalizeChoice($('s-apps-mode').value, ['off', 'only', 'bypass'], 'off');
  S.appsList = cleanApps;
  S.sitesMode = normalizeChoice($('s-sites-mode').value, ['off', 'only', 'bypass'], 'off');
  S.sitesList = cleanSites;

  try {
    await save();
    await updateResumeOnBootPolicy();
    await updateWarpyAutoPolicy();
  } catch (error) {
    Object.assign(S, previousSettings);
    try {
      await save();
      await updateResumeOnBootPolicy();
      await updateWarpyAutoPolicy();
    } catch { /* keep the original error */ }
    loadSettingsInputs();
    updateSettingsSaveState();
    showMessage(t('settingsSaveError') + error, false);
    return false;
  }

  localizeUI();
  hide('overlay-settings');
  if (!S.networkAutoProtect) {
    S.networkAutoBlocked = false;
    S.networkAutoBlockedReason = '';
    S.networkAutoHandledGeneration = -1;
  }

  const tunnelChanged = previousTunnelSettings !== tunnelSettingsSnapshot();
  if (tunnelChanged && (S.status === 'connected' || S.status === 'connecting')) {
    const shouldRestart = await showConfirm('restartConfirm');
    if (shouldRestart) {
      await stopVpn();
      await startVpn();
    }
  }
  if (S.networkAutoProtect) await checkStatus();
  return true;
}

function diagnosticsSettingsSummary() {
  return {
    schemaVersion: 1,
    profileCount: S.profiles.length,
    subscriptionCount: S.subscriptions.length,
    adblock: S.adblock,
    quic: S.quic,
    lan: S.lan,
    killSwitch: S.killSwitch,
    resumeOnBoot: S.resumeOnBoot,
    networkAutoProtect: S.networkAutoProtect,
    warpyAuto: S.warpyAuto,
    mtu: S.mtu,
    appsMode: S.appsMode,
    appRuleCount: S.appsList.length,
    sitesMode: S.sitesMode,
    siteRuleCount: S.sitesList.length,
    language: S.lang,
    updateChannel: S.updateChannel,
  };
}

async function exportDiagnostics() {
  const button = $('btn-export-diagnostics');
  const status = $('diagnostics-status');
  if (button.disabled) return;
  button.disabled = true;
  button.classList.add('exporting');
  status.dataset.state = '';
  status.textContent = t('diagnosticsExporting');
  try {
    const path = await invoke('export_diagnostics', {
      settingsSummary: diagnosticsSettingsSummary(),
    });
    const filename = String(path).split(/[\\/]/).pop();
    status.dataset.state = 'saved';
    status.textContent = `${t('diagnosticsSaved')}: ${filename}`;
  } catch (error) {
    status.dataset.state = 'error';
    status.textContent = t('diagnosticsError');
    await logMsg(`Diagnostics export failed: ${error}`);
  } finally {
    button.disabled = false;
    button.classList.remove('exporting');
  }
}

let pendingUpdate = null;
let updateCheckRunning = false;
let updateInstallRunning = false;
let updateUiState = { kind: 'idle', version: '', percent: null };
let dismissedUpdateVersion = '';
const UPDATE_AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;

function selectedUpdateChannel() {
  return 'stable';
}

function renderUpdateControl() {
  const button = $('btn-check-update');
  const status = $('update-status');
  if (!button || !status) return;

  const label = button.querySelector('span');
  const currentChannel = selectedUpdateChannel();
  const actionable = pendingUpdate?.channel === currentChannel;
  let buttonText = t('checkUpdates');
  let statusText = '';
  let state = '';
  let disabled = false;

  if (updateInstallRunning) {
    state = 'installing';
    disabled = true;
    buttonText = t('updateInstalling');
    statusText = updateUiState.percent === null
      ? t('updateInstalling')
      : `${t('updateInstalling')} ${updateUiState.percent}%`;
  } else if (updateCheckRunning) {
    state = 'checking';
    disabled = true;
    buttonText = t('updateChecking');
    statusText = t('updateChecking');
  } else if (actionable && ['available', 'rollback', 'saveSettings'].includes(updateUiState.kind)) {
    state = 'available';
    buttonText = `${t('updateInstall')} ${pendingUpdate.version}`;
    statusText = updateUiState.kind === 'saveSettings'
      ? t('updateSaveSettings')
      : `${t(pendingUpdate.rollback ? 'updateRollbackAvailable' : 'updateAvailable')} ${pendingUpdate.version}`;
  } else if (updateUiState.kind === 'latest') {
    statusText = t('updateLatest');
  } else if (updateUiState.kind === 'error') {
    state = 'error';
    statusText = t('updateFailed');
  } else if (updateUiState.kind === 'installError') {
    state = 'error';
    statusText = t('updateInstallFailed');
  }

  button.disabled = disabled;
  button.dataset.state = state;
  label.textContent = buttonText;
  status.dataset.state = pendingUpdate?.rollback && actionable ? 'rollback' : state;
  status.textContent = statusText;
  renderUpdateBanner();
}

function renderUpdateBanner() {
  const banner = $('update-banner');
  if (!banner) return;
  const actionable = pendingUpdate && pendingUpdate.channel === selectedUpdateChannel();
  const visible = updateInstallRunning || (
    actionable &&
    pendingUpdate.version !== dismissedUpdateVersion &&
    ['available', 'rollback'].includes(updateUiState.kind)
  );
  banner.classList.toggle('hidden', !visible);
  banner.classList.toggle('installing', updateInstallRunning);
  if (!visible) return;

  const percent = updateUiState.percent;
  $('update-banner-title').textContent = updateInstallRunning
    ? t('updateInstalling')
    : `${t(pendingUpdate.rollback ? 'updateRollbackAvailable' : 'updateAvailable')} ${pendingUpdate.version}`;
  $('update-banner-detail').textContent = updateInstallRunning && percent !== null
    ? `${percent}%`
    : t('updateRestartNotice');
  $('update-banner-progress').style.width = `${percent ?? 0}%`;
}

async function bindUpdateProgressEvents() {
  const eventApi = window.__TAURI__?.event;
  if (typeof eventApi?.listen !== 'function') return;
  try {
    await eventApi.listen('warpy://update-progress', event => {
      if (!updateInstallRunning) return;
      const value = Number(event.payload?.percent);
      updateUiState = {
        ...updateUiState,
        kind: 'installing',
        percent: Number.isFinite(value) ? Math.min(100, Math.max(0, Math.round(value))) : null,
      };
      renderUpdateControl();
    });
  } catch (error) {
    await logMsg(`Update progress listener failed: ${error}`);
  }
}

async function checkForUpdate({ silent = false } = {}) {
  if (updateCheckRunning || updateInstallRunning) return;
  const channel = selectedUpdateChannel();
  pendingUpdate = null;
  updateCheckRunning = true;
  if (!silent) updateUiState = { kind: 'checking', version: '', percent: null };
  renderUpdateControl();
  try {
    const update = await invoke('check_for_update', { channel });
    if (!update) {
      if (!silent) updateUiState = { kind: 'latest', version: '', percent: null };
    } else {
      pendingUpdate = {
        channel,
        version: String(update.version),
        rollback: update.rollback === true,
      };
      updateUiState = {
        kind: pendingUpdate.rollback ? 'rollback' : 'available',
        version: pendingUpdate.version,
        percent: null,
      };
    }
  } catch (error) {
    if (!silent) updateUiState = { kind: 'error', version: '', percent: null };
    await logMsg(`Update check failed: ${error}`);
  } finally {
    updateCheckRunning = false;
    renderUpdateControl();
  }
}

function startAutomaticUpdateChecks() {
  setTimeout(() => { void checkForUpdate({ silent: true }); }, 12_000);
  setInterval(() => { void checkForUpdate({ silent: true }); }, UPDATE_AUTO_CHECK_INTERVAL_MS);
}

async function installPendingUpdate() {
  if (!pendingUpdate || updateInstallRunning || updateCheckRunning) return;
  if (settingsOpenSnapshot !== null && settingsOpenSnapshot !== settingsInputsSnapshot()) {
    updateUiState = { ...updateUiState, kind: 'saveSettings' };
    renderUpdateControl();
    return;
  }

  const action = pendingUpdate.rollback ? t('updateRollbackConfirm') : t('updateInstallConfirm');
  const confirmed = await showConfirm(
    `${action} ${pendingUpdate.version}? ${t('updateRestartNotice')}`,
    false,
  );
  if (!confirmed) return;

  updateInstallRunning = true;
  updateUiState = { kind: 'installing', version: pendingUpdate.version, percent: null };
  renderUpdateControl();
  try {
    await invoke('install_update', {
      channel: pendingUpdate.channel,
      expectedVersion: pendingUpdate.version,
    });
  } catch (error) {
    updateInstallRunning = false;
    updateUiState = { kind: 'installError', version: pendingUpdate.version, percent: null };
    renderUpdateControl();
    await logMsg(`Update installation failed: ${error}`);
  }
}

async function handleUpdateAction() {
  const channel = selectedUpdateChannel();
  if (pendingUpdate?.channel === channel && (updateUiState.kind === 'available' || updateUiState.kind === 'rollback' || updateUiState.kind === 'saveSettings')) {
    await installPendingUpdate();
    return;
  }
  await checkForUpdate();
}

function tunnelSettingsSnapshot() {
  return JSON.stringify({
    adblock: S.adblock,
    quic: S.quic,
    lan: S.lan,
    killSwitch: S.killSwitch,
    mtu: S.mtu,
    appsMode: S.appsMode,
    appsList: S.appsList,
    sitesMode: S.sitesMode,
    sitesList: S.sitesList,
  });
}

function settingsStateSnapshot() {
  return {
    adblock: S.adblock,
    quic: S.quic,
    lan: S.lan,
    killSwitch: S.killSwitch,
    resumeOnBoot: S.resumeOnBoot,
    networkAutoProtect: S.networkAutoProtect,
    warpyAuto: S.warpyAuto,
    mtu: S.mtu,
    lang: S.lang,
    updateChannel: S.updateChannel,
    appsMode: S.appsMode,
    appsList: [...S.appsList],
    sitesMode: S.sitesMode,
    sitesList: [...S.sitesList],
  };
}

function settingsInputsSnapshot() {
  const cleanApps = $('s-apps-list').value.split(',').map(value => value.trim()).filter(Boolean);
  const cleanSites = $('s-sites-list').value.split(',')
    .map(value => value.trim().toLowerCase().replace(/^(https?:\/\/)?(www\.)?/, '').split('/')[0].split(':')[0])
    .filter(Boolean);
  return JSON.stringify({
    quic: $('s-quic').checked,
    lan: $('s-lan').checked,
    killSwitch: $('s-kill-switch').checked,
    resumeOnBoot: $('s-resume-on-boot').checked,
    mtu: normalizeMtu($('s-mtu').value),
    lang: normalizeChoice($('s-lang').value, ['ru', 'en'], systemLanguage()),
    appsMode: $('s-apps-mode').value,
    appsList: cleanApps,
    sitesMode: $('s-sites-mode').value,
    sitesList: cleanSites,
  });
}

function normalizeChoice(value, allowed, fallback) {
  return allowed.includes(value) ? value : fallback;
}

function normalizeMtu(value) {
  const mtu = Number.parseInt(value, 10);
  if (!Number.isFinite(mtu) || mtu === 0) return 0;
  return Math.min(1500, Math.max(576, mtu));
}

function stringList(value) {
  return Array.isArray(value) ? value.map(item => String(item).trim()).filter(Boolean) : [];
}

let settingsOpenSnapshot = null;

function showSettingsPage(page) {
  const tunneling = page === 'tunneling';
  $('settings-main-page').classList.toggle('hidden', tunneling);
  $('settings-main-header').classList.toggle('hidden', tunneling);
  $('settings-tunneling-page').classList.toggle('hidden', !tunneling);
  $('settings-tunneling-header').classList.toggle('hidden', !tunneling);
  const body = tunneling ? $('settings-tunneling-page') : $('settings-main-page');
  body.scrollTop = 0;
}

function updateSettingsSaveState() {
  const button = $('btn-save-settings');
  if (!button) return;
  const changed = settingsOpenSnapshot !== null && settingsOpenSnapshot !== settingsInputsSnapshot();
  button.disabled = !changed;
  button.setAttribute('aria-disabled', String(!changed));
}

function show(id) {
  $(id).classList.remove('hidden');
  if (id === 'overlay-settings') {
    loadSettingsInputs();
    showSettingsPage('main');
    settingsOpenSnapshot = settingsInputsSnapshot();
    updateSettingsSaveState();
    void refreshKillSwitchStatus();
    void refreshVpnHealth();
    void refreshNetworkContextDisplay();
    $('app').classList.add('settings-active');
  }
}
function hide(id) {
  $(id).classList.add('hidden');
  if (id === 'overlay-profiles') profilesViewGroup = null;
  if (id === 'overlay-settings') {
    settingsOpenSnapshot = null;
    $('app').classList.remove('settings-active');
  }
}

function openAddProfile() {
  hide('overlay-profiles');
  show('overlay-add');
}

async function requestSettingsClose() {
  if (!$('settings-tunneling-page').classList.contains('hidden')) {
    showSettingsPage('main');
    return;
  }
  if (
    settingsOpenSnapshot !== null &&
    settingsOpenSnapshot !== settingsInputsSnapshot()
  ) {
    show('overlay-settings-unsaved');
    return;
  }
  hide('overlay-settings');
}

function discardSettingsChanges() {
  hide('overlay-settings-unsaved');
  loadSettingsInputs();
  hide('overlay-settings');
}

function setConnectionCheckState(state, text) {
  const status = $('connection-check-status');
  if (!status) return;
  status.dataset.state = state;
  status.textContent = text;
}

function resetConnectionCheck() {
  connectionCheckRunning = false;
  pendingConnectionRecommendations = [];
  const button = $('btn-connection-check');
  if (button) button.disabled = false;
  setConnectionCheckState('idle', t('connectionCheckIdle'));
}

const CONNECTION_DIAGNOSTIC_ATTEMPTS = 10;

async function measureDiagnosticLatency(signal) {
  const samples = await measureLatencySamples({
    attempts: CONNECTION_DIAGNOSTIC_ATTEMPTS,
    minSuccessful: 3,
    pauseMs: 220,
    signal,
    timeoutMs: 3500,
    urlFactory: () => cloudflareDownloadUrl(1024, 'diagnostic'),
    onAttempt: (current, total) => {
      setConnectionCheckState(
        'checking',
        t('connectionCheckLatency')
          .replace('{current}', String(current))
          .replace('{total}', String(total)),
      );
    },
  });
  return summarizeLatencySamples(samples, CONNECTION_DIAGNOSTIC_ATTEMPTS);
}

async function measureDiagnosticDownload(signal) {
  const samples = [];
  for (let index = 0; index < 2; index++) {
    const startedAt = performance.now();
    const response = await fetchWithTimeout(
      cloudflareDownloadUrl(2_000_000, 'diagnostic-download'),
      signal,
      8000,
    );
    const body = await response.arrayBuffer();
    const durationMs = performance.now() - startedAt;
    if (body.byteLength > 0 && durationMs > 0) {
      samples.push(toMbps(body.byteLength, durationMs));
    }
  }
  return samples.length ? median(samples) : null;
}

function connectionFindingKey(metrics) {
  if (metrics.lossPercent >= 3) return 'connectionReportLossDetail';
  if (metrics.jitterMs >= 50) return 'connectionReportJitterDetail';
  if (metrics.latencyMs >= 180) return 'connectionReportLatencyDetail';
  return 'connectionReportStableDetail';
}

function connectionQualityTitleKey(quality) {
  if (quality === 'poor') return 'connectionReportPoor';
  if (quality === 'impaired') return 'connectionReportImpaired';
  return 'connectionReportStable';
}

function createConnectionMetric(labelKey, value) {
  const row = document.createElement('div');
  row.className = 'connection-report-metric';
  row.append(
    createTextElement('span', 'connection-report-metric-label', t(labelKey)),
    createTextElement('strong', 'connection-report-metric-value', value),
  );
  return row;
}

function renderConnectionDiagnosticReport(report) {
  const quality = classifyConnection(report.metrics);
  const title = $('connection-report-summary');
  title.dataset.state = quality;
  title.textContent = t(connectionQualityTitleKey(quality));
  $('connection-report-detail').textContent = t(connectionFindingKey(report.metrics));

  const metrics = $('connection-report-metrics');
  metrics.replaceChildren(
    createConnectionMetric('connectionReportLatency', `${report.metrics.latencyMs} ${t('ms')}`),
    createConnectionMetric('connectionReportJitter', `${report.metrics.jitterMs} ${t('ms')}`),
    createConnectionMetric('connectionReportLoss', `${report.metrics.lossPercent}%`),
    createConnectionMetric(
      'connectionReportDownload',
      report.downloadMbps === null ? '—' : `${Math.round(report.downloadMbps)} ${t('mbps')}`,
    ),
  );

  const recommendations = $('connection-report-recommendations');
  recommendations.replaceChildren();
  if (!report.recommendations.length) {
    recommendations.append(
      createTextElement('div', 'connection-report-empty', t('connectionReportNoChanges')),
    );
  } else {
    const recommendationCopy = {
      warpyAuto: ['connectionReportAutoTitle', 'connectionReportAutoDescription'],
      automaticMtu: ['connectionReportMtuTitle', 'connectionReportMtuDescription'],
    };
    report.recommendations.forEach(recommendation => {
      const copy = recommendationCopy[recommendation];
      const item = document.createElement('div');
      item.className = 'connection-report-recommendation';
      item.append(
        createTextElement('span', 'connection-report-recommendation-mark', '✓'),
        createTextElement('strong', 'connection-report-recommendation-title', t(copy[0])),
        createTextElement('span', 'connection-report-recommendation-copy', t(copy[1])),
      );
      recommendations.append(item);
    });
  }

  pendingConnectionRecommendations = [...report.recommendations];
  $('connection-report-apply').classList.toggle('hidden', !report.recommendations.length);
  $('connection-report-close').textContent = t(
    report.recommendations.length ? 'cancel' : 'connectionReportDone',
  );
  show('overlay-connection-report');
}

async function applyConnectionRecommendations() {
  if (!pendingConnectionRecommendations.length) {
    hide('overlay-connection-report');
    return;
  }
  if (pendingConnectionRecommendations.includes('warpyAuto')) {
    $('s-warpy-auto').checked = true;
    renderWarpyAutoStatus();
  }
  if (pendingConnectionRecommendations.includes('automaticMtu')) {
    $('s-mtu').value = '0';
  }
  pendingConnectionRecommendations = [];
  hide('overlay-connection-report');
  const saved = await saveSettingsFromInputs();
  if (saved) setConnectionCheckState('success', t('connectionCheckApplied'));
}

async function checkAndTuneConnection() {
  if (connectionCheckRunning) return;
  if (!S.profiles.length) {
    setConnectionCheckState('error', t('connectionCheckNoProfiles'));
    return;
  }

  connectionCheckRunning = true;
  const button = $('btn-connection-check');
  button.disabled = true;
  setConnectionCheckState('checking', t('connectionCheckRunning'));

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 35_000);
  try {
    const snapshot = await getVpnRuntimeSnapshot();
    if (snapshot.competingVpn) throw new Error(t('otherVpnActive'));
    if (S.status !== 'connected') await startVpn();
    if (S.status !== 'connected') throw new Error(t('failedToConnect'));

    const metrics = await measureDiagnosticLatency(controller.signal);
    setConnectionCheckState('checking', t('connectionCheckDownload'));
    let downloadMbps = null;
    try {
      downloadMbps = await measureDiagnosticDownload(controller.signal);
    } catch (error) {
      if (controller.signal.aborted) throw error;
      await logMsg(`Connection diagnostic download sample failed: ${error}`);
    }

    const quality = classifyConnection(metrics);
    const recommendations = buildConnectionRecommendations({
      metrics,
      mtu: $('s-mtu').value,
      warpyAuto: $('s-warpy-auto').checked,
      profileCount: S.profiles.length,
    });
    renderConnectionDiagnosticReport({ metrics, downloadMbps, recommendations });
    setConnectionCheckState(
      quality === 'stable' ? 'success' : 'warning',
      t('connectionCheckComplete'),
    );
  } catch (error) {
    setConnectionCheckState('error', t('connectionCheckFailed'));
    await logMsg(`Connection check failed: ${error}`);
  } finally {
    clearTimeout(timeout);
    connectionCheckRunning = false;
    button.disabled = false;
  }
}

let confirmResolver = null;
function showConfirm(messageKeyOrText, isKey = true) {
  return new Promise((resolve) => {
    $('confirm-msg').textContent = isKey ? t(messageKeyOrText) : messageKeyOrText;
    show('overlay-confirm');
    confirmResolver = resolve;
  });
}

function showMessage(messageKeyOrText, isKey = true) {
  $('message-msg').textContent = isKey ? t(messageKeyOrText) : messageKeyOrText;
  show('overlay-message');
}

/* ── Settings persistence ──────────────────────── */
async function loadSettings() {
  try {
    const raw = await invoke('load_settings');
    const d = JSON.parse(raw);
    let migrated = d.schemaVersion !== 7;
    S.profiles = Array.isArray(d.profiles)
      ? d.profiles.filter(profile => profile && typeof profile === 'object').map(profile => {
        const clean = { ...profile };
        if ('_index' in clean) {
          delete clean._index;
          migrated = true;
        }
        return clean;
      })
      : [];
    S.subscriptions = sanitizeSubscriptions(d.subscriptions, () => { migrated = true; });

    // Repair names damaged by older URL parsing without changing user-created groups.
    S.profiles.forEach(p => {
      if (p.raw && (!p.name || p.name.includes('?'))) {
        const parsed = parseLink(p.raw);
        if (parsed?.name && !parsed.name.includes('?')) {
          p.name = parsed.name;
          migrated = true;
        }
      }
    });

    const requestedActive = Number.isInteger(d.active) ? d.active : Number.parseInt(d.active, 10) || 0;
    S.active = Math.min(Math.max(requestedActive, 0), Math.max(S.profiles.length - 1, 0));
    const storedPreferred = typeof d.preferredProfileKey === 'string' ? d.preferredProfileKey : '';
    const preferredIndex = S.profiles.findIndex(profile => profileRuntimeKey(profile) === storedPreferred);
    if (preferredIndex >= 0) {
      S.preferredProfileKey = storedPreferred;
      S.active = preferredIndex;
    } else {
      S.preferredProfileKey = profileRuntimeKey(S.profiles[S.active]);
      if (storedPreferred) migrated = true;
    }
    S.adblock = d.adblock === true;
    S.quic = d.quic === true;
    S.lan = d.lan === true;
    S.killSwitch = d.killSwitch === true;
    S.resumeOnBoot = d.resumeOnBoot === true;
    S.networkAutoProtect = false;
    if (d.networkAutoProtect === true) migrated = true;
    S.warpyAuto = false;
    if (d.warpyAuto === true) migrated = true;
    S.mtu = normalizeMtu(d.mtu);
    S.appsMode = normalizeChoice(d.appsMode, ['off', 'only', 'bypass'], 'off');
    S.appsList = stringList(d.appsList);
    S.sitesMode = normalizeChoice(d.sitesMode, ['off', 'only', 'bypass'], 'off');
    S.sitesList = stringList(d.sitesList).map(s => {
      let clean = s.toLowerCase();
      clean = clean.replace(/^(https?:\/\/)?(www\.)?/, '');
      clean = clean.split('/')[0].split(':')[0];
      return clean;
    }).filter(s => s.length > 0);

    S.lang = normalizeChoice(d.lang, ['ru', 'en'], systemLanguage());
    if (d.lang !== S.lang) migrated = true;
    S.updateChannel = 'stable';
    if (d.updateChannel && d.updateChannel !== 'stable') migrated = true;
    S.settingsLoaded = true;

    loadSettingsInputs();
    localizeUI();
    if (migrated) await save();
    return true;
  } catch(e) {
    S.settingsLoaded = false;
    console.error('Settings load failed:', e);
    await logMsg(`Settings load failed: ${e}`);
    return false;
  }
}

function loadSettingsInputs() {
  $('s-quic').checked = !!S.quic;
  $('s-lan').checked = !!S.lan;
  $('s-kill-switch').checked = !!S.killSwitch;
  $('s-resume-on-boot').checked = !!S.resumeOnBoot;
  $('s-mtu').value = S.mtu || 0;
  $('s-apps-mode').value = S.appsMode || 'off';
  $('s-apps-list').value = (S.appsList || []).join(', ');
  $('s-sites-mode').value = S.sitesMode || 'off';
  $('s-sites-list').value = (S.sitesList || []).join(', ');
  $('s-lang').value = S.lang || systemLanguage();
  renderUpdateControl();
  updateSettingsSaveState();
}

function renderKillSwitchStatus() {
  const element = $('kill-switch-status');
  if (!element) return;

  const status = S.killSwitchServiceStatus || 'Off';
  let key = S.killSwitch ? 'killSwitchReady' : 'killSwitchOff';
  let state = 'off';
  if (status === 'Armed') {
    key = 'killSwitchArmed';
    state = 'armed';
  } else if (status === 'Suppressed:split-tunneling') {
    key = 'killSwitchSplitSuppressed';
  } else if (status.startsWith('Suppressed:')) {
    key = 'killSwitchSuppressed';
  } else if (status.startsWith('Error:')) {
    key = 'killSwitchError';
    state = 'error';
  }
  element.textContent = t(key);
  element.dataset.state = state;
}

async function refreshKillSwitchStatus() {
  try {
    S.killSwitchServiceStatus = String(await invoke('get_kill_switch_status') || 'Off');
  } catch (error) {
    console.error('Kill switch status check failed:', error);
    S.killSwitchServiceStatus = `Error:${error}`;
  }
  renderKillSwitchStatus();
}

function profileIndexForOutbound(outbound) {
  const match = /^profile-(\d+)$/.exec(String(outbound || ''));
  if (!match) return -1;
  const runtimeIndex = Number.parseInt(match[1], 10) - 1;
  const profileKey = S.runtimeProfileKeys[runtimeIndex];
  if (!profileKey) return -1;
  return S.profiles.findIndex(profile => profileRuntimeKey(profile) === profileKey);
}

function renderWarpyAutoStatus() {
  const element = $('warpy-auto-status');
  if (!element) return;
  const settingsOpen = !$('overlay-settings').classList.contains('hidden');
  const enabled = settingsOpen ? $('s-warpy-auto').checked : S.warpyAuto;
  const pendingChange = settingsOpen && enabled !== S.warpyAuto;
  if (!enabled) {
    element.textContent = t('warpyAutoOff');
    element.dataset.state = 'off';
    return;
  }

  const event = pendingChange ? null : S.vpnHealth?.lastAutoSwitch;
  if (!event) {
    const runtimePreferred = profileIndexForOutbound(S.vpnHealth?.preferredOutbound);
    const preferredIndex = runtimePreferred >= 0 ? runtimePreferred : preferredProfileIndex();
    const profile = preferredIndex >= 0 ? S.profiles[preferredIndex] : null;
    const name = profile ? getProfileDisplay(profile).name : '';
    element.textContent = name
      ? `${t('warpyAutoPreferred')} · ${name}`
      : t('warpyAutoWatching');
    element.dataset.state = 'armed';
    return;
  }
  if (event.outcome !== 'switched') {
    element.textContent = t('warpyAutoFailed');
    element.dataset.state = 'error';
    return;
  }

  const index = profileIndexForOutbound(event.toOutbound);
  const profile = index >= 0 ? S.profiles[index] : null;
  const profileName = profile ? getProfileDisplay(profile).name : '';
  const key = warpyAutoEventTranslationKey(event.reason);
  element.textContent = profileName ? `${t(key)} · ${profileName}` : t(key);
  element.dataset.state = 'armed';
}

function updateNetworkContext(value) {
  S.networkContext = normalizeNetworkContext(value);
  if (!S.networkContext.internet || S.networkContext.trust === 'trusted') {
    S.networkAutoBlocked = false;
    S.networkAutoBlockedReason = '';
  }
}

async function getVpnRuntimeSnapshot() {
  const snapshot = await invoke('get_vpn_runtime_snapshot');
  if (!snapshot || typeof snapshot !== 'object') {
    throw new Error('Invalid VPN runtime snapshot');
  }
  return {
    status: String(snapshot.status || 'Error'),
    desiredRunning: snapshot.desiredRunning === true,
    network: normalizeNetworkContext(snapshot.network),
    competingVpn: snapshot.competingVpn === true,
  };
}

async function applyServiceConnectionSnapshot(
  snapshot,
  { showConnectedAlert, errorText = '' } = {},
) {
  const previous = { status: S.status, connectedAt: S.connectedAt };
  const connected = String(snapshot.status).startsWith('Connected');
  const startedAt = connected ? Number(await invoke('get_vpn_started_at')) || 0 : 0;
  const next = reconcileServiceConnection(previous, snapshot, startedAt);

  S.status = next.status;
  S.connectedAt = next.connectedAt;
  if (next.status !== 'connected') {
    S.showConnectedAlert = false;
  } else if (typeof showConnectedAlert === 'boolean') {
    S.showConnectedAlert = showConnectedAlert;
  }

  if (next.status === 'connected') {
    if (next.changed || !S.uptimeTimer) startTimers();
  } else {
    stopTimers();
  }

  if (next.status === 'error') {
    errorMsg.textContent = errorText || `${t('failedToConnect')} (${next.serviceStatus})`;
  }
  syncUI();
  return next;
}

async function refreshNetworkContextDisplay() {
  try {
    const snapshot = await getVpnRuntimeSnapshot();
    updateNetworkContext(snapshot.network);
  } catch (error) {
    console.error('Network context refresh failed:', error);
  }
}

async function applyNetworkAutoProtection(snapshot) {
  updateNetworkContext(snapshot.network);
  const decision = networkProtectionDecision({
    enabled: S.networkAutoProtect,
    network: S.networkContext,
    backendStatus: snapshot.status,
    hasProfiles: S.profiles.length > 0,
    busy: S.networkAutoConnectRunning
      || S.startCommandAttempt !== 0
      || S.status === 'connected'
      || S.status === 'connecting',
    blocked: S.networkAutoBlocked,
    handledGeneration: S.networkAutoHandledGeneration,
  });
  if (decision.action !== 'connect') return false;

  S.networkAutoHandledGeneration = decision.context.generation;
  S.networkAutoConnectRunning = true;
  try {
    await logMsg('Public network auto-protection started');
    await startVpn();
    if (S.status !== 'connected') {
      S.networkAutoBlocked = true;
      S.networkAutoBlockedReason = 'failed';
    }
  } finally {
    S.networkAutoConnectRunning = false;
  }
  return true;
}

function warpyAutoEventTranslationKey(reason) {
  return reason === 'unavailable'
    ? 'warpyAutoUnavailable'
    : reason === 'preferred'
      ? 'warpyAutoReturned'
      : 'warpyAutoFaster';
}

async function initializeSystemNotifications(allowPrompt) {
  const notification = window.__TAURI__?.notification;
  if (typeof notification?.isPermissionGranted !== 'function') return;
  try {
    let granted = await notification.isPermissionGranted();
    if (!granted && allowPrompt && typeof notification.requestPermission === 'function') {
      granted = await notification.requestPermission() === 'granted';
    }
    S.systemNotificationsReady = granted;
  } catch (error) {
    await logMsg(`System notification initialization failed: ${error}`);
  }
}

function sendSignificantNotification(key, title, body, eventAt = Date.now()) {
  if (isWindowVisible || !S.systemNotificationsReady) return;
  if (!shouldDeliverSystemNotification(key, eventAt)) return;
  const notification = window.__TAURI__?.notification;
  if (typeof notification?.sendNotification !== 'function') return;
  try {
    notification.sendNotification({ title, body });
  } catch (error) {
    S.systemNotificationsReady = false;
    void logMsg(`System notification delivery failed: ${error}`);
  }
}

async function refreshVpnHealth() {
  if (S.healthCheckRunning) return;
  S.healthCheckRunning = true;
  try {
    const snapshot = await invoke('get_vpn_health');
    S.vpnHealth = snapshot && typeof snapshot === 'object' ? snapshot : null;
    const event = S.vpnHealth?.lastAutoSwitch;
    const notificationEvent = autoSwitchNotificationEvent(event, S.lastHandledAutoSwitchAt);
    if (notificationEvent) {
      if (
        notificationEvent.kind === 'auto-switched'
        && event.toOutbound === S.vpnHealth?.activeOutbound
      ) {
        const index = profileIndexForOutbound(event.toOutbound);
        if (index >= 0 && index !== S.active) {
          S.active = index;
          syncUI();
          if (!$('overlay-profiles').classList.contains('hidden')) renderProfiles();
        }
        const messageKey = warpyAutoEventTranslationKey(event.reason);
        sendSignificantNotification('auto-switched', t('warpyAuto'), t(messageKey), notificationEvent.observedAt);
      } else if (notificationEvent.kind === 'auto-failed') {
        sendSignificantNotification('auto-failed', t('warpyAuto'), t('warpyAutoFailed'), notificationEvent.observedAt);
      }
      S.lastHandledAutoSwitchAt = notificationEvent.observedAt;
    }
    renderWarpyAutoStatus();
  } catch (error) {
    console.error('VPN health check failed:', error);
  } finally {
    S.healthCheckRunning = false;
  }
}

async function updateResumeOnBootPolicy() {
  await invoke('set_resume_on_boot', {
    enabled: S.resumeOnBoot,
  });
}

async function updateWarpyAutoPolicy() {
  await invoke('set_warpy_auto', { enabled: S.warpyAuto });
  renderWarpyAutoStatus();
}

async function save() {
  if (!S.settingsLoaded) throw new Error(t('settingsUnavailable'));
  const profiles = S.profiles.map(profile => {
    const clean = { ...profile };
    delete clean._index;
    return clean;
  });
  await invoke('save_settings', { settings: JSON.stringify({
    schemaVersion: 7,
    profiles,
    subscriptions: S.subscriptions.map(subscription => ({
      id: subscription.id,
      url: subscription.url,
      name: subscription.name,
      updatedAt: subscription.updatedAt,
      lastCheckedAt: subscription.lastCheckedAt,
      lastStatus: subscription.lastStatus,
    })),
    active: S.active,
    preferredProfileKey: S.preferredProfileKey,
    adblock: S.adblock,
    quic: S.quic,
    lan: S.lan,
    killSwitch: S.killSwitch,
    resumeOnBoot: S.resumeOnBoot,
    networkAutoProtect: S.networkAutoProtect,
    warpyAuto: S.warpyAuto,
    mtu: S.mtu,
    appsMode: S.appsMode,
    appsList: S.appsList,
    sitesMode: S.sitesMode,
    sitesList: S.sitesList,
    lang: S.lang,
    updateChannel: S.updateChannel,
  }) });
}

/* ── Profile management ────────────────────────── */
function parseLink(rawLink) {
  return parseProfileLink(rawLink);
}

async function addFromClipboard() {
  const button = $('btn-clipboard-import');
  if (button.disabled) return;

  button.disabled = true;
  button.querySelector('span').textContent = t('importLoading');
  try {
    const text = await navigator.clipboard.readText();
    await importClipboardText(text);
  } catch (error) {
    showMessage(t('clipboardError') + error, false);
  } finally {
    button.disabled = false;
    button.querySelector('span').textContent = t('addClipboardBtn');
  }
}

function normalizeSubscriptionUrl(value) {
  const url = new URL(String(value || '').trim());
  if (url.protocol !== 'https:' || url.username || url.password) {
    throw new Error(t('subscriptionInvalidUrl'));
  }
  url.hash = '';
  return url.href;
}

function newSubscriptionId() {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID();
  return `subscription-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function sanitizeSubscriptions(value, markMigrated = () => {}) {
  if (!Array.isArray(value)) {
    if (value !== undefined) markMigrated();
    return [];
  }

  const subscriptions = [];
  const ids = new Set();
  const urls = new Set();
  for (const item of value) {
    try {
      const id = typeof item?.id === 'string' ? item.id.trim() : '';
      const url = normalizeSubscriptionUrl(item?.url);
      const name = typeof item?.name === 'string' && item.name.trim()
        ? item.name.trim()
        : subscriptionDisplayName(url);
      if (!id || ids.has(id) || urls.has(url)) throw new Error('duplicate');
      const updatedAt = Number.isFinite(Number(item.updatedAt))
        ? Math.max(0, Number(item.updatedAt))
        : 0;
      const storedCheckedAt = Number(item.lastCheckedAt);
      const lastCheckedAt = Number.isFinite(storedCheckedAt)
        ? Math.max(0, storedCheckedAt)
        : updatedAt;
      const allowedStatuses = ['', 'updated', 'unchanged', 'error'];
      const storedStatus = typeof item.lastStatus === 'string' ? item.lastStatus : '';
      const lastStatus = allowedStatuses.includes(storedStatus)
        ? (storedStatus || (updatedAt > 0 ? 'updated' : ''))
        : (updatedAt > 0 ? 'updated' : '');
      if (
        item.lastCheckedAt === undefined ||
        item.lastStatus === undefined ||
        storedCheckedAt !== lastCheckedAt ||
        storedStatus !== lastStatus
      ) markMigrated();
      ids.add(id);
      urls.add(url);
      subscriptions.push({
        id,
        url,
        name,
        updatedAt,
        lastCheckedAt,
        lastStatus,
      });
    } catch {
      markMigrated();
    }
  }
  return subscriptions;
}

function subscriptionStatusText(subscription, isRefreshing) {
  if (isRefreshing) return { text: t('subscriptionUpdating'), state: 'updating' };
  const key = subscription.lastStatus === 'error'
    ? 'subscriptionUpdateError'
    : subscription.lastStatus === 'unchanged'
      ? 'subscriptionUnchanged'
      : 'subscriptionUpdated';
  const timestamp = Number(subscription.lastCheckedAt || subscription.updatedAt) || 0;
  if (!timestamp) return { text: t(key), state: subscription.lastStatus || 'updated' };
  const locale = S.lang;
  const time = new Intl.DateTimeFormat(locale, {
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(timestamp));
  return { text: `${t(key)} · ${time}`, state: subscription.lastStatus || 'updated' };
}

function renderSubscriptions() {
  const list = $('subscriptions-list');
  if (!list) return;
  list.replaceChildren();
  if (!S.subscriptions.length) {
    const empty = document.createElement('div');
    empty.className = 'subscription-empty';
    empty.textContent = t('subscriptionsEmpty');
    list.appendChild(empty);
    return;
  }

  for (const subscription of S.subscriptions) {
    const row = document.createElement('div');
    row.className = 'subscription-row';
    const main = document.createElement('div');
    main.className = 'subscription-row-main';
    const name = document.createElement('div');
    name.className = 'subscription-row-name';
    name.textContent = subscription.name;
    const meta = document.createElement('div');
    meta.className = 'subscription-row-meta';
    const count = document.createElement('span');
    count.textContent = serverCountLabel(
      S.profiles.filter(profile => profile.subscriptionId === subscription.id).length,
    );
    const separator = document.createElement('span');
    separator.textContent = '·';
    const status = document.createElement('span');
    status.className = 'subscription-row-status';
    const isRefreshing = refreshingSubscriptions.has(subscription.id);
    const statusValue = subscriptionStatusText(subscription, isRefreshing);
    status.textContent = statusValue.text;
    status.dataset.state = statusValue.state;
    meta.append(count, separator, status);
    main.append(name, meta);

    const refresh = document.createElement('button');
    refresh.type = 'button';
    refresh.className = `subscription-refresh${isRefreshing ? ' refreshing' : ''}`;
    refresh.disabled = isRefreshing;
    refresh.title = t('refreshSubscription');
    refresh.setAttribute('aria-label', t('refreshSubscription'));
    refresh.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6v5h-5"/><path d="M4 18v-5h5"/><path d="M6.1 9a7 7 0 0 1 11.6-2.6L20 9M4 15l2.3 2.6A7 7 0 0 0 17.9 15"/></svg>';
    refresh.onclick = () => { void refreshSubscription(subscription.id); };
    row.append(main, refresh);
    list.appendChild(row);
  }
}

async function saveSubscriptionRefreshError(subscriptionId) {
  const subscription = S.subscriptions.find(item => item.id === subscriptionId);
  if (!subscription) return;
  const previousCheckedAt = subscription.lastCheckedAt;
  const previousStatus = subscription.lastStatus;
  subscription.lastCheckedAt = Date.now();
  subscription.lastStatus = 'error';
  try {
    await save();
  } catch {
    subscription.lastCheckedAt = previousCheckedAt;
    subscription.lastStatus = previousStatus;
  }
}

async function refreshSubscription(subscriptionId, { automatic = false } = {}) {
  if (refreshingSubscriptions.has(subscriptionId)) return;
  const subscription = S.subscriptions.find(item => item.id === subscriptionId);
  if (!subscription) return;
  if (automatic && (S.status !== 'stopped' || !subscriptionRefreshDue(subscription))) return;
  refreshingSubscriptions.add(subscriptionId);
  renderSubscriptions();
  try {
    const response = await invoke('fetch_subscription', { url: subscription.url });
    const parsed = parseSubscriptionPayload(response?.body);
    const currentProfiles = S.profiles.filter(profile => profile.subscriptionId === subscriptionId);
    const checkedAt = Date.now();
    if (subscriptionProfilesEqual(currentProfiles, parsed.profiles)) {
      subscription.lastCheckedAt = checkedAt;
      subscription.lastStatus = 'unchanged';
      await save();
    } else {
      if (automatic && S.status !== 'stopped') return;
      await applySubscriptionUpdate({
        ...subscription,
        updatedAt: checkedAt,
        lastCheckedAt: checkedAt,
        lastStatus: 'updated',
      }, parsed.profiles);
    }
  } catch {
    await saveSubscriptionRefreshError(subscriptionId);
    if (!automatic) console.error('Subscription refresh failed');
  } finally {
    refreshingSubscriptions.delete(subscriptionId);
    renderSubscriptions();
  }
}

function scheduleAutomaticSubscriptionRefresh(delayMs = 0) {
  if (subscriptionAutoRefreshTimeout !== null) return;
  subscriptionAutoRefreshTimeout = setTimeout(() => {
    subscriptionAutoRefreshTimeout = null;
    void runAutomaticSubscriptionRefresh();
  }, delayMs);
}

function startSubscriptionAutoRefreshScheduler() {
  if (subscriptionAutoRefreshTimer !== null) return;
  scheduleAutomaticSubscriptionRefresh(30_000);
  subscriptionAutoRefreshTimer = setInterval(
    () => scheduleAutomaticSubscriptionRefresh(),
    SUBSCRIPTION_AUTO_POLL_INTERVAL_MS,
  );
}

async function runAutomaticSubscriptionRefresh() {
  if (
    subscriptionAutoRefreshRunning ||
    S.status !== 'stopped' ||
    S.startCommandAttempt !== 0 ||
    !S.subscriptions.length
  ) return;

  subscriptionAutoRefreshRunning = true;
  try {
    const dueIds = S.subscriptions
      .filter(subscription => subscriptionRefreshDue(subscription))
      .map(subscription => subscription.id);
    for (const subscriptionId of dueIds) {
      if (S.status !== 'stopped' || S.startCommandAttempt !== 0) break;
      await refreshSubscription(subscriptionId, { automatic: true });
    }
  } finally {
    subscriptionAutoRefreshRunning = false;
  }
}

function captureProfileState() {
  return {
    profiles: S.profiles,
    subscriptions: S.subscriptions,
    active: S.active,
    preferredProfileKey: S.preferredProfileKey,
    status: S.status,
  };
}

async function commitProfileState(snapshot, nextState, operationName, { restartRuntime = true } = {}) {
  const wasRunning = snapshot.status === 'connected' || snapshot.status === 'connecting';
  const shouldRestartRuntime = wasRunning && restartRuntime;
  let originalError = null;
  try {
    if (shouldRestartRuntime) await stopVpn();
    S.profiles = nextState.profiles;
    S.subscriptions = nextState.subscriptions;
    S.active = nextState.active;
    S.preferredProfileKey = nextState.preferredProfileKey;
    await save();
    syncUI();
    renderProfiles();
    renderSubscriptions();
    if (shouldRestartRuntime && S.profiles.length) {
      await startVpn();
      if (S.status !== 'connected') {
        throw new Error(errorMsg.textContent || t('failedToConnect'));
      }
    }
    return;
  } catch (error) {
    originalError = error;
  }

  if (shouldRestartRuntime && S.status !== 'stopped') await stopVpn();
  S.profiles = snapshot.profiles;
  S.subscriptions = snapshot.subscriptions;
  S.active = snapshot.active;
  S.preferredProfileKey = snapshot.preferredProfileKey;
  let rollbackError = null;
  try {
    await save();
  } catch (error) {
    rollbackError = error;
  }
  syncUI();
  renderProfiles();
  renderSubscriptions();
  if (shouldRestartRuntime && snapshot.profiles.length) {
    try {
      await startVpn();
      if (S.status !== 'connected') throw new Error(errorMsg.textContent || t('failedToConnect'));
    } catch (error) {
      rollbackError ||= error;
    }
  }
  if (rollbackError) {
    await logMsg(`${operationName} rollback failed: ${rollbackError}`);
    throw new Error(`${originalError}; ${t('settingsSaveError')}${rollbackError}`);
  }
  throw originalError;
}

async function applySubscriptionUpdate(subscription, importedProfiles, legacyGroupName = '') {
  const snapshot = captureProfileState();
  const previousActiveProfile = snapshot.profiles[snapshot.active];
  const previousPreferredProfile = snapshot.profiles.find(profile =>
    profileRuntimeKey(profile) === snapshot.preferredProfileKey
  );
  const nextProfiles = replaceSubscriptionProfiles(
    snapshot.profiles,
    subscription.id,
    importedProfiles,
    subscription.name,
    legacyGroupName,
  );
  const existingSubscriptionIndex = snapshot.subscriptions.findIndex(item => item.id === subscription.id);
  const nextSubscriptions = [...snapshot.subscriptions];
  if (existingSubscriptionIndex >= 0) nextSubscriptions[existingSubscriptionIndex] = subscription;
  else nextSubscriptions.push(subscription);

  let nextPreferred = findProfileIndexAfterSubscriptionUpdate(
    nextProfiles,
    previousPreferredProfile,
    subscription.id,
  );
  let nextActive = findProfileIndexAfterSubscriptionUpdate(
    nextProfiles,
    previousActiveProfile,
    subscription.id,
  );
  const firstImported = nextProfiles.findIndex(profile => profile.subscriptionId === subscription.id);
  if (nextPreferred < 0) nextPreferred = firstImported >= 0 ? firstImported : 0;
  if (nextActive < 0) nextActive = nextPreferred;

  const wasRunning = snapshot.status === 'connected' || snapshot.status === 'connecting';
  await commitProfileState(snapshot, {
    profiles: nextProfiles,
    subscriptions: nextSubscriptions,
    active: wasRunning ? nextPreferred : nextActive,
    preferredProfileKey: profileRuntimeKey(nextProfiles[nextPreferred]),
  }, 'Subscription update');
}

async function importSubscriptionUrl(text) {
  let url;
  try {
    url = normalizeSubscriptionUrl(text);
  } catch {
    showMessage('subscriptionInvalidUrl');
    return;
  }

  try {
    const response = await invoke('fetch_subscription', { url });
    const parsed = parseSubscriptionPayload(response?.body);
    const existing = S.subscriptions.find(item => item.url === url);
    const suggestedName = subscriptionDisplayName(text);
    const legacyGroupName = existing ? '' : Object.keys(partitionProfiles().groups).find(groupName =>
      groupName.localeCompare(suggestedName, undefined, { sensitivity: 'base' }) === 0
    ) || '';
    const updatedAt = Date.now();
    const subscription = {
      id: existing?.id || newSubscriptionId(),
      url,
      name: existing?.name || legacyGroupName || suggestedName,
      updatedAt,
      lastCheckedAt: updatedAt,
      lastStatus: 'updated',
    };
    await applySubscriptionUpdate(subscription, parsed.profiles, legacyGroupName);
    if (parsed.skipped > 0) {
      await logMsg(`Subscription imported; unsupported profiles skipped: ${parsed.skipped}`);
    }
    hide('overlay-add');
  } catch (error) {
    showMessage(t('subscriptionError') + String(error).replace(/^Error:\s*/, ''), false);
  }
}

async function importClipboardText(text) {
  const clipboard = classifyClipboardImport(text);
  if (clipboard.type === 'empty') {
    showMessage('clipboardEmpty');
    return;
  }
  if (clipboard.type === 'profile') {
    await importProfileText(clipboard.value);
    return;
  }
  if (clipboard.type === 'subscription') {
    await importSubscriptionUrl(clipboard.value);
    return;
  }
  showMessage('invalidFormat');
}

async function importProfileText(text) {
  const p = parseLink(text.trim());
  if (!p) {
    showMessage('invalidFormat');
    return;
  }
  const duplicateIndex = S.profiles.findIndex(profile => profile.raw === p.raw);
  if (duplicateIndex >= 0) {
    if (!await selectProfile(duplicateIndex, { closeProfiles: false })) return;
    hide('overlay-add');
    showMessage('duplicateProfile');
    return;
  }
  const previousProfiles = S.profiles;
  const previousActive = S.active;
  const previousPreferredProfileKey = S.preferredProfileKey;
  const wasRunning = S.status === 'connected' || S.status === 'connecting';
  const importedIndex = S.profiles.length;
  S.profiles = [...S.profiles, p];
  if (!wasRunning) {
    S.active = importedIndex;
    setPreferredProfile(importedIndex);
  }
  try {
    await save();
  } catch (error) {
    S.profiles = previousProfiles;
    S.active = previousActive;
    S.preferredProfileKey = previousPreferredProfileKey;
    showMessage(t('settingsSaveError') + error, false);
    return;
  }
  syncUI();
  renderProfiles();
  hide('overlay-add');
  if (wasRunning) {
    if (await showConfirm('connectImportedProfile')) {
      await selectProfile(importedIndex, { closeProfiles: false });
    }
  } else {
    await startVpn({ preserveActiveProfile: true });
  }
}

let loadedProcesses = [];

async function loadRunningProcesses() {
  const listEl = $('running-apps-list');
  showListMessage(listEl, t('loadingProcesses'));
  try {
    const excludeSystem = $('running-apps-exclude-system').checked;
    const list = await invoke('get_running_processes', { excludeSystem });

    const currentVal = $('s-apps-list').value.trim();
    const currentApps = currentVal.split(',').map(x => x.trim()).filter(Boolean);
    const byName = new Map();
    [...list, ...currentApps].forEach(name => {
      const key = name.toLowerCase();
      if (!byName.has(key)) byName.set(key, name);
    });

    const selected = new Set(currentApps.map(name => name.toLowerCase()));
    loadedProcesses = [...byName.values()].map(name => ({
      name,
      checked: selected.has(name.toLowerCase())
    })).sort((a, b) => Number(b.checked) - Number(a.checked)
      || a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));

    renderRunningProcessesList();
  } catch(e) {
    showListMessage(listEl, t('errorLabel') + e, true);
  }
}

function showListMessage(container, message, isError = false) {
  const element = createTextElement('div', isError ? 'list-message error' : 'list-message', message);
  container.replaceChildren(element);
}

function renderRunningProcessesList() {
  const listEl = $('running-apps-list');
  listEl.replaceChildren();

  const query = $('running-apps-search').value.trim().toLowerCase();
  const filtered = loadedProcesses.filter(p => p.name.toLowerCase().includes(query));

  if (filtered.length === 0) {
    showListMessage(listEl, t('nothingFound'));
    return;
  }

  filtered.forEach(p => {
    const itemEl = document.createElement('div');
    itemEl.className = 'app-item';

    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = p.checked;
    checkbox.onclick = (e) => {
      e.stopPropagation();
      p.checked = checkbox.checked;
    };

    const nameEl = document.createElement('span');
    nameEl.className = 'app-item-name';
    nameEl.textContent = p.name;

    itemEl.appendChild(checkbox);
    itemEl.appendChild(nameEl);

    itemEl.onclick = () => {
      checkbox.checked = !checkbox.checked;
      p.checked = checkbox.checked;
    };

    listEl.appendChild(itemEl);
  });
}

function confirmRunningAppsSelection() {
  const checkedApps = loadedProcesses.filter(p => p.checked).map(p => p.name);
  $('s-apps-list').value = checkedApps.join(', ');
  $('s-apps-list').dispatchEvent(new Event('change'));
  hide('overlay-running-apps');
}

const COUNTRY_MAP = {
  'нидерланды': 'nl', 'netherlands': 'nl', 'амстердам': 'nl', 'amsterdam': 'nl',
  'оаэ': 'ae', 'uae': 'ae', 'фуджейра': 'ae', 'fujairah': 'ae', 'дубай': 'ae', 'dubai': 'ae',
  'греция': 'gr', 'greece': 'gr', 'афины': 'gr', 'athens': 'gr',
  'австрия': 'at', 'austria': 'at', 'вена': 'at', 'vienna': 'at',
  'болгария': 'bg', 'bulgaria': 'bg', 'софия': 'bg', 'sofia': 'bg',
  'индия': 'in', 'india': 'in', 'бангалор': 'in', 'bangalore': 'in',
  'германия': 'de', 'germany': 'de', 'франкфурт': 'de', 'frankfurt': 'de', 'мюнхен': 'de', 'munich': 'de',
  'сша': 'us', 'usa': 'us', 'нью-йорк': 'us', 'new york': 'us', 'вашингтон': 'us', 'washington': 'us', 'майами': 'us', 'miami': 'us', 'лос-анджелес': 'us', 'los angeles': 'us',
  'великобритания': 'gb', 'uk': 'gb', 'лондон': 'gb', 'london': 'gb',
  'франция': 'fr', 'france': 'fr', 'париж': 'fr', 'paris': 'fr',
  'турция': 'tr', 'turkey': 'tr', 'стамбул': 'tr', 'istanbul': 'tr',
  'польша': 'pl', 'poland': 'pl', 'варшава': 'pl', 'warsaw': 'pl',
  'казахстан': 'kz', 'kazakhstan': 'kz', 'алматы': 'kz', 'almaty': 'kz', 'астана': 'kz', 'astana': 'kz',
  'финляндия': 'fi', 'finland': 'fi', 'хельсинки': 'fi', 'helsinki': 'fi',
  'швеция': 'se', 'sweden': 'se', 'стокгольм': 'se', 'stockholm': 'se',
  'швейцария': 'ch', 'switzerland': 'ch', 'цюрих': 'ch', 'zurich': 'ch', 'женева': 'ch', 'geneva': 'ch',
  'испания': 'es', 'spain': 'es', 'мадрид': 'es', 'madrid': 'es', 'барселона': 'es', 'barcelona': 'es',
  'италия': 'it', 'italy': 'it', 'милан': 'it', 'milan': 'it', 'рим': 'it', 'rome': 'it',
  'кипр': 'cy', 'cyprus': 'cy', 'никосия': 'cy', 'nicosia': 'cy',
  'япония': 'jp', 'japan': 'jp', 'токио': 'jp', 'tokyo': 'jp',
  'сингапур': 'sg', 'singapore': 'sg'
};

function getProfileDisplay(p) {
  let displayName = String(p.name || p.protocol || '');
  let flagEmoji = '';
  let countryCode = '';

  // 1. Check if name starts with flag emojis (Regional Indicator Symbols U+1F1E6 to U+1F1FF)
  const matchEmoji = displayName.match(/^([\uD83C][\uDDE6-\uDDFF]){2}/);
  if (matchEmoji) {
    const emoji = matchEmoji[0];
    const charCode1 = emoji.codePointAt(0) - 127397;
    const charCode2 = emoji.codePointAt(2) - 127397;
    countryCode = String.fromCharCode(charCode1, charCode2).toLowerCase();
    flagEmoji = emoji;
    displayName = displayName.substring(emoji.length).trim();
  } else {
    // 2. Check if name starts with two uppercase letters followed by a space (like 'NL ', 'AE ')
    const matchText = displayName.match(/^([A-Z]{2})\b\s*/);
    if (matchText) {
      countryCode = matchText[1].toLowerCase();
      displayName = displayName.substring(matchText[0].length).trim();
    } else {
      // 3. Fallback: Scan displayName for country/city matches from COUNTRY_MAP
      const lowerName = displayName.toLowerCase();
      for (const [key, code] of Object.entries(COUNTRY_MAP)) {
        if (lowerName.includes(key)) {
          countryCode = code;
          break;
        }
      }
    }
  }

  if (displayName) {
    displayName = displayName.charAt(0).toUpperCase() + displayName.slice(1);
  }

  return {
    name: displayName || p.name,
    flagEmoji: flagEmoji || countryCodeToEmoji(countryCode),
    countryCode
  };
}

function countryCodeToEmoji(countryCode) {
  if (!/^[a-z]{2}$/i.test(countryCode)) return '';
  return [...countryCode.toUpperCase()]
    .map(letter => String.fromCodePoint(127397 + letter.charCodeAt(0)))
    .join('');
}

function createFlagElement(countryCode, fallbackEmoji) {
  if (/^[a-z]{2}$/i.test(countryCode)) {
    const flag = document.createElement('img');
    flag.className = 'p-flag';
    flag.src = `assets/flags/${countryCode.toLowerCase()}.svg`;
    flag.alt = '';
    flag.setAttribute('aria-hidden', 'true');
    return flag;
  }

  return fallbackEmoji ? createTextElement('span', 'p-flag-emoji', fallbackEmoji) : null;
}

function createTextElement(tag, className, text) {
  const element = document.createElement(tag);
  element.className = className;
  element.textContent = text;
  return element;
}

function createActiveDot() {
  const dot = document.createElement('span');
  dot.className = 'active-dot';
  dot.setAttribute('aria-label', t('connected'));
  return dot;
}

function createGroupArrow() {
  const arrow = document.createElement('span');
  arrow.className = 'group-arrow';
  arrow.setAttribute('aria-hidden', 'true');
  arrow.innerHTML = `
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
      <polyline points="6 9 12 15 18 9"></polyline>
    </svg>
  `;
  return arrow;
}

function createProfileQr(raw) {
  if (typeof window.qrcode !== 'function') throw new Error('QR generator is unavailable');
  const qr = window.qrcode(0, 'L');
  qr.addData(raw, 'Byte');
  qr.make();
  return qr.createDataURL(5, 10);
}

function showShareValue(value) {
  if (!value) {
    showMessage('invalidFormat');
    return;
  }
  try {
    $('share-qr').src = createProfileQr(value);
  } catch (error) {
    showMessage(String(error), false);
    return;
  }
  $('share-link').value = value;
  show('overlay-share');
}

function shareableSubscriptionUrl(subscription) {
  const url = new URL(subscription.url);
  url.hash = subscription.name;
  return url.href;
}

async function selectProfile(index, { closeProfiles = true } = {}) {
  if (!S.profiles[index]) return false;

  const previousActive = S.active;
  const previousPreferredProfileKey = S.preferredProfileKey;
  const wasActive = S.status === 'connected' || S.status === 'connecting';
  const previousRuntimeIndex = S.runtimeProfileKeys.indexOf(profileRuntimeKey(S.profiles[previousActive]));
  const previousPreferredRuntimeIndex = S.runtimeProfileKeys.indexOf(previousPreferredProfileKey);
  const nextRuntimeIndex = S.runtimeProfileKeys.indexOf(profileRuntimeKey(S.profiles[index]));
  let switchedInPlace = false;
  let stoppedForRestart = false;

  if (wasActive && S.status === 'connected' && nextRuntimeIndex >= 0) {
    try {
      await invoke('switch_vpn_outbound', { outbound: `profile-${nextRuntimeIndex + 1}` });
      switchedInPlace = true;
    } catch (error) {
      showMessage(t('profileSwitchError') + error, false);
      return false;
    }
  } else if (wasActive) {
    if (!await stopVpn()) return false;
    stoppedForRestart = true;
  }

  S.active = index;
  setPreferredProfile(index);
  try {
    await save();
  } catch (error) {
    S.active = previousActive;
    S.preferredProfileKey = previousPreferredProfileKey;
    if (switchedInPlace && previousRuntimeIndex >= 0) {
      try {
        await invoke('switch_vpn_outbound', { outbound: `profile-${previousRuntimeIndex + 1}` });
      } catch (rollbackError) {
        await logMsg(`Profile switch rollback failed: ${rollbackError}`);
      }
    }
    if (switchedInPlace && previousPreferredRuntimeIndex >= 0) {
      try {
        await invoke('set_preferred_outbound', {
          outbound: `profile-${previousPreferredRuntimeIndex + 1}`,
        });
      } catch (rollbackError) {
        await logMsg(`Preferred profile rollback failed: ${rollbackError}`);
      }
    }
    syncUI();
    showMessage(t('settingsSaveError') + error, false);
    if (stoppedForRestart) await startVpn();
    return false;
  }

  if (switchedInPlace) await refreshVpnHealth();
  syncUI();
  if (closeProfiles) hide('overlay-profiles');
  if (stoppedForRestart) await startVpn();
  return true;
}

function createProfileItemEl(p, i) {
  const el = document.createElement('div');
  el.className = 'p-item' + (i === S.active ? ' active' : '');

  const display = getProfileDisplay(p);
  if (i === S.active) {
    const statusSlot = document.createElement('span');
    statusSlot.className = 'p-status-slot';
    statusSlot.appendChild(createActiveDot());
    el.appendChild(statusSlot);
  }

  const flag = createFlagElement(display.countryCode, display.flagEmoji);
  if (flag) {
    const flagWrapper = document.createElement('span');
    flagWrapper.className = 'p-flag-wrapper';
    flagWrapper.appendChild(flag);
    el.appendChild(flagWrapper);
  }

  const infoEl = document.createElement('div');
  infoEl.className = 'p-item-info';
  const titleEl = document.createElement('div');
  titleEl.className = 'p-item-title';
  titleEl.appendChild(createTextElement('span', 'p-item-name', display.name));
  infoEl.appendChild(titleEl);
  infoEl.appendChild(createTextElement(
    'span',
    'p-item-proto',
    `${String(p.protocol).toUpperCase()} · ${p.host}:${p.port}`
  ));
  el.appendChild(infoEl);

  const actionsEl = document.createElement('div');
  actionsEl.className = 'p-item-actions';
  actionsEl.innerHTML = `
    <button class="p-share" data-i="${i}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="18" cy="5" r="3"></circle>
        <circle cx="6" cy="12" r="3"></circle>
        <circle cx="18" cy="19" r="3"></circle>
        <line x1="8.59" y1="13.51" x2="15.42" y2="17.49"></line>
        <line x1="15.41" y1="6.51" x2="8.59" y2="10.49"></line>
      </svg>
    </button>
    <button class="p-del" data-i="${i}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
        <polyline points="3 6 5 6 21 6"></polyline>
        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
      </svg>
    </button>
  `;
  el.appendChild(actionsEl);

  el.onclick = async e => {
    if (e.target.classList.contains('p-share') || e.target.closest('.p-share')) {
      const idx = parseInt(e.target.closest('.p-share').dataset.i);
      const profile = S.profiles[idx];
      if (!profile?.raw) {
        showMessage('invalidFormat');
        return;
      }
      showShareValue(profile.raw);
      return;
    }
    if (e.target.classList.contains('p-del') || e.target.closest('.p-del')) {
      const idx = parseInt(e.target.closest('.p-del').dataset.i);
      const name = S.profiles[idx]?.name || '';
      const shouldDelete = await showConfirm(t('deleteConfirm') + ' "' + name + '"?', false);
      if (shouldDelete) {
        const snapshot = captureProfileState();
        const deletingActive = idx === S.active;
        const deletingPreferred = profileRuntimeKey(S.profiles[idx]) === S.preferredProfileKey;
        const deletedRuntimeIndex = S.runtimeProfileKeys.indexOf(profileRuntimeKey(S.profiles[idx]));
        const nextProfiles = snapshot.profiles.filter((_, profileIndex) => profileIndex !== idx);
        let nextActive = snapshot.active;
        if (idx < nextActive) nextActive -= 1;
        if (deletingActive) nextActive = Math.min(idx, Math.max(nextProfiles.length - 1, 0));
        const nextPreferredProfileKey = deletingPreferred
          ? profileRuntimeKey(nextProfiles[nextActive])
          : snapshot.preferredProfileKey;
        try {
          await commitProfileState(snapshot, {
            profiles: nextProfiles,
            subscriptions: snapshot.subscriptions,
            active: nextActive,
            preferredProfileKey: nextPreferredProfileKey,
          }, 'Profile delete', { restartRuntime: deletingActive });
          if (!deletingActive && deletedRuntimeIndex >= 0) {
            try {
              await invoke('forget_vpn_outbound', {
                outbound: `profile-${deletedRuntimeIndex + 1}`,
              });
            } catch (error) {
              await logMsg(`Profile runtime cleanup deferred: ${error}`);
            }
          }
        } catch (error) {
          showMessage(t('settingsSaveError') + error, false);
        }
      }
      return;
    }
    await selectProfile(i);
  };
  return el;
}

function renderProfiles() {
  const list = $('profile-list');
  list.innerHTML = '';
  const title = $('profiles-drawer-title');
  const addButton = $('profiles-add-btn');
  const backButton = $('profiles-back-btn');
  const closeButton = $('close-profiles');
  const { groups, ungrouped } = partitionProfiles();

  if (profilesViewGroup && !groups[profilesViewGroup]) profilesViewGroup = null;
  const isGroupView = profilesViewGroup !== null;
  title.textContent = isGroupView ? profilesViewGroup : t('profilesTitle');
  addButton.classList.toggle('hidden', isGroupView);
  backButton.classList.toggle('hidden', !isGroupView);
  closeButton.classList.toggle('hidden', isGroupView);

  if (isGroupView) {
    groups[profilesViewGroup].forEach(item => {
      list.appendChild(createProfileItemEl(item.profile, item.index));
    });
    return;
  }

  if (!S.profiles.length) {
    list.appendChild(createTextElement('div', 'empty-msg', t('emptyMsg')));
    return;
  }

  // Render groups as navigation rows. Their profiles live on a separate view.
  Object.keys(groups).forEach(groupName => {
    const groupContainer = document.createElement('div');
    const groupItems = groups[groupName];
    const hasActive = groupItems.some(item => item.index === S.active);
    const subscriptionIds = [...new Set(groupItems
      .map(item => item.profile.subscriptionId)
      .filter(Boolean))];
    const subscription = subscriptionIds.length === 1
      ? S.subscriptions.find(item => item.id === subscriptionIds[0])
      : null;
    groupContainer.className = 'profile-group-container' + (hasActive ? ' has-active' : '');

    const header = document.createElement('div');
    header.className = 'profile-group-header';
    if (hasActive) {
      const groupStatusSlot = document.createElement('span');
      groupStatusSlot.className = 'p-status-slot';
      groupStatusSlot.appendChild(createActiveDot());
      header.appendChild(groupStatusSlot);
    }

    const groupInfo = document.createElement('div');
    groupInfo.className = 'group-info';
    const groupTitleRow = document.createElement('div');
    groupTitleRow.className = 'group-title-row';
    groupTitleRow.appendChild(createTextElement('span', 'group-title', groupName));
    groupInfo.appendChild(groupTitleRow);
    groupInfo.appendChild(createTextElement('span', 'group-count', serverCountLabel(groupItems.length)));
    header.appendChild(groupInfo);

    const groupActions = document.createElement('div');
    groupActions.className = 'group-actions';
    if (subscription) {
      const shareButton = document.createElement('button');
      shareButton.className = 'p-share group-share';
      shareButton.type = 'button';
      shareButton.title = t('shareTitle');
      shareButton.setAttribute('aria-label', t('shareTitle'));
      shareButton.innerHTML = `
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="18" cy="5" r="3"></circle><circle cx="6" cy="12" r="3"></circle><circle cx="18" cy="19" r="3"></circle>
          <line x1="8.59" y1="13.51" x2="15.42" y2="17.49"></line><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"></line>
        </svg>`;
      groupActions.appendChild(shareButton);
    }
    const deleteButton = document.createElement('button');
    deleteButton.className = 'p-del group-delete';
    deleteButton.type = 'button';
    deleteButton.title = t('deleteGroupConfirm');
    deleteButton.setAttribute('aria-label', t('deleteGroupConfirm'));
    deleteButton.innerHTML = `
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
        <polyline points="3 6 5 6 21 6"></polyline>
        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
      </svg>`;
    groupActions.appendChild(deleteButton);
    const openArrow = createGroupArrow();
    openArrow.classList.add('group-open-arrow');
    groupActions.appendChild(openArrow);
    header.appendChild(groupActions);

    header.onclick = async event => {
      if (event.target.closest('.group-share')) {
        showShareValue(shareableSubscriptionUrl(subscription));
        return;
      }
      if (event.target.closest('.group-delete')) {
        await deleteProfileGroup(groupName, groupItems, subscription);
        return;
      }
      profilesViewGroup = groupName;
      renderProfiles();
    };

    groupContainer.appendChild(header);
    list.appendChild(groupContainer);
  });

  // Render Ungrouped
  ungrouped.forEach(item => list.appendChild(createProfileItemEl(item.profile, item.index)));
}

function partitionProfiles(profiles = S.profiles) {
  const groups = {};
  const ungrouped = [];
  profiles.forEach((p, i) => {
    const item = { profile: p, index: i };
    if (p.group) {
      if (!groups[p.group]) groups[p.group] = [];
      groups[p.group].push(item);
    } else {
      ungrouped.push(item);
    }
  });
  return { groups, ungrouped };
}

async function deleteProfileGroup(groupName, groupItems, subscription) {
  const shouldDelete = await showConfirm(
    `${t('deleteGroupConfirm')} "${groupName}" (${groupItems.length})?`,
    false,
  );
  if (!shouldDelete) return;

  profilesViewGroup = null;
  const snapshot = captureProfileState();
  const deletedIndexes = new Set(groupItems.map(item => item.index));
  const nextProfiles = snapshot.profiles.filter((_, index) => !deletedIndexes.has(index));
  const nextSubscriptions = subscription
    ? snapshot.subscriptions.filter(item => item.id !== subscription.id)
    : snapshot.subscriptions;
  const previousPreferred = snapshot.profiles.find(profile =>
    profileRuntimeKey(profile) === snapshot.preferredProfileKey
  );
  const previousActive = snapshot.profiles[snapshot.active];
  let nextPreferred = nextProfiles.findIndex(profile =>
    profileRuntimeKey(profile) === profileRuntimeKey(previousPreferred)
  );
  let nextActive = nextProfiles.findIndex(profile =>
    profileRuntimeKey(profile) === profileRuntimeKey(previousActive)
  );
  if (nextPreferred < 0) nextPreferred = nextProfiles.length ? 0 : -1;
  if (nextActive < 0) nextActive = nextPreferred;
  const wasRunning = snapshot.status === 'connected' || snapshot.status === 'connecting';
  const deletingActive = deletedIndexes.has(snapshot.active);
  const deletedRuntimeIndexes = groupItems
    .map(item => S.runtimeProfileKeys.indexOf(profileRuntimeKey(item.profile)))
    .filter(index => index >= 0);

  try {
    await commitProfileState(snapshot, {
      profiles: nextProfiles,
      subscriptions: nextSubscriptions,
      active: wasRunning ? Math.max(nextPreferred, 0) : Math.max(nextActive, 0),
      preferredProfileKey: nextPreferred >= 0 ? profileRuntimeKey(nextProfiles[nextPreferred]) : '',
    }, 'Profile group delete', { restartRuntime: deletingActive });
    if (!deletingActive) {
      for (const runtimeIndex of deletedRuntimeIndexes) {
        try {
          await invoke('forget_vpn_outbound', { outbound: `profile-${runtimeIndex + 1}` });
        } catch (error) {
          await logMsg(`Profile group runtime cleanup deferred: ${error}`);
        }
      }
    }
  } catch (error) {
    showMessage(t('settingsSaveError') + String(error).replace(/^Error:\s*/, ''), false);
  }
}

/* ── Sing-box config generation ────────────────── */
function currentVpnSettings() {
  return {
    adblock: S.adblock,
    quic: S.quic,
    lan: S.lan,
    mtu: S.mtu,
    appsMode: S.appsMode,
    appsList: S.appsList,
    sitesMode: S.sitesMode,
    sitesList: S.sitesList,
  };
}

function profileRuntimeKey(profile) {
  if (!profile) return '';
  return profile.raw || [profile.protocol, profile.host, profile.port, profile.uuid].join('\u0000');
}

function preferredProfileIndex() {
  return S.profiles.findIndex(profile => profileRuntimeKey(profile) === S.preferredProfileKey);
}

function setPreferredProfile(index) {
  S.preferredProfileKey = profileRuntimeKey(S.profiles[index]);
}

function restorePreferredProfileSelection() {
  const index = preferredProfileIndex();
  if (index >= 0) S.active = index;
}

function makeConfig() {
  const settings = currentVpnSettings();
  const activeProfile = S.profiles[S.active];
  if (!activeProfile) throw new Error(t('addProfileHint'));

  const runtime = buildRuntimeSingBoxConfig(
    S.profiles,
    S.active,
    settings,
    S.warpyAuto,
  );

  return {
    config: JSON.stringify(runtime.config, null, 2),
    profileKeys: runtime.profileIndexes.map(index => profileRuntimeKey(S.profiles[index])),
  };
}

/* ── VPN control ───────────────────────────────── */
async function toggleVpn() {
  if (!S.profiles.length) { show('overlay-add'); return; }
  if (S.status === 'connected' || S.status === 'connecting' || S.status === 'error') {
    await stopVpn({ manual: true });
  } else {
    S.networkAutoBlocked = false;
    S.networkAutoBlockedReason = '';
    await startVpn();
  }
}

async function startVpnAfterSystemBoot() {
  const retryDelays = [5000, 5000, 10000, 15000];
  let expectedAttempt = S.connectAttempt;
  S.commandPending = 'start';
  S.commandError = '';
  S.showConnectedAlert = false;
  errorMsg.textContent = '';
  syncUI();

  for (let index = 0; index < retryDelays.length; index++) {
    await delay(retryDelays[index]);
    if (
      S.connectAttempt !== expectedAttempt ||
      !S.resumeOnBoot ||
      !S.profiles.length
    ) return;

    await logMsg(`Autostart connection attempt ${index + 1}/${retryDelays.length}`);
    const attemptBeforeStart = S.connectAttempt;
    await startVpn({ reportFailure: index === retryDelays.length - 1 });
    if (S.connectAttempt !== attemptBeforeStart + 1) return;
    expectedAttempt = S.connectAttempt;
    if (S.status === 'connected') return;
  }
}

async function startVpn({ reportFailure = true, preserveActiveProfile = false } = {}) {
  if (!preserveActiveProfile) restorePreferredProfileSelection();
  const attempt = ++S.connectAttempt;
  S.startCommandAttempt = attempt;
  S.commandPending = 'start';
  S.commandError = '';
  S.showConnectedAlert = false;
  errorMsg.textContent = '';
  syncUI();
  try {
    const snapshot = await getVpnRuntimeSnapshot();
    if (snapshot.competingVpn) throw new Error(t('otherVpnActive'));
    const runtime = makeConfig();
    S.runtimeProfileKeys = [];
    await invoke('start_vpn', {
      config: runtime.config,
      killSwitch: S.killSwitch,
      autoMode: S.warpyAuto,
    });
    if (attempt !== S.connectAttempt) return;
    const serviceSnapshot = await getVpnRuntimeSnapshot();
    if (!serviceSnapshot.status.startsWith('Connected')) {
      throw new Error(`${t('failedToConnect')} (${serviceSnapshot.status})`);
    }

    if (attempt !== S.connectAttempt) return;
    S.runtimeProfileKeys = runtime.profileKeys;
    S.commandPending = null;
    await applyServiceConnectionSnapshot(serviceSnapshot, { showConnectedAlert: true });
    await refreshVpnHealth();
    setTimeout(() => {
      if (attempt === S.connectAttempt && S.status === 'connected') {
        S.showConnectedAlert = false;
        syncUI();
      }
    }, 1250);
  } catch(e) {
    if (attempt !== S.connectAttempt) return;
    let cleanupError = '';
    try {
      await invoke('stop_vpn');
      const cleanupSnapshot = await getVpnRuntimeSnapshot();
      if (cleanupSnapshot.status !== 'Stopped') cleanupError = ` (${cleanupSnapshot.status})`;
      await applyServiceConnectionSnapshot(cleanupSnapshot);
    } catch (error) {
      cleanupError = ` (${String(error).replace(/^Error:\s*/, '')})`;
    }
    S.runtimeProfileKeys = [];
    const rawError = String(e).replace(/^Error:\s*/, '');
    const message = rawError.includes('ANOTHER_VPN_ACTIVE') ? t('otherVpnActive') : rawError;
    S.commandError = reportFailure
      ? `${message || t('trafficCheckFailed')}${cleanupError}`
      : '';
    syncUI();
  } finally {
    if (S.startCommandAttempt === attempt) S.startCommandAttempt = 0;
    if (S.commandPending === 'start') S.commandPending = null;
    syncUI();
  }
}

async function stopVpn({ manual = false } = {}) {
  if (manual && S.networkAutoProtect) {
    S.networkAutoBlocked = true;
    S.networkAutoBlockedReason = 'manual';
  }
  ++S.connectAttempt;
  S.startCommandAttempt = 0;
  S.commandPending = 'stop';
  S.commandError = '';
  syncUI();
  try {
    await invoke('stop_vpn');
    const snapshot = await getVpnRuntimeSnapshot();
    if (snapshot.status !== 'Stopped') throw new Error(`${t('failedToDisconnect')} (${snapshot.status})`);
    S.commandPending = null;
    await applyServiceConnectionSnapshot(snapshot);
  } catch (error) {
    S.commandPending = null;
    S.commandError = String(error).replace(/^Error:\s*/, '');
    S.showConnectedAlert = false;
    S.runtimeProfileKeys = [];
    syncUI();
    return false;
  }
  S.showConnectedAlert = false;
  S.runtimeProfileKeys = [];
  restorePreferredProfileSelection();
  stopTimers();
  syncUI();
  scheduleAutomaticSubscriptionRefresh(1000);
  return true;
}

async function checkStatus() {
  const activeStatus = ['connected', 'connecting', 'error'].includes(S.status);
  if (
    (!activeStatus && !S.networkAutoProtect) ||
    S.statusCheckRunning ||
    S.startCommandAttempt !== 0
  ) return;
  S.statusCheckRunning = true;
  try {
    const snapshot = await getVpnRuntimeSnapshot();
    S.statusCheckFailures = 0;
    const st = snapshot.status;
    const autoProtectionStarted = await applyNetworkAutoProtection(snapshot);
    if (autoProtectionStarted || S.status === 'stopped') return;
    const notificationTransition = connectivityNotificationTransition(
      S.status,
      st,
      S.recoveryNotificationPending,
    );
    if (st.startsWith('Connected')) {
      S.commandError = '';
      await applyServiceConnectionSnapshot(snapshot);
      await refreshVpnHealth();
    } else if (isTransientServiceStatus(st)) {
      await applyServiceConnectionSnapshot(snapshot);
    } else {
      if (st === 'Stopped' && !snapshot.desiredRunning) S.commandError = '';
      await applyServiceConnectionSnapshot(snapshot);
    }
    S.recoveryNotificationPending = notificationTransition.recoveryPending;
    if (notificationTransition.event === 'restored') {
      sendSignificantNotification(
        'vpn-restored',
        t('notificationVpnRestoredTitle'),
        t('notificationVpnRestoredBody'),
      );
    } else if (notificationTransition.event === 'failed') {
      sendSignificantNotification(
        'vpn-failed',
        t('notificationVpnFailedTitle'),
        t('notificationVpnFailedBody'),
      );
    }
  } catch(e) {
    S.statusCheckFailures++;
    console.error('VPN status check failed:', e);
    if (
      S.statusCheckFailures >= 3
      && ['connected', 'connecting', 'error'].includes(S.status)
      && (S.status !== 'error' || errorMsg.textContent !== t('serviceUnavailable'))
    ) {
      S.commandError = t('serviceUnavailable');
      S.showConnectedAlert = false;
      stopTimers();
      syncUI();
    }
  } finally {
    S.statusCheckRunning = false;
  }
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

async function verifyVpnTraffic(timeoutMs = 3000, bytes = 1024) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    try {
      const response = await fetch(
        `https://speed.cloudflare.com/__down?bytes=${bytes}&health=${Date.now()}-${Math.random()}`,
        { cache: 'no-store', signal: controller.signal }
      );
      if (response.ok) {
        const body = await response.arrayBuffer();
        if (body.byteLength >= Math.min(bytes, 1024)) return performance.now();
      }
    } catch (e) {
      if (controller.signal.aborted) throw e;
    }

    const fallbackResponse = await fetch('https://www.google.com/generate_204', {
      cache: 'no-store',
      signal: controller.signal,
    });
    if (fallbackResponse.ok || fallbackResponse.status === 204) {
      return performance.now();
    }
    throw new Error(`HTTP ${fallbackResponse.status}`);
  } finally {
    clearTimeout(timeout);
  }
}

async function restoreBackendState() {
  try {
    const snapshot = await getVpnRuntimeSnapshot();
    const status = snapshot.status;
    if (isTransientServiceStatus(status)) {
      await applyServiceConnectionSnapshot(snapshot);
      return;
    }
    if (status === 'Stopped' && !snapshot.desiredRunning) {
      await applyServiceConnectionSnapshot(snapshot);
      return;
    }
    if (!status.startsWith('Connected')) {
      await applyServiceConnectionSnapshot(snapshot);
      return;
    }
    S.statusCheckFailures = 0;
    S.commandError = '';
    await applyServiceConnectionSnapshot(snapshot);
    try {
      S.runtimeProfileKeys = makeConfig().profileKeys;
    } catch (error) {
      await logMsg(`Runtime profile mapping failed: ${error}`);
    }
    await refreshVpnHealth();
  } catch (error) {
    S.commandError = t('serviceUnavailable');
    await logMsg(`Backend state restore failed: ${error}`);
    syncUI();
  }
}

/* ── Timers ────────────────────────────────────── */
function startTimers() {
  stopTimers();
  const updateUptime = () => {
    const d = Date.now() - S.connectedAt;
    const totalSecs = Math.floor(d / 1000);
    const h = Math.floor(totalSecs / 3600);
    const m = Math.floor((totalSecs % 3600) / 60);
    const s = totalSecs % 60;

    const mm = String(m).padStart(2, '0');
    const ss = String(s).padStart(2, '0');

    if (h > 0) {
      const hh = String(h).padStart(2, '0');
      uptimeEl.textContent = hh + ':' + mm + ':' + ss;
    } else {
      uptimeEl.textContent = mm + ':' + ss;
    }
  };
  updateUptime();
  S.uptimeTimer = setInterval(updateUptime, 1000);
  updateNetworkMetrics();
  updatePingMetric();
  S.speedTimer = setInterval(updateNetworkMetrics, 1500);
  S.pingTimer = setInterval(updatePingMetric, 10_000);
}

async function updateNetworkMetrics() {
  if (S.status !== 'connected' || S.networkMetricsRunning) return;
  S.networkMetricsRunning = true;
  try {
    const sample = await invoke('get_vpn_network_stats');
    const now = performance.now();
    if (!sample.available) {
      mSpeed.textContent = '—';
      S.lastNetworkStats = null;
      return;
    }
    if (S.lastNetworkStats) {
      const elapsedSeconds = (now - S.lastNetworkStats.at) / 1000;
      const receivedBytes = Math.max(0, sample.received - S.lastNetworkStats.received);
      const transmittedBytes = Math.max(0, sample.transmitted - S.lastNetworkStats.transmitted);
      const currentBytes = Math.max(receivedBytes, transmittedBytes);
      mSpeed.textContent = String(Math.round(currentBytes / Math.max(elapsedSeconds, 0.1) / 1024));
    } else {
      mSpeed.textContent = '0';
    }
    S.lastNetworkStats = { received: sample.received, transmitted: sample.transmitted, at: now };
  } catch (error) {
    console.error('Network metrics failed:', error);
    mSpeed.textContent = '—';
  } finally {
    S.networkMetricsRunning = false;
  }
}

async function updatePingMetric() {
  if (S.status !== 'connected') return;
  const startedAt = performance.now();
  try {
    await verifyVpnTraffic(3000, 1024);
    mPing.textContent = String(Math.round(performance.now() - startedAt));
  } catch {
    mPing.textContent = '—';
  }
}
function stopTimers() {
  clearInterval(S.uptimeTimer);
  clearInterval(S.speedTimer);
  clearInterval(S.pingTimer);
  S.uptimeTimer = null;
  S.speedTimer = null;
  S.pingTimer = null;
  S.lastNetworkStats = null;
}

async function verifyConnectionAfterResume() {
  if (!['connected', 'connecting', 'error'].includes(S.status) || S.resumeCheckRunning) return;
  S.resumeCheckRunning = true;
  try {
    await delay(600);
    await checkStatus();
  } catch (error) {
    await logMsg(`Status sync after resume failed: ${error}`);
  } finally {
    S.resumeCheckRunning = false;
  }
}

/* ── Sync UI ───────────────────────────────────── */
function syncUI() {
  const p = S.profiles[S.active];
  const uiStatus = uiConnectionStatus();
  const conn = uiStatus === 'connected';
  const conng = uiStatus === 'connecting';
  const err = uiStatus === 'error' || Boolean(S.commandError);

  // Power button
  powerBtn.className = 'power-btn' + (conn ? ' connected' : conng ? ' connecting' : err ? ' error' : '');
  powerBtn.classList.toggle('empty', !p);

  // Particles & fill animations triggering
  if (conn || conng) {
    startAnimation();
  } else {
    stopAnimation();
  }

  // Uptime, Spinner & Status Alert
  if (conng) {
    uptimeEl.classList.remove('visible');
    $('status-alert').classList.remove('visible');
    $('glow-container').classList.remove('glow-active');
    show('spinner-container');
  } else if (conn) {
    hide('spinner-container');
    if (S.showConnectedAlert) {
      uptimeEl.classList.remove('visible');
      $('status-alert').classList.add('visible');
      $('glow-container').classList.add('glow-active');
    } else {
      $('status-alert').classList.remove('visible');
      $('glow-container').classList.remove('glow-active');
      uptimeEl.classList.add('visible');
    }
  } else {
    uptimeEl.classList.remove('visible');
    $('status-alert').classList.remove('visible');
    $('glow-container').classList.remove('glow-active');
    hide('spinner-container');
  }

  // Protocol chip
  if (p) { protocolChip.textContent = p.protocol.charAt(0).toUpperCase() + p.protocol.slice(1); protocolChip.style.display = ''; }
  else protocolChip.style.display = 'none';

  // Metrics
  metricsEl.style.display = conn ? '' : 'none';
  if (!conn) { mSpeed.textContent = '0'; mPing.textContent = '—'; }

  // Error
  if (S.commandError) errorMsg.textContent = S.commandError;
  else if (!err) errorMsg.textContent = '';

  localizeUI();
  scheduleTrayMenuUpdate();
}

/* ── Speedtest Engine ───────────────────────────── */
let speedtestRunning = false;
let speedtestAbortController = null;
let speedtestAutoStartTimer = null;
let currentMbps = 0;
let smoothedMbps = 0;
let speedtestStage = 'idle';
const warpStars = [];
const maxStars = 35;
const cx = 100;
const cy = 100;

function spawnStar(randomStart = false) {
  const angle = Math.random() * Math.PI * 2;
  const r = randomStart ? (Math.random() * 70 + 25) : (Math.random() * 8 + 28);

  let baseColor = '255,255,255';
  const rand = Math.random();
  if (speedtestStage === 'download') {
    if (rand < 0.12) baseColor = '0,192,127';
  } else if (speedtestStage === 'upload') {
    if (rand < 0.12) baseColor = '77,163,255';
  }

  return {
    angle,
    r,
    prevR: r,
    speed: Math.random() * 0.8 + 0.3,
    baseColor,
    width: Math.random() * 1.2 + 0.5,
    maxR: 90 + Math.random() * 30
  };
}

function initWarpStars() {
  warpStars.length = 0;
  for (let i = 0; i < maxStars; i++) {
    warpStars.push(spawnStar(true));
  }
}

function drawWarpFrame() {
  if (!speedtestRunning || !isWindowVisible) return;
  const canvas = $('speedtest-canvas');
  if (!canvas) return;
  const ctx = canvas.getContext('2d');

  // Fully transparent each frame — no background fill at all
  ctx.clearRect(0, 0, 200, 200);

  const speedFactor = 0.5 + Math.min(currentMbps / 300, 1.0) * 4.5;

  for (let i = 0; i < warpStars.length; i++) {
    const s = warpStars[i];
    s.prevR = s.r;
    s.r += s.speed * speedFactor;

    // Only draw outside center zone
    if (s.r > 30) {
      // tail goes from prevR to current r (per-frame segment)
      const headR = s.r;
      const tailR = Math.max(30, s.prevR);

      // Fade in near center, fade out near maxR
      const fadeIn = Math.min(1, (s.r - 30) / 18);
      const fadeOut = Math.max(0, 1 - (s.r - (s.maxR - 18)) / 18);
      const alpha = Math.min(fadeIn, fadeOut) * 0.85;

      if (alpha > 0.01) {
        const x1 = cx + Math.cos(s.angle) * headR;
        const y1 = cy + Math.sin(s.angle) * headR;
        const x2 = cx + Math.cos(s.angle) * tailR;
        const y2 = cy + Math.sin(s.angle) * tailR;

        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.lineTo(x2, y2);
        ctx.strokeStyle = `rgba(${s.baseColor},${alpha})`;
        ctx.lineWidth = s.width;
        ctx.lineCap = 'round';
        ctx.stroke();
      }
    }

    if (s.r > s.maxR) {
      warpStars[i] = spawnStar(false);
    }
  }
  requestAnimationFrame(drawWarpFrame);
}

function showSpeedtest() {
  $('speedtest-running-area').classList.remove('hidden');
  $('speedtest-results-area').classList.add('hidden');

  $('speedtest-stage').textContent = '';
  setGaugeValue(0, t('mbps'));

  currentMbps = 0;
  speedtestStage = 'idle';
  initWarpStars();

  $('speedtest-btn-action').textContent = t('speedtestBtnStart');
  $('speedtest-btn-action').disabled = false;

  show('overlay-speedtest');

  clearTimeout(speedtestAutoStartTimer);
  speedtestAutoStartTimer = setTimeout(() => {
    speedtestAutoStartTimer = null;
    if ($('overlay-speedtest').classList.contains('hidden')) return;
    toggleSpeedtestRun();
  }, 100);
}

function hideSpeedtest() {
  clearTimeout(speedtestAutoStartTimer);
  speedtestAutoStartTimer = null;
  if (speedtestRunning) {
    cancelSpeedtest();
  }
  hide('overlay-speedtest');
}

function updateGaugeUI(mbps, stage, color) {
  currentMbps = mbps;
  $('speedtest-stage').textContent = stage;
  $('speedtest-stage').style.color = color || '';
  const unit = speedtestStage === 'ping' ? t('ms') : t('mbps');
  setGaugeValue(Math.round(mbps), unit);
}

function updateRealtimeGauge(mbps, stage, color) {
  smoothedMbps = smoothedMbps === 0 ? mbps : smoothedMbps * 0.65 + mbps * 0.35;
  updateGaugeUI(smoothedMbps, stage, color);
}

function setGaugeValue(value, unit) {
  const unitElement = createTextElement('span', 'gauge-unit', unit);
  $('speedtest-live-val').replaceChildren(document.createTextNode(String(value)), unitElement);
}

function showSpeedtestFailure(message) {
  speedtestStage = 'failed';
  currentMbps = 0;
  smoothedMbps = 0;
  $('speedtest-stage').textContent = message;
  $('speedtest-stage').style.color = '#ff575f';
  $('speedtest-live-val').replaceChildren(document.createTextNode('—'));
}

function cancelSpeedtest() {
  if (speedtestAbortController) {
    speedtestAbortController.abort();
    speedtestAbortController = null;
  }
  speedtestRunning = false;
  $('speedtest-btn-action').textContent = t('speedtestBtnStart');
  $('speedtest-btn-action').disabled = false;
  $('speedtest-stage').textContent = t('cancelled');
}

const SPEEDTEST_STREAMS = 4;
const SPEEDTEST_WARMUP_MS = 1000;
const SPEEDTEST_MEASURE_MS = 5000;

async function measurePing(signal) {
  const samples = await measureLatencySamples({
    attempts: 5,
    minSuccessful: 3,
    signal,
    timeoutMs: 5000,
    urlFactory: () => cloudflareDownloadUrl(1024, 'run'),
  }).catch(error => {
    if (signal.aborted) throw error;
    throw new Error(t('speedtestFailed'));
  });
  return median(samples);
}

function uploadWithProgress(url, payload, signal, onProgress) {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    let settled = false;

    const finish = (callback, value) => {
      if (settled) return;
      settled = true;
      signal.removeEventListener('abort', abort);
      callback(value);
    };
    const abort = () => {
      request.abort();
      finish(reject, new DOMException('Aborted', 'AbortError'));
    };

    signal.addEventListener('abort', abort, { once: true });
    request.upload.onprogress = event => onProgress(event.loaded, performance.now());
    request.onload = () => {
      if (request.status >= 200 && request.status < 300) finish(resolve);
      else finish(reject, new Error(`Upload HTTP ${request.status}`));
    };
    request.onerror = () => finish(reject, new Error('Upload network error'));
    request.onabort = () => finish(reject, new DOMException('Aborted', 'AbortError'));
    request.open('POST', url, true);
    request.send(payload);
  });
}

async function measureDownload(signal) {
  const stageController = new AbortController();
  const abortStage = () => stageController.abort();
  signal.addEventListener('abort', abortStage, { once: true });

  const startedAt = performance.now();
  const measureAt = startedAt + SPEEDTEST_WARMUP_MS;
  const finishAt = measureAt + SPEEDTEST_MEASURE_MS;
  let measuredBytes = 0;
  let lastUiBytes = 0;
  let lastUiAt = measureAt;
  let successfulStreams = 0;
  const timer = setTimeout(abortStage, SPEEDTEST_WARMUP_MS + SPEEDTEST_MEASURE_MS);

  const worker = async (streamId) => {
    try {
      while (!stageController.signal.aborted && performance.now() < finishAt) {
        const url = `https://speed.cloudflare.com/__down?bytes=100000000&stream=${streamId}&run=${Date.now()}-${Math.random()}`;
        const response = await fetch(url, { cache: 'no-store', signal: stageController.signal });
        if (!response.ok || !response.body) throw new Error(`Download HTTP ${response.status}`);
        successfulStreams++;
        const reader = response.body.getReader();

        while (!stageController.signal.aborted) {
          const { done, value } = await reader.read();
          if (done) break;
          const now = performance.now();
          if (now >= measureAt && now <= finishAt) measuredBytes += value.length;
          if (now >= measureAt && now - lastUiAt >= 160) {
            updateRealtimeGauge(
              toMbps(measuredBytes - lastUiBytes, now - lastUiAt),
              '▼ ▼ ▼',
              '#00c07f'
            );
            lastUiBytes = measuredBytes;
            lastUiAt = now;
          }
          if (now >= finishAt) {
            abortStage();
            break;
          }
        }
      }
    } catch (error) {
      if (!stageController.signal.aborted) throw error;
    }
  };

  try {
    const results = await Promise.allSettled(
      Array.from({ length: SPEEDTEST_STREAMS }, (_, index) => worker(index + 1))
    );
    if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
    const failed = results.find(result => result.status === 'rejected');
    if (successfulStreams < Math.ceil(SPEEDTEST_STREAMS / 2) || !measuredBytes) {
      throw failed?.reason || new Error('Download test returned no data');
    }
    return toMbps(measuredBytes, SPEEDTEST_MEASURE_MS);
  } finally {
    clearTimeout(timer);
    abortStage();
    signal.removeEventListener('abort', abortStage);
  }
}

async function measureUpload(signal) {
  const stageController = new AbortController();
  const abortStage = () => stageController.abort();
  signal.addEventListener('abort', abortStage, { once: true });

  const startedAt = performance.now();
  const measureAt = startedAt + SPEEDTEST_WARMUP_MS;
  const finishAt = measureAt + SPEEDTEST_MEASURE_MS;
  const payload = new Uint8Array(8 * 1024 * 1024);
  let measuredBytes = 0;
  let lastUiBytes = 0;
  let lastUiAt = measureAt;
  const activeStreams = new Set();
  const timer = setTimeout(abortStage, SPEEDTEST_WARMUP_MS + SPEEDTEST_MEASURE_MS);

  const worker = async (streamId) => {
    try {
      while (!stageController.signal.aborted && performance.now() < finishAt) {
        const requestStartedAt = performance.now();
        let lastLoaded = 0;
        let lastProgressAt = requestStartedAt;
        await uploadWithProgress(
          `https://speed.cloudflare.com/__up?stream=${streamId}&run=${Date.now()}-${Math.random()}`,
          payload,
          stageController.signal,
          (loaded, now) => {
            const deltaBytes = Math.max(0, loaded - lastLoaded);
            const intervalMs = Math.max(1, now - lastProgressAt);
            const overlapMs = Math.max(0, Math.min(now, finishAt) - Math.max(lastProgressAt, measureAt));
            if (overlapMs > 0 && deltaBytes > 0) {
              measuredBytes += deltaBytes * Math.min(1, overlapMs / intervalMs);
              activeStreams.add(streamId);
            }
            lastLoaded = loaded;
            lastProgressAt = now;

            if (now >= measureAt && now - lastUiAt >= 160) {
              updateRealtimeGauge(
                toMbps(measuredBytes - lastUiBytes, now - lastUiAt),
                '▲ ▲ ▲',
                '#4da3ff'
              );
              lastUiBytes = measuredBytes;
              lastUiAt = now;
            }
          }
        );
      }
    } catch (error) {
      if (!stageController.signal.aborted) throw error;
    }
  };

  try {
    const results = await Promise.allSettled(
      Array.from({ length: SPEEDTEST_STREAMS }, (_, index) => worker(index + 1))
    );
    if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
    const failed = results.find(result => result.status === 'rejected');
    if (activeStreams.size < Math.ceil(SPEEDTEST_STREAMS / 2) || !measuredBytes) {
      throw failed?.reason || new Error('Upload test returned no data');
    }
    return toMbps(measuredBytes, SPEEDTEST_MEASURE_MS);
  } finally {
    clearTimeout(timer);
    abortStage();
    signal.removeEventListener('abort', abortStage);
  }
}

async function toggleSpeedtestRun() {
  if (speedtestRunning) {
    logMsg('Speedtest cancel requested');
    cancelSpeedtest();
    return;
  }

  logMsg('Speedtest started');
  speedtestRunning = true;
  $('speedtest-btn-action').textContent = t('speedtestRunning');
  $('speedtest-btn-action').disabled = true;

  $('speedtest-running-area').classList.remove('hidden');
  $('speedtest-results-area').classList.add('hidden');

  speedtestAbortController = new AbortController();
  const signal = speedtestAbortController.signal;

  let pingResult = '—';
  let downloadResult = '—';
  let uploadResult = '—';

  try {
    const snapshot = await getVpnRuntimeSnapshot();
    if (snapshot.competingVpn) throw new Error(t('otherVpnActive'));

    // Start Warp Frame Drawing
    requestAnimationFrame(drawWarpFrame);

    // Warm the connection, then use the median of five latency samples.
    logMsg('1. Ping stage starting...');
    speedtestStage = 'ping';
    currentMbps = 0;
    smoothedMbps = 0;
    updateGaugeUI(0, '...', '#00c07f');
    const pingVal = await measurePing(signal);
    pingResult = pingVal > 0 ? `${Math.round(pingVal)}` : '—';
    logMsg(`Ping stage completed. Median: ${pingResult} ms`);

    // 2. DOWNLOAD STAGE
    logMsg('2. Download stage starting...');
    speedtestStage = 'download';
    currentMbps = 0;
    smoothedMbps = 0;
    updateGaugeUI(0, '▼ ▼ ▼', '#00c07f');
    const finalDlMbps = await measureDownload(signal);
    downloadResult = finalDlMbps.toFixed(0);
    logMsg(`Download completed: ${downloadResult} Mbps`);

    // 3. UPLOAD STAGE
    logMsg('3. Upload stage starting...');
    speedtestStage = 'upload';
    currentMbps = 0;
    smoothedMbps = 0;
    updateGaugeUI(0, '▲ ▲ ▲', '#4da3ff');
    const finalUlMbps = await measureUpload(signal);
    uploadResult = finalUlMbps.toFixed(0);
    logMsg(`Upload completed: ${uploadResult} Mbps`);

    // 4. DISPLAY RESULTS
    if (signal.aborted) {
      logMsg('Display results skipped (aborted)');
      return;
    }
    speedtestStage = 'finished';
    $('speedtest-running-area').classList.add('hidden');
    $('speedtest-results-area').classList.remove('hidden');

    $('speedtest-res-down').textContent = downloadResult;
    $('speedtest-res-up').textContent = uploadResult;
    $('speedtest-res-ping').textContent = pingResult;

    $('speedtest-btn-action').textContent = t('speedtestBtnStart');
    $('speedtest-btn-action').disabled = false;
    speedtestRunning = false;
    speedtestAbortController = null;
    logMsg(`Speedtest finished successfully. Results: DL=${downloadResult} UL=${uploadResult} Ping=${pingResult}`);

  } catch (err) {
    logMsg(`Speedtest failed with error: ${err.message} | Stack: ${err.stack}`);
    if (!signal.aborted) {
      const rawError = String(err).replace(/^Error:\s*/, '');
      showSpeedtestFailure(
        rawError.includes(t('otherVpnActive')) ? t('otherVpnActive') : t('speedtestFailed'),
      );
      $('speedtest-btn-action').textContent = t('speedtestBtnStart');
      $('speedtest-btn-action').disabled = false;
      speedtestRunning = false;
      speedtestAbortController = null;
    }
  }
}

document.addEventListener('visibilitychange', () => {
  isWindowVisible = !document.hidden;
  if (isWindowVisible) {
    scheduleAutomaticSubscriptionRefresh();
    void verifyConnectionAfterResume();
    let shouldAnimate = false;
    if (S.status === 'connecting') {
      shouldAnimate = true;
    } else if (S.status === 'connected') {
      if (connectedStart !== null) {
        const elapsed = (performance.now() - animStart) / 1000;
        if (elapsed - connectedStart < 2.0) {
          shouldAnimate = true;
        }
      } else {
        shouldAnimate = true;
      }
    } else if (disconnectTime) {
      shouldAnimate = true;
    }

    if (shouldAnimate) {
      if (!rafId) rafId = requestAnimationFrame(drawFrame);
    } else {
      // Draw a single frame to ensure UI is fresh
      requestAnimationFrame(drawFrame);
    }
    if (speedtestRunning) requestAnimationFrame(drawWarpFrame);
  } else {
    if (rafId) {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
  }
});

/* ── Go ────────────────────────────────────────── */
document.addEventListener('DOMContentLoaded', init);
