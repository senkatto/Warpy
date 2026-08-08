import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourceFiles = [
  'src/clipboard-import.js',
  'src/index.css',
  'src/index.html',
  'src/index.js',
  'src/network-measurements.js',
  'src/subscription.js',
  'src/tray-profiles.js',
  'src/significant-notifications.js',
  'src/vpn-config.js',
  'src-tauri/src/diagnostics.rs',
  'src-tauri/src/main.rs',
  'src-tauri/src/network_context.rs',
  'src-tauri/src/tray_menu.rs',
  'src-tauri/src/subscription.rs',
  'src-tauri/src/updates.rs',
  'src-tauri/src/vpn_engine.rs',
  'src-tauri/src/vpn_ipc.rs',
  'src-tauri/src/vpn_selector.rs',
  'src-tauri/src/vpn_service.rs',
  'src-tauri/src/windows_autostart.rs',
  'src-tauri/src/windows_connectivity.rs',
  'src-tauri/app.manifest',
  'src-tauri/installer.nsi',
  'src-tauri/tauri.conf.json',
  'src-tauri/tauri.updater.conf.json',
  'src-tauri/windows-installer-hooks.nsh',
];

test('Android and Windows versions stay internally consistent', async () => {
  const [packageJsonRaw, packageLockRaw, cargoToml, tauriConfigRaw, html, androidBuild] = await Promise.all([
    readFile(path.join(projectRoot, 'package.json'), 'utf8'),
    readFile(path.join(projectRoot, 'package-lock.json'), 'utf8'),
    readFile(path.join(projectRoot, 'src-tauri/Cargo.toml'), 'utf8'),
    readFile(path.join(projectRoot, 'src-tauri/tauri.conf.json'), 'utf8'),
    readFile(path.join(projectRoot, 'src/index.html'), 'utf8'),
    readFile(path.join(projectRoot, '../app/build.gradle.kts'), 'utf8'),
  ]);
  const packageJson = JSON.parse(packageJsonRaw);
  const packageLock = JSON.parse(packageLockRaw);
  const tauriConfig = JSON.parse(tauriConfigRaw);
  const cargoVersion = cargoToml.match(/^version = "([^"]+)"$/m)?.[1];
  const androidVersion = androidBuild.match(/versionName = "([^"]+)"/)?.[1];

  assert.equal(androidVersion, packageJson.version);
  assert.equal(packageLock.version, packageJson.version);
  assert.equal(packageLock.packages[''].version, packageJson.version);
  assert.equal(cargoVersion, packageJson.version);
  assert.equal(tauriConfig.version, packageJson.version);
  assert.match(html, new RegExp(`index\\.css\\?v=${packageJson.version.replaceAll('.', '\\.')}`));
  assert.match(html, new RegExp(`index\\.js\\?v=${packageJson.version.replaceAll('.', '\\.')}`));

  for (const [name, metadata] of Object.entries(packageLock.packages)) {
    if (!name.startsWith('node_modules/')) continue;
    assert.notEqual(metadata.version, packageJson.version, `${name} inherited the app version`);
    if (metadata.resolved?.startsWith('https://registry.npmjs.org/')) {
      assert.match(metadata.resolved, new RegExp(`-${metadata.version.replaceAll('.', '\\.')}\\.tgz$`));
    }
  }
});

test('release sources contain no known corruption or machine-local paths', async () => {
  const contents = await Promise.all(
    sourceFiles.map(relativePath => readFile(path.join(projectRoot, relativePath), 'utf8')),
  );
  const source = contents.join('\n');

  assert.doesNotMatch(source, /Рќ|РµР|СЃС|Ã|Ð/);
  assert.doesNotMatch(source, /taskkill|netsh|ENABLE_DEPRECATED|api\.qrserver|flagcdn/i);
  assert.doesNotMatch(source, /[A-Z]:\\(?:Users|n8n)\\/i);
});

test('packaged frontend contains no backup files', async () => {
  const entries = await readdir(path.join(projectRoot, 'src'), { recursive: true });
  assert.equal(entries.some(entry => entry.toLowerCase().endsWith('.bak')), false);
});

test('encrypted settings recover from a validated backup and cannot be overwritten after load failure', async () => {
  const [frontend, main] = await Promise.all([
    readFile(path.join(projectRoot, 'src/index.js'), 'utf8'),
    readFile(path.join(projectRoot, 'src-tauri/src/main.rs'), 'utf8'),
  ]);

  assert.match(main, /fn read_protected_settings\(path: &Path\)/);
  assert.match(main, /settings\.dat\.bak/);
  assert.match(main, /read_protected_settings\(&backup_path\)/);
  assert.match(main, /write_atomic\(&protected_path, &protected\)/);
  assert.match(frontend, /S\.settingsLoaded = true/);
  assert.match(frontend, /if \(!S\.settingsLoaded\) throw new Error\(t\('settingsUnavailable'\)\)/);
  assert.match(frontend, /if \(!settingsLoaded\) showMessage\('settingsLoadError'\)/);
});

test('renderer uses only in-app dialogs and one automatic clipboard import action', async () => {
  const frontend = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const html = await readFile(path.join(projectRoot, 'src/index.html'), 'utf8');

  assert.doesNotMatch(frontend, /\b(?:alert|confirm|prompt)\s*\(/);
  assert.match(frontend, /classifyClipboardImport\(text\)/);
  assert.equal((html.match(/id="btn-clipboard-import"/g) || []).length, 1);
  assert.doesNotMatch(html, /btn-subscription-import/);
  assert.match(html, /id="overlay-message"/);
});

test('global Tauri access is constrained by a minimal capability allowlist', async () => {
  const [configRaw, capabilitiesRaw] = await Promise.all([
    readFile(path.join(projectRoot, 'src-tauri/tauri.conf.json'), 'utf8'),
    readFile(path.join(projectRoot, 'src-tauri/capabilities/default.json'), 'utf8'),
  ]);
  const config = JSON.parse(configRaw);
  const capabilities = JSON.parse(capabilitiesRaw);

  assert.equal(config.app.withGlobalTauri, true);
  assert.doesNotMatch(capabilitiesRaw, /core:default|core:window:default|notification:default/);
  assert.deepEqual(capabilities.windows, ['main']);
  assert.deepEqual(
    [...capabilities.permissions].sort(),
    [
      'core:event:allow-listen',
      'core:event:allow-unlisten',
      'core:window:allow-close',
      'core:window:allow-minimize',
      'core:window:allow-set-focus',
      'core:window:allow-show',
      'core:window:allow-start-dragging',
      'core:window:allow-unminimize',
      'notification:allow-is-permission-granted',
      'notification:allow-notify',
      'notification:allow-request-permission',
    ].sort(),
  );
});

test('profile navigation separates the group view and reuses one add dialog', async () => {
  const frontend = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const html = await readFile(path.join(projectRoot, 'src/index.html'), 'utf8');

  assert.match(html, /id="profiles-add-btn"/);
  assert.match(html, /id="profiles-back-btn"/);
  assert.match(html, /class="power-empty-label"/);
  assert.match(frontend, /let profilesViewGroup = null/);
  assert.match(frontend, /profilesViewGroup = groupName;\s*renderProfiles\(\)/);
  assert.match(frontend, /if \(isGroupView\) \{[\s\S]*groups\[profilesViewGroup\]\.forEach/);
  assert.match(frontend, /powerBtn\.classList\.toggle\('empty', !p\)/);
  assert.match(frontend, /if \(S\.profiles\.length\) void toggleVpn\(\);\s*else openAddProfile\(\)/);
  assert.equal((frontend.match(/function openAddProfile\(\)/g) || []).length, 1);
});

test('settings expose only the supported controls and track real changes', async () => {
  const frontend = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const html = await readFile(path.join(projectRoot, 'src/index.html'), 'utf8');
  const css = await readFile(path.join(projectRoot, 'src/index.css'), 'utf8');

  assert.doesNotMatch(html, /id="btn-connection-check"/);
  assert.doesNotMatch(html, /id="s-auto-server"/);
  assert.doesNotMatch(html, /id="s-update-channel"/);
  assert.match(html, /id="btn-open-tunneling"/);
  assert.match(html, /id="settings-tunneling-page"/);
  assert.match(html, /id="close-tunneling"/);
  assert.match(html, /data-i18n="routingSection"/);
  assert.match(html, /id="s-resume-on-boot"/);
  assert.match(html, /id="s-kill-switch"/);
  assert.match(html, /id="s-lan"/);
  assert.match(html, /id="s-quic"/);
  assert.match(html, /id="s-mtu"/);
  assert.match(html, /id="btn-language"/);
  assert.match(html, /id="btn-check-update"/);
  assert.match(frontend, /function settingsInputsSnapshot\(\)/);
  assert.match(frontend, /function updateSettingsSaveState\(\)/);
  assert.match(frontend, /settingsOpenSnapshot !== settingsInputsSnapshot\(\)/);
  assert.match(frontend, /button\.disabled = !changed/);
  assert.match(html, /id="app-tooltip"/);
  assert.doesNotMatch(html, /id="s-network-auto-protect"/);
  assert.match(html, /class="settings-drag-bar" data-tauri-drag-region/);
  assert.match(frontend, /closest\('\.top-bar, \.settings-drag-bar, \.dialog-settings \.drawer-head'\)/);
  assert.match(css, /\.settings-body\s*\{[\s\S]*?overflow-x:\s*hidden/);
  assert.match(css, /\.s-title\{[^}]*color:#55dca7/);
});

test('desktop shell cannot be shifted sideways by a touchpad gesture', async () => {
  const frontend = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const css = await readFile(path.join(projectRoot, 'src/index.css'), 'utf8');

  assert.match(css, /html,body\{[^}]*overflow:hidden;[^}]*overscroll-behavior-x:none/);
  assert.match(css, /#app\{[^}]*overflow:hidden;[^}]*overscroll-behavior-x:none/);
  assert.match(css, /\.drawer-list\{[^}]*overflow-x:hidden;[^}]*overflow-y:scroll/);
  assert.match(css, /\.running-apps-list\{[\s\S]*?overflow-x:hidden;[\s\S]*?overflow-y:auto/);
  assert.match(frontend, /Math\.abs\(event\.deltaX\) > Math\.abs\(event\.deltaY\)/);
  assert.match(frontend, /\{ capture: true, passive: false \}/);
});

test('tray actions reuse the WebUI connection and profile transactions', async () => {
  const frontend = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const main = await readFile(path.join(projectRoot, 'src-tauri/src/main.rs'), 'utf8');
  const tray = await readFile(path.join(projectRoot, 'src-tauri/src/tray_menu.rs'), 'utf8');
  const handlerStart = frontend.indexOf('async function handleTrayCommand(command)');
  const handlerEnd = frontend.indexOf('async function revealMainWindow()', handlerStart);

  assert.notEqual(handlerStart, -1);
  assert.notEqual(handlerEnd, -1);
  const handler = frontend.slice(handlerStart, handlerEnd);
  assert.match(frontend, /eventApi\.listen\('warpy:\/\/tray-command'/);
  assert.match(handler, /await toggleVpn\(\)/);
  assert.match(handler, /await selectProfile\(index, \{ closeProfiles: false \}\)/);
  assert.doesNotMatch(handler, /invoke\('(start_vpn|stop_vpn|switch_vpn_outbound)'/);
  assert.match(frontend, /function syncUI\(\)[\s\S]*scheduleTrayMenuUpdate\(\)/);
  assert.match(frontend, /lastTrayMenuSignature/);
  assert.match(frontend, /buildTrayProfileSnapshots\(S\.profiles, language, getProfileDisplay\)/);
  assert.match(main, /TrayIconBuilder::with_id\(tray_menu::TRAY_ID\)/);
  assert.match(main, /show_menu_on_left_click\(false\)/);
  assert.match(main, /update_tray_menu,/);
  assert.match(main, /app\.emit\(tray_menu::TRAY_COMMAND_EVENT, command\)/);
  assert.match(tray, /const MAX_TRAY_PROFILES: usize = 2000/);
  assert.match(tray, /command_from_menu_id/);
  assert.match(tray, /const GROUP_PAGE_SIZE: usize = 14/);
  assert.match(tray, /"profiles"[\s\S]*prepared\.profiles_label/);
  assert.match(tray, /IconMenuItem::with_id/);
  assert.match(tray, /BaseDirectory::Resource/);
});

test('sleep, unlock and network changes use one bounded service recovery path', async () => {
  const frontend = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const service = await readFile(path.join(projectRoot, 'src-tauri/src/vpn_service.rs'), 'utf8');
  const engine = await readFile(path.join(projectRoot, 'src-tauri/src/vpn_engine.rs'), 'utf8');
  const connectivity = await readFile(
    path.join(projectRoot, 'src-tauri/src/windows_connectivity.rs'),
    'utf8',
  );
  const resumeStart = frontend.indexOf('async function verifyConnectionAfterResume()');
  const resumeEnd = frontend.indexOf('/*', resumeStart);

  assert.notEqual(resumeStart, -1);
  assert.notEqual(resumeEnd, -1);
  const resumeSync = frontend.slice(resumeStart, resumeEnd);
  assert.match(resumeSync, /await checkStatus\(\)/);
  assert.doesNotMatch(resumeSync, /startVpn\(|stopVpn\(|verifyVpnTraffic\(/);
  assert.match(frontend, /\['connected', 'connecting', 'error'\]\.includes\(S\.status\)/);
  assert.match(service, /ServiceControlAccept::POWER_EVENT/);
  assert.match(service, /ServiceControlAccept::SESSION_CHANGE/);
  assert.match(service, /PowerEventParam::ResumeAutomatic/);
  assert.match(service, /SessionChangeReason::SessionUnlock/);
  assert.match(service, /NetworkChangeSubscription::register/);
  assert.match(connectivity, /NotifyIpInterfaceChange/);
  assert.match(connectivity, /CancelMibChangeNotify2/);
  assert.match(connectivity, /MAX_SETTLE_DELAY/);
  assert.doesNotMatch(connectivity, /wmi|ManagementObject|adapter.*name/i);
  const recoveryStart = engine.indexOf('pub(crate) fn handle_connectivity_event');
  const recoveryEnd = engine.indexOf('\n    fn start_locked(', recoveryStart);
  assert.notEqual(recoveryStart, -1);
  assert.notEqual(recoveryEnd, -1);
  const connectivityRecovery = engine.slice(recoveryStart, recoveryEnd);
  assert.match(connectivityRecovery, /verify_tunnel\(\)\.is_ok\(\)/);
  assert.match(connectivityRecovery, /self\.request_recovery\(\)/);
  assert.ok(
    connectivityRecovery.indexOf('self.lifecycle.lock()')
      < connectivityRecovery.indexOf('verify_tunnel().is_ok()'),
  );
});

test('system notifications are significant, private and UI-owned', async () => {
  const frontend = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const policy = await readFile(
    path.join(projectRoot, 'src/significant-notifications.js'),
    'utf8',
  );
  const main = await readFile(path.join(projectRoot, 'src-tauri/src/main.rs'), 'utf8');
  const capability = await readFile(
    path.join(projectRoot, 'src-tauri/capabilities/default.json'),
    'utf8',
  );

  assert.match(main, /tauri_plugin_notification::init\(\)/);
  assert.match(capability, /"notification:allow-is-permission-granted"/);
  assert.match(capability, /"notification:allow-request-permission"/);
  assert.match(capability, /"notification:allow-notify"/);
  assert.match(frontend, /if \(isWindowVisible \|\| !S\.systemNotificationsReady\) return/);
  assert.match(frontend, /initializeSystemNotifications\(!autostartLaunch && isWindowVisible\)/);
  assert.match(frontend, /notificationVpnRestoredTitle/);
  assert.match(frontend, /notificationVpnFailedTitle/);
  assert.doesNotMatch(policy, /server|address|profile|credential/i);
  assert.doesNotMatch(frontend, /sendSignificantNotification\([^\n]+getProfileDisplay/);
});

test('diagnostics export crosses IPC only after service-side redaction', async () => {
  const frontend = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const diagnostics = await readFile(
    path.join(projectRoot, 'src-tauri/src/diagnostics.rs'),
    'utf8',
  );
  const main = await readFile(path.join(projectRoot, 'src-tauri/src/main.rs'), 'utf8');
  const ipc = await readFile(path.join(projectRoot, 'src-tauri/src/vpn_ipc.rs'), 'utf8');
  const service = await readFile(path.join(projectRoot, 'src-tauri/src/vpn_service.rs'), 'utf8');
  const summaryStart = frontend.indexOf('function diagnosticsSettingsSummary()');
  const summaryEnd = frontend.indexOf('async function exportDiagnostics()', summaryStart);

  assert.notEqual(summaryStart, -1);
  assert.notEqual(summaryEnd, -1);
  const summary = frontend.slice(summaryStart, summaryEnd);
  assert.match(summary, /profileCount: S\.profiles\.length/);
  assert.match(summary, /appRuleCount: S\.appsList\.length/);
  assert.match(summary, /siteRuleCount: S\.sitesList\.length/);
  assert.doesNotMatch(summary, /profiles:\s*S\.profiles/);
  assert.doesNotMatch(summary, /subscriptions:\s*S\.subscriptions/);
  assert.doesNotMatch(summary, /appsList:\s*S\.appsList/);
  assert.doesNotMatch(summary, /sitesList:\s*S\.sitesList/);
  assert.match(frontend, /invoke\('export_diagnostics'/);
  assert.match(ipc, /VpnRequest::Diagnostics/);
  assert.match(service, /collect_sanitized_log\(&paths\.app_dir\.join\("service\.log"\), false\)/);
  assert.match(service, /collect_sanitized_log\(&paths\.app_dir\.join\("sing-box\.log"\), true\)/);
  assert.match(main, /service_call\(vpn_ipc::VpnRequest::Diagnostics\)/);
  assert.match(diagnostics, /"summary\.json"/);
  assert.match(diagnostics, /"core-errors\.log"/);
  assert.doesNotMatch(diagnostics, /settings\.dat|settings\.json|health\.json/);
});

test('autostart does not require an external route before Warpy connects', async () => {
  const source = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const start = source.indexOf('async function startVpnAfterSystemBoot()');
  const end = source.indexOf('async function startVpn(', start);
  assert.notEqual(start, -1);
  assert.notEqual(end, -1);

  const autostart = source.slice(start, end);
  assert.match(autostart, /retryDelays = \[5000, 5000, 10000, 15000\]/);
  assert.doesNotMatch(autostart, /fetch\(|verifyVpnTraffic|speed\.cloudflare\.com/);
});

test('manual VPN startup has one readiness authority and always cleans up failures', async () => {
  const frontend = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const engine = await readFile(path.join(projectRoot, 'src-tauri/src/vpn_engine.rs'), 'utf8');
  const start = frontend.indexOf('async function startVpn(');
  const end = frontend.indexOf('async function stopVpn(', start);
  const nativeStart = engine.indexOf('pub(crate) fn start(');
  const nativeEnd = engine.indexOf('pub(crate) fn switch_outbound', nativeStart);

  assert.notEqual(start, -1);
  assert.notEqual(end, -1);
  assert.notEqual(nativeStart, -1);
  assert.notEqual(nativeEnd, -1);

  const rendererStart = frontend.slice(start, end);
  const engineStart = engine.slice(nativeStart, nativeEnd);
  assert.doesNotMatch(rendererStart, /verifyVpnTraffic\(/);
  assert.match(rendererStart, /await invoke\('stop_vpn'\)/);
  assert.match(rendererStart, /const serviceSnapshot = await getVpnRuntimeSnapshot\(\)/);
  assert.match(rendererStart, /await applyServiceConnectionSnapshot\(serviceSnapshot/);
  assert.doesNotMatch(rendererStart, /S\.status\s*=(?!=)/);
  assert.match(engineStart, /if let Err\(error\) = result \{\s*let cleanup_error = self\.stop_core\(\)\.err\(\)/);
});

test('tunnel readiness probe stays on TCP and cannot race browser QUIC fallback', async () => {
  const probe = await readFile(path.join(projectRoot, 'src-tauri/src/vpn_probe.rs'), 'utf8');
  const engine = await readFile(path.join(projectRoot, 'src-tauri/src/vpn_engine.rs'), 'utf8');

  assert.match(probe, /INTERNET_DEFAULT_HTTPS_PORT/);
  assert.match(probe, /WINHTTP_FLAG_SECURE/);
  assert.match(engine, /let probe_result = self\.verify_started_outbound\(quick_probe\)/);
  assert.match(engine, /control\.probe_outbound\(&outbound\)/);
  assert.match(engine, /cleanup_stale_tun_default_route\(\)/);
  assert.match(engine, /CONNECTIVITY_EVENT_STARTUP_GRACE_MS/);
  assert.match(engine, /now_ms\(\)\.saturating_sub\(started_at\)/);
  assert.match(engine, /const TUN_DNS_SERVER: &str = "1\.1\.1\.1"/);
  assert.doesNotMatch(engine, /name_server: Vec<u16> = "172\.29\.99\.2/);
});

test('renderer restoration trusts the service and never tears down a healthy tunnel', async () => {
  const source = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const restoreStart = source.indexOf('async function restoreBackendState()');
  const restoreEnd = source.indexOf('/* ── Timers', restoreStart);
  const statusStart = source.indexOf('async function checkStatus()');
  const statusEnd = source.indexOf('function delay(', statusStart);
  assert.notEqual(restoreStart, -1);
  assert.notEqual(restoreEnd, -1);
  assert.notEqual(statusStart, -1);
  assert.notEqual(statusEnd, -1);

  const restore = source.slice(restoreStart, restoreEnd);
  const statusCheck = source.slice(statusStart, statusEnd);
  assert.match(restore, /const snapshot = await getVpnRuntimeSnapshot\(\)/);
  assert.match(restore, /await applyServiceConnectionSnapshot\(snapshot\)/);
  assert.doesNotMatch(restore, /S\.status\s*=(?!=)/);
  assert.doesNotMatch(restore, /verifyVpnTraffic|stop_vpn/);
  assert.match(statusCheck, /await applyServiceConnectionSnapshot\(snapshot\)/);
  assert.doesNotMatch(statusCheck, /S\.status\s*=(?!=)/);
  assert.match(statusCheck, /S\.statusCheckFailures >= 3/);
  assert.match(statusCheck, /serviceUnavailable/);
});

test('Android UI delegates VPN commands and service status to their single owners', async () => {
  const activity = await readFile(
    path.join(projectRoot, '../app/src/main/java/com/warpy/app/MainActivity.kt'),
    'utf8',
  );
  const viewModel = await readFile(
    path.join(projectRoot, '../app/src/main/java/com/warpy/app/MainViewModel.kt'),
    'utf8',
  );
  const coordinator = await readFile(
    path.join(projectRoot, '../app/src/main/java/com/warpy/app/vpn/VpnCommandCoordinator.kt'),
    'utf8',
  );

  assert.doesNotMatch(activity, /VpnStartHelper\.start\(|WarpyService\.ACTION_STOP/);
  assert.match(activity, /viewModel\.startVpn\(\)/);
  assert.match(activity, /viewModel\.stopVpn\(\)/);
  assert.match(viewModel, /private val vpnCommands = VpnCommandCoordinator/);
  assert.match(coordinator, /VpnStartHelper\.start\(/);
  assert.match(coordinator, /WarpyService\.ACTION_STOP/);
  assert.equal(
    [...viewModel.matchAll(/status\s*=\s*VpnStatus\./g)].length,
    4,
    'Only the four service-status reducers may assign a final VPN status',
  );
});

test('Android connection glow follows one confirmed service transition only once', async () => {
  const androidUi = await readFile(path.join(projectRoot, '..', 'app', 'src', 'main', 'java', 'com', 'warpy', 'app', 'MainActivity.kt'), 'utf8');

  assert.match(androidUi, /VpnStatus\.Connecting -> borderGlowArmed = !hasSecondarySurface/);
  assert.match(androidUi, /if \(borderGlowArmed && !hasSecondarySurface\) \{\s*borderGlowEventId \+= 1/);
  assert.match(androidUi, /consumedEventId = consumedBorderGlowEventId/);
  assert.match(androidUi, /onEventConsumed = \{ consumedBorderGlowEventId = it \}/);
  assert.match(androidUi, /if \(connected && eventId > consumedEventId\) \{\s*onEventConsumed\(eventId\)/);
});

test('desktop background status snapshots do not interrupt the connection glow', async () => {
  const renderer = await readFile(new URL('../src/index.js', import.meta.url), 'utf8');

  assert.match(renderer, /\{ showConnectedAlert, errorText = '' \} = \{\}/);
  assert.match(renderer, /if \(next\.status !== 'connected'\)/);
  assert.match(renderer, /typeof showConnectedAlert === 'boolean'/);
  assert.doesNotMatch(renderer, /next\.status === 'connected' && showConnectedAlert/);
});

test('autostart explicitly keeps the main window hidden', async () => {
  const source = await readFile(path.join(projectRoot, 'src-tauri/src/main.rs'), 'utf8');
  assert.match(
    source,
    /if autostart_launch \{\s*let _ = window\.hide\(\);\s*\} else \{/,
  );
});

test('deleting an inactive profile preserves the active tunnel', async () => {
  const source = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const start = source.indexOf("if (e.target.classList.contains('p-del')");
  const end = source.indexOf('await selectProfile(i)', start);
  assert.notEqual(start, -1);
  assert.notEqual(end, -1);

  const deletion = source.slice(start, end);
  assert.match(deletion, /restartRuntime: deletingActive/);
  assert.match(deletion, /if \(!deletingActive && deletedRuntimeIndex >= 0\)/);
  assert.match(deletion, /forgetRuntimeOutbounds/);
  assert.doesNotMatch(deletion, /if \(wasRunning\) await stopVpn\(\)/);

  const cleanupStart = source.indexOf('async function forgetRuntimeOutbounds');
  const cleanupEnd = source.indexOf('async function applySubscriptionUpdate', cleanupStart);
  const cleanup = source.slice(cleanupStart, cleanupEnd);
  assert.match(cleanup, /invoke\('forget_vpn_outbound'/);
  assert.match(cleanup, /if \(!await stopVpn\(\)\)/);
  assert.match(cleanup, /await startVpn\(\{ preserveActiveProfile: true \}\)/);
});

test('profile import asks before switching an active tunnel and auto-connects while offline', async () => {
  const source = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const start = source.indexOf('async function importProfileText(text)');
  const end = source.indexOf('\nlet loadedProcesses', start);
  assert.notEqual(start, -1);
  assert.notEqual(end, -1);
  const profileImport = source.slice(start, end);

  assert.match(profileImport, /await showConfirm\('connectImportedProfile'\)/);
  assert.match(profileImport, /await selectProfile\(importedIndex/);
  assert.match(profileImport, /await startVpn\(\{ preserveActiveProfile: true \}\)/);
});

test('manual profile choice is persisted without hidden automatic switching', async () => {
  const source = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const selectStart = source.indexOf('async function selectProfile(');
  const selectEnd = source.indexOf('function createProfileItemEl(', selectStart);

  assert.notEqual(selectStart, -1);
  assert.notEqual(selectEnd, -1);
  const selection = source.slice(selectStart, selectEnd);
  assert.match(selection, /setPreferredProfile\(index\)/);
  assert.match(selection, /if \(stoppedForRestart && !await startVpn\(\)\) return false/);
  assert.match(source, /async function startVpn[\s\S]*?return true;[\s\S]*?return false;/);
  assert.match(source, /preferredProfileKey: S\.preferredProfileKey/);
  assert.doesNotMatch(source, /set_warpy_auto|refreshVpnHealth|autoSwitchNotificationEvent/);
});

test('subscription updates are persisted with the current settings schema', async () => {
  const source = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const subscriptionSource = await readFile(path.join(projectRoot, 'src/subscription.js'), 'utf8');
  const yamlSource = await readFile(path.join(projectRoot, 'src/vendor/js-yaml.mjs'), 'utf8');
  const yamlLicense = await readFile(path.join(projectRoot, 'src/vendor/js-yaml.LICENSE.txt'), 'utf8');
  const packageJson = JSON.parse(await readFile(path.join(projectRoot, 'package.json'), 'utf8'));

  assert.match(source, /schemaVersion: 8/);
  assert.match(source, /subscriptions: S\.subscriptions\.map/);
  assert.match(source, /lastCheckedAt: subscription\.lastCheckedAt/);
  assert.match(source, /lastStatus: subscription\.lastStatus/);
  assert.match(source, /parseSubscriptionPayload\(response\?\.body\)/);
  assert.match(source, /await applySubscriptionUpdate\(subscription, parsed\.profiles, legacyGroupName\)/);
  assert.match(source, /className = 'p-share group-share'/);
  assert.match(source, /className = 'p-del group-delete'/);
  assert.match(source, /await commitProfileState\(snapshot/);
  assert.match(source, /async function refreshSubscription\(subscriptionId,/);
  assert.match(source, /subscriptionProfilesEqual\(currentProfiles, parsed\.profiles\)/);
  assert.match(source, /subscription\.lastStatus = 'unchanged';\s*await save\(\);/);
  assert.match(subscriptionSource, /format = 'sing-box-json'/);
  assert.match(subscriptionSource, /format = 'base64-sing-box-json'/);
  assert.match(subscriptionSource, /!SING_BOX_TRANSPORTS\.has\(transportType\)/);
  assert.match(subscriptionSource, /format = 'clash-yaml'/);
  assert.match(subscriptionSource, /format = 'base64-clash-yaml'/);
  assert.match(subscriptionSource, /schema: JSON_SCHEMA/);
  assert.match(subscriptionSource, /maxDepth: 20/);
  assert.match(subscriptionSource, /maxTotalMergeKeys: 0/);
  assert.match(subscriptionSource, /propertyValue\(config, \['proxies'\]\)/);
  assert.equal(packageJson.dependencies['js-yaml'], '4.3.1');
  assert.match(yamlSource, /Vendored from js-yaml 4\.3\.1/);
  assert.match(yamlLicense, /The MIT License/);
});

test('desktop updates are signed, user-confirmed and constrained to release channels', async () => {
  const frontend = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const main = await readFile(path.join(projectRoot, 'src-tauri/src/main.rs'), 'utf8');
  const updates = await readFile(path.join(projectRoot, 'src-tauri/src/updates.rs'), 'utf8');
  const config = JSON.parse(
    await readFile(path.join(projectRoot, 'src-tauri/tauri.conf.json'), 'utf8'),
  );
  const updaterConfig = JSON.parse(
    await readFile(path.join(projectRoot, 'src-tauri/tauri.updater.conf.json'), 'utf8'),
  );
  const releaseWorkflow = await readFile(
    path.join(projectRoot, '../.github/workflows/release.yml'),
    'utf8',
  );
  const signatureGate = await readFile(
    path.join(projectRoot, 'scripts/verify-updater-signature.ps1'),
    'utf8',
  );
  const buildRelease = await readFile(
    path.join(projectRoot, 'scripts/build-release.ps1'),
    'utf8',
  );
  const prepareReleaseAssets = await readFile(
    path.join(projectRoot, 'scripts/prepare-release-assets.ps1'),
    'utf8',
  );

  assert.match(main, /tauri_plugin_updater::Builder::new\(\)\.build\(\)/);
  assert.match(main, /updates::check_for_update/);
  assert.match(main, /updates::install_update/);
  assert.equal(updaterConfig.bundle.createUpdaterArtifacts, true);
  assert.match(config.plugins.updater.pubkey, /^[A-Za-z0-9+/=]+$/);
  assert.match(updates, /release\.draft \|\| release\.prerelease/);
  assert.match(updates, /MAX_RELEASE_FEED_BYTES/);
  assert.match(updates, /RELEASE_DOWNLOAD_PREFIX/);
  assert.match(updates, /is_trusted_feed_url/);
  assert.match(updates, /release\.version == expected/);
  assert.match(updates, /download_and_install/);
  assert.doesNotMatch(updates, /UpdateChannel|latest-beta\.json|endpoint:\s*String/);
  assert.match(frontend, /await showConfirm\([\s\S]*updateRestartNotice/);
  assert.match(frontend, /invoke\('install_update'/);
  assert.doesNotMatch(frontend.slice(0, frontend.indexOf('function bindEvents()')), /check_for_update/);
  assert.match(releaseWorkflow, /TAURI_SIGNING_PRIVATE_KEY/);
  assert.match(releaseWorkflow, /needs:[\s\S]*android[\s\S]*windows/);
  assert.match(releaseWorkflow, /Warpy-Android\.apk/);
  assert.match(releaseWorkflow, /Warpy-Windows\.exe/);
  assert.match(releaseWorkflow, /Publish unified GitHub Release/);
  assert.match(signatureGate, /verify_updater_signature/);
  assert.match(buildRelease, /npm ci --ignore-scripts --dry-run --no-audit --no-fund/);
  assert.match(buildRelease, /npm test/);
  assert.match(prepareReleaseAssets, /Release version .* does not match package version/);
  assert.match(prepareReleaseAssets, /Warpy-Windows\.exe/);
  assert.doesNotMatch(prepareReleaseAssets, /\.sha256|release-manifest\.json/);
  assert.doesNotMatch(prepareReleaseAssets, /\bgit\b.*rev-parse/i);
});

test('failed first launch restores the previous signed installation locally', async () => {
  const frontend = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const main = await readFile(path.join(projectRoot, 'src-tauri/src/main.rs'), 'utf8');
  const updates = await readFile(path.join(projectRoot, 'src-tauri/src/updates.rs'), 'utf8');
  const hooks = await readFile(
    path.join(projectRoot, 'src-tauri/windows-installer-hooks.nsh'),
    'utf8',
  );

  assert.match(updates, /launch-health-.*\.ok/);
  assert.match(frontend, /invoke\('confirm_launch_health'\)/);
  assert.match(main, /--post-update-health-check/);
  assert.match(main, /--rollback-shutdown/);
  assert.match(hooks, /\.warpy-rollback/);
  assert.match(hooks, /settings\.dat/);
  assert.match(hooks, /IntCmp \$WarpyHealthWait 30/);
  assert.match(hooks, /warpy_restore_binary/);
  assert.match(hooks, /--install-service/);
  assert.doesNotMatch(hooks, /https?:\/\//);
});

test('removed automatic network policies are migrated out and cannot change connectivity', async () => {
  const frontend = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const html = await readFile(path.join(projectRoot, 'src/index.html'), 'utf8');
  const service = await readFile(path.join(projectRoot, 'src-tauri/src/vpn_service.rs'), 'utf8');
  const context = await readFile(path.join(projectRoot, 'src-tauri/src/network_context.rs'), 'utf8');

  assert.doesNotMatch(html, /s-network-auto-protect|network-auto-protect-status/);
  assert.doesNotMatch(frontend, /s-network-auto-protect/);
  assert.match(frontend, /'networkAutoProtect' in d/);
  assert.doesNotMatch(frontend, /applyNetworkAutoProtection|networkProtectionDecision|set_warpy_auto/);
  assert.match(service, /NetworkChangeSubscription::register/);
  assert.match(service, /refresh_network_context/);
  assert.doesNotMatch(context, /ssid|network.*name|GetName|GetNetworkId|wmi/i);
});

test('automatic subscription refresh is daily, deferred and never restarts an active VPN', async () => {
  const source = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const subscriptionSource = await readFile(path.join(projectRoot, 'src/subscription.js'), 'utf8');
  const start = source.indexOf('async function runAutomaticSubscriptionRefresh()');
  const end = source.indexOf('function captureProfileState()', start);
  const refreshStart = source.indexOf('async function refreshSubscription(');
  const refreshEnd = source.indexOf('function scheduleAutomaticSubscriptionRefresh(', refreshStart);

  assert.notEqual(start, -1);
  assert.notEqual(end, -1);
  assert.notEqual(refreshStart, -1);
  assert.notEqual(refreshEnd, -1);
  const scheduler = source.slice(start, end);
  const refresh = source.slice(refreshStart, refreshEnd);
  assert.match(source, /SUBSCRIPTION_AUTO_POLL_INTERVAL_MS = 5 \* 60 \* 1000/);
  assert.match(subscriptionSource, /AUTO_SUBSCRIPTION_REFRESH_INTERVAL_MS = 24 \* 60 \* 60 \* 1000/);
  assert.match(scheduler, /S\.status !== 'stopped'/);
  assert.match(scheduler, /subscriptionRefreshDue\(subscription\)/);
  assert.match(scheduler, /refreshSubscription\(subscriptionId, \{ automatic: true \}\)/);
  assert.match(refresh, /if \(automatic && S\.status !== 'stopped'\) return;/);
  assert.doesNotMatch(scheduler, /stopVpn\(|startVpn\(/);
  assert.match(source, /if \(isWindowVisible\) \{\s*scheduleAutomaticSubscriptionRefresh\(\);/);
});

test('a competing VPN is rejected before Warpy changes Windows routes', async () => {
  const frontend = await readFile(path.join(projectRoot, 'src/index.js'), 'utf8');
  const engine = await readFile(path.join(projectRoot, 'src-tauri/src/vpn_engine.rs'), 'utf8');
  const service = await readFile(path.join(projectRoot, 'src-tauri/src/vpn_service.rs'), 'utf8');
  const killSwitch = await readFile(
    path.join(projectRoot, 'src-tauri/src/vpn_kill_switch.rs'),
    'utf8',
  );

  assert.match(killSwitch, /pub\(crate\) const COMPETING_VPN_ERROR/);
  assert.match(killSwitch, /pub\(crate\) fn competing_vpn_active\(\)/);
  assert.match(
    engine,
    /if competing_vpn_active\(\)\? \{\s*return Err\(COMPETING_VPN_ERROR\.to_string\(\)\);/,
  );
  assert.match(service, /"competingVpn": competing_vpn/);
  assert.match(frontend, /if \(snapshot\.competingVpn\) throw new Error\(t\('otherVpnActive'\)\)/);
  assert.match(frontend, /function showSpeedtestFailure\(message\)/);
  assert.match(
    frontend,
    /\$\('speedtest-live-val'\)\.replaceChildren\(document\.createTextNode\('—'\)\)/,
  );
});
