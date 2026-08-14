#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod diagnostics;
#[cfg(windows)]
mod network_context;
#[cfg(windows)]
mod subscription;
mod tray_menu;
mod updates;
mod vpn_engine;
#[cfg(windows)]
mod vpn_ipc;
#[cfg(windows)]
mod vpn_kill_switch;
#[cfg(windows)]
mod vpn_probe;
mod vpn_recovery;
#[cfg(windows)]
mod vpn_selector;
#[cfg(windows)]
mod vpn_service;
#[cfg(windows)]
mod windows_autostart;
#[cfg(windows)]
mod windows_connectivity;

#[cfg(not(windows))]
use crate::vpn_engine::read_vpn_network_stats;
use crate::vpn_engine::{rotate_log, write_atomic, VpnNetworkStats};
use diagnostics::{
    collect_sanitized_log, write_bundle, DiagnosticsManifest, DiagnosticsSettingsSummary,
    ServiceDiagnostics,
};
#[cfg(windows)]
use std::path::Path;
use std::{
    collections::BTreeSet,
    fs::{self, OpenOptions},
    io::Write,
    path::PathBuf,
    process::Command,
    sync::Mutex,
    time::{SystemTime, UNIX_EPOCH},
};
use sysinfo::{ProcessExt, System, SystemExt};
use tauri::{Emitter, Manager, State};

const CREATE_NO_WINDOW: u32 = 0x08000000;
const MAX_VPN_CONFIG_BYTES: usize = 768 * 1024;
const MAX_SETTINGS_BYTES: usize = 8 * 1024 * 1024;
const MAX_PROTECTED_SETTINGS_BYTES: usize = MAX_SETTINGS_BYTES + 64 * 1024;
const MAX_LOG_MESSAGE_CHARS: usize = 2_048;
#[cfg(windows)]
static SERVICE_IPC_LOCK: Mutex<()> = Mutex::new(());

fn ensure_text_size(value: &str, max_bytes: usize, label: &str) -> Result<(), String> {
    if value.len() > max_bytes {
        return Err(format!("{label} exceeds the allowed size"));
    }
    Ok(())
}

fn validate_runtime_outbound(outbound: &str) -> Result<(), String> {
    if outbound.len() > 32 {
        return Err("Invalid runtime profile identifier".to_string());
    }
    let index = outbound
        .strip_prefix("profile-")
        .and_then(|value| value.parse::<u32>().ok())
        .filter(|value| *value > 0)
        .filter(|value| outbound == format!("profile-{value}"));
    if index.is_none() {
        return Err("Invalid runtime profile identifier".to_string());
    }
    Ok(())
}

struct AppState {
    settings_io: Mutex<()>,
    tray_menu_io: Mutex<()>,
    autostart_launch: bool,
    post_update_launch: bool,
}

fn app_data_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let path = app
        .path()
        .app_data_dir()
        .map_err(|error| error.to_string())?;
    fs::create_dir_all(&path).map_err(|error| error.to_string())?;
    Ok(path)
}

#[cfg(windows)]
fn protect_for_current_user(contents: &[u8]) -> Result<Vec<u8>, String> {
    use std::mem::zeroed;
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{
        CryptProtectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    let input = CRYPT_INTEGER_BLOB {
        cbData: contents.len() as u32,
        pbData: contents.as_ptr() as *mut u8,
    };
    let mut output: CRYPT_INTEGER_BLOB = unsafe { zeroed() };
    let result = unsafe {
        CryptProtectData(
            &input,
            std::ptr::null(),
            std::ptr::null(),
            std::ptr::null(),
            std::ptr::null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if result == 0 {
        return Err(format!(
            "Не удалось защитить настройки: {}",
            std::io::Error::last_os_error()
        ));
    }

    let protected =
        unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe {
        let _ = LocalFree(output.pbData as _);
    }
    Ok(protected)
}

#[cfg(windows)]
fn unprotect_for_current_user(contents: &[u8]) -> Result<Vec<u8>, String> {
    use std::mem::zeroed;
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{
        CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    let input = CRYPT_INTEGER_BLOB {
        cbData: contents.len() as u32,
        pbData: contents.as_ptr() as *mut u8,
    };
    let mut output: CRYPT_INTEGER_BLOB = unsafe { zeroed() };
    let result = unsafe {
        CryptUnprotectData(
            &input,
            std::ptr::null_mut(),
            std::ptr::null(),
            std::ptr::null(),
            std::ptr::null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if result == 0 {
        return Err(format!(
            "Не удалось прочитать защищённые настройки: {}",
            std::io::Error::last_os_error()
        ));
    }

    let plain =
        unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe {
        let _ = LocalFree(output.pbData as _);
    }
    Ok(plain)
}

#[cfg(windows)]
fn read_protected_settings(path: &Path) -> Result<String, String> {
    let size = fs::metadata(path).map_err(|error| error.to_string())?.len() as usize;
    if size > MAX_PROTECTED_SETTINGS_BYTES {
        return Err("Protected settings file is too large".to_string());
    }
    let protected = fs::read(path).map_err(|error| error.to_string())?;
    let plain = unprotect_for_current_user(&protected)?;
    if plain.len() > MAX_SETTINGS_BYTES {
        return Err("Settings file is too large".to_string());
    }
    let settings = String::from_utf8(plain).map_err(|error| error.to_string())?;
    serde_json::from_str::<serde_json::Value>(&settings)
        .map_err(|error| format!("Некорректные настройки: {error}"))?;
    Ok(settings)
}

#[tauri::command]
async fn get_vpn_status() -> String {
    #[cfg(windows)]
    {
        service_call_async(vpn_ipc::VpnRequest::Status)
            .await
            .and_then(|value| serde_json::from_value(value).map_err(|error| error.to_string()))
            .unwrap_or_else(|_| "Error".to_string())
    }

    #[cfg(not(windows))]
    {
        "Stopped".to_string()
    }
}

#[tauri::command]
async fn get_vpn_runtime_snapshot() -> Result<serde_json::Value, String> {
    #[cfg(windows)]
    {
        service_call_async(vpn_ipc::VpnRequest::RuntimeSnapshot).await
    }

    #[cfg(not(windows))]
    Ok(serde_json::json!({
        "status": "Stopped",
        "desiredRunning": false,
        "network": {
            "trust": "unknown",
            "internet": false,
            "generation": 0,
        },
        "competingVpn": false,
    }))
}

#[tauri::command]
async fn get_vpn_started_at() -> Option<u64> {
    #[cfg(windows)]
    {
        service_call_async(vpn_ipc::VpnRequest::StartedAt)
            .await
            .and_then(|value| serde_json::from_value(value).map_err(|error| error.to_string()))
            .unwrap_or(None)
    }

    #[cfg(not(windows))]
    {
        None
    }
}

#[tauri::command]
async fn get_vpn_network_stats() -> Result<VpnNetworkStats, String> {
    #[cfg(windows)]
    {
        service_call_async(vpn_ipc::VpnRequest::NetworkStats)
            .await
            .and_then(|value| serde_json::from_value(value).map_err(|error| error.to_string()))
    }

    #[cfg(not(windows))]
    {
        read_vpn_network_stats()
    }
}

#[tauri::command]
async fn get_kill_switch_status() -> String {
    #[cfg(windows)]
    {
        service_call_async(vpn_ipc::VpnRequest::KillSwitchStatus)
            .await
            .and_then(|value| serde_json::from_value(value).map_err(|error| error.to_string()))
            .unwrap_or_else(|error| format!("Error:{error}"))
    }

    #[cfg(not(windows))]
    {
        "Off".to_string()
    }
}

#[tauri::command]
async fn stop_vpn() -> Result<(), String> {
    #[cfg(windows)]
    {
        service_call_async(vpn_ipc::VpnRequest::Stop)
            .await
            .map(|_| ())
    }

    #[cfg(not(windows))]
    Ok(())
}

#[tauri::command]
async fn cancel_vpn_start() -> Result<(), String> {
    #[cfg(windows)]
    {
        let response = tauri::async_runtime::spawn_blocking(|| {
            vpn_ipc::call(&vpn_ipc::VpnRequest::CancelStart)
        })
            .await
            .map_err(|error| format!("VPN cancellation failed: {error}"))?;
        response??;
        Ok(())
    }

    #[cfg(not(windows))]
    Ok(())
}

#[tauri::command]
async fn start_vpn(config: String, kill_switch: bool) -> Result<(), String> {
    ensure_text_size(&config, MAX_VPN_CONFIG_BYTES, "VPN configuration")?;
    #[cfg(windows)]
    {
        service_call_async(vpn_ipc::VpnRequest::Start {
            config,
            kill_switch,
        })
        .await
        .map(|_| ())
    }

    #[cfg(not(windows))]
    Err("VPN поддерживается только в Windows".to_string())
}

#[tauri::command]
async fn forget_vpn_outbound(outbound: String) -> Result<(), String> {
    validate_runtime_outbound(&outbound)?;
    #[cfg(windows)]
    {
        service_call_async(vpn_ipc::VpnRequest::ForgetOutbound { outbound })
            .await
            .map(|_| ())
    }

    #[cfg(not(windows))]
    Ok(())
}

#[tauri::command]
async fn switch_vpn_outbound(outbound: String) -> Result<(), String> {
    validate_runtime_outbound(&outbound)?;
    #[cfg(windows)]
    {
        service_call_async(vpn_ipc::VpnRequest::SwitchOutbound { outbound })
            .await
            .map(|_| ())
    }

    #[cfg(not(windows))]
    Err("VPN поддерживается только в Windows".to_string())
}

#[tauri::command]
async fn set_resume_on_boot(enabled: bool) -> Result<(), String> {
    #[cfg(windows)]
    {
        service_call_async(vpn_ipc::VpnRequest::SetAutostart { enabled }).await?;
        remove_legacy_autostart_entry()
    }

    #[cfg(not(windows))]
    Ok(())
}

#[tauri::command]
fn update_tray_menu(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    snapshot: tray_menu::TrayMenuSnapshot,
) -> Result<(), String> {
    let _tray_menu_io = state
        .tray_menu_io
        .lock()
        .map_err(|_| "Меню Warpy недоступно".to_string())?;
    let rendered = tray_menu::build(&app, &snapshot)?;
    let tray = app
        .tray_by_id(tray_menu::TRAY_ID)
        .ok_or_else(|| "Значок Warpy в области уведомлений недоступен".to_string())?;
    tray.set_menu(Some(rendered.menu))
        .map_err(|error| error.to_string())?;
    tray.set_tooltip(Some(rendered.tooltip))
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn is_autostart_launch(state: State<'_, AppState>) -> bool {
    state.autostart_launch
}

#[tauri::command]
fn is_post_update_launch(state: State<'_, AppState>) -> bool {
    state.post_update_launch
}

#[tauri::command]
fn confirm_launch_health(app: tauri::AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    if !state.post_update_launch {
        return Err("launch health confirmation is not expected".to_string());
    }
    updates::confirm_launch_health(&app)
}

#[cfg(windows)]
fn remove_legacy_autostart_entry() -> Result<(), String> {
    use std::ptr;
    use windows_sys::Win32::{
        Foundation::ERROR_FILE_NOT_FOUND,
        System::Registry::{
            RegCloseKey, RegCreateKeyExW, RegDeleteValueW, HKEY, HKEY_CURRENT_USER, KEY_SET_VALUE,
            REG_OPTION_NON_VOLATILE,
        },
    };

    let key_path: Vec<u16> = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        .encode_utf16()
        .chain(Some(0))
        .collect();
    let value_name: Vec<u16> = "Warpy".encode_utf16().chain(Some(0)).collect();
    let mut key: HKEY = ptr::null_mut();
    let create_result = unsafe {
        RegCreateKeyExW(
            HKEY_CURRENT_USER,
            key_path.as_ptr(),
            0,
            ptr::null(),
            REG_OPTION_NON_VOLATILE,
            KEY_SET_VALUE,
            ptr::null(),
            &mut key,
            ptr::null_mut(),
        )
    };
    if create_result != 0 {
        return Err(std::io::Error::from_raw_os_error(create_result as i32).to_string());
    }

    let update_result = {
        let result = unsafe { RegDeleteValueW(key, value_name.as_ptr()) };
        if result == ERROR_FILE_NOT_FOUND {
            0
        } else {
            result
        }
    };
    unsafe {
        let _ = RegCloseKey(key);
    }
    if update_result != 0 {
        return Err(std::io::Error::from_raw_os_error(update_result as i32).to_string());
    }
    Ok(())
}

#[cfg(windows)]
fn service_call(request: vpn_ipc::VpnRequest) -> Result<serde_json::Value, String> {
    let _guard = SERVICE_IPC_LOCK
        .lock()
        .map_err(|_| "VPN service IPC lock is unavailable".to_string())?;
    if !matches!(&request, vpn_ipc::VpnRequest::AttachUi { .. }) {
        vpn_ipc::call(&vpn_ipc::VpnRequest::AttachUi {
            process_id: std::process::id(),
        })??;
    }
    vpn_ipc::call(&request)?
}

#[cfg(windows)]
async fn service_call_async(request: vpn_ipc::VpnRequest) -> Result<serde_json::Value, String> {
    tauri::async_runtime::spawn_blocking(move || service_call(request))
        .await
        .map_err(|error| format!("Сбой вызова службы VPN: {error}"))?
}

#[cfg(windows)]
#[tauri::command]
async fn fetch_subscription(url: String) -> Result<subscription::FetchedSubscription, String> {
    tauri::async_runtime::spawn_blocking(move || subscription::fetch(&url))
        .await
        .map_err(|error| format!("Сбой загрузки подписки: {error}"))?
}

#[tauri::command]
fn load_settings(app: tauri::AppHandle, state: State<'_, AppState>) -> Result<String, String> {
    let _settings_io = state
        .settings_io
        .lock()
        .map_err(|_| "Хранилище настроек недоступно".to_string())?;
    let app_dir = app_data_dir(&app)?;

    #[cfg(windows)]
    {
        let protected_path = app_dir.join("settings.dat");
        let backup_path = app_dir.join("settings.dat.bak");
        if protected_path.exists() {
            match read_protected_settings(&protected_path) {
                Ok(settings) => return Ok(settings),
                Err(primary_error) if backup_path.exists() => {
                    let settings = read_protected_settings(&backup_path).map_err(|backup_error| {
                        format!(
                            "Не удалось прочитать настройки и резервную копию: {primary_error}; {backup_error}"
                        )
                    })?;
                    let protected = fs::read(&backup_path).map_err(|error| error.to_string())?;
                    write_atomic(&protected_path, &protected)?;
                    return Ok(settings);
                }
                Err(error) => return Err(error),
            }
        }
        if backup_path.exists() {
            let settings = read_protected_settings(&backup_path)?;
            let protected = fs::read(&backup_path).map_err(|error| error.to_string())?;
            write_atomic(&protected_path, &protected)?;
            return Ok(settings);
        }

        let legacy_path = app_dir.join("settings.json");
        if legacy_path.exists() {
            let size = fs::metadata(&legacy_path)
                .map_err(|error| error.to_string())?
                .len() as usize;
            if size > MAX_SETTINGS_BYTES {
                return Err("Settings file is too large".to_string());
            }
            let plain = fs::read(&legacy_path).map_err(|error| error.to_string())?;
            serde_json::from_slice::<serde_json::Value>(&plain)
                .map_err(|error| format!("Некорректные настройки: {error}"))?;
            let protected = protect_for_current_user(&plain)?;
            write_atomic(&protected_path, &protected)?;
            write_atomic(&backup_path, &protected)?;
            fs::remove_file(legacy_path).map_err(|error| error.to_string())?;
            return String::from_utf8(plain).map_err(|error| error.to_string());
        }
        Ok("{}".to_string())
    }

    #[cfg(not(windows))]
    {
        let settings_path = app_dir.join("settings.json");
        if !settings_path.exists() {
            return Ok("{}".to_string());
        }
        fs::read_to_string(settings_path).map_err(|error| error.to_string())
    }
}

#[tauri::command]
fn save_settings(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    settings: String,
) -> Result<(), String> {
    let _settings_io = state
        .settings_io
        .lock()
        .map_err(|_| "Хранилище настроек недоступно".to_string())?;
    ensure_text_size(&settings, MAX_SETTINGS_BYTES, "Settings")?;
    serde_json::from_str::<serde_json::Value>(&settings)
        .map_err(|error| format!("Некорректные настройки: {error}"))?;
    let app_dir = app_data_dir(&app)?;

    #[cfg(windows)]
    {
        let protected_path = app_dir.join("settings.dat");
        let backup_path = app_dir.join("settings.dat.bak");
        if protected_path.exists() {
            read_protected_settings(&protected_path)
                .map_err(|_| "Текущие настройки повреждены; сохранение отменено".to_string())?;
            let current = fs::read(&protected_path).map_err(|error| error.to_string())?;
            write_atomic(&backup_path, &current)?;
        }
        let protected = protect_for_current_user(settings.as_bytes())?;
        write_atomic(&protected_path, &protected)?;
        if !backup_path.exists() {
            write_atomic(&backup_path, &protected)?;
        }
        let _ = fs::remove_file(app_dir.join("settings.json"));
        Ok(())
    }

    #[cfg(not(windows))]
    write_atomic(&app_dir.join("settings.json"), settings.as_bytes())
}

#[tauri::command]
fn get_app_version(app: tauri::AppHandle) -> String {
    app.package_info().version.to_string()
}

#[tauri::command]
async fn export_diagnostics(
    app: tauri::AppHandle,
    settings_summary: DiagnosticsSettingsSummary,
) -> Result<String, String> {
    let app_version = app.package_info().version.to_string();
    let app_log = app_data_dir(&app)?.join("app.log");
    let destination_dir = app
        .path()
        .download_dir()
        .or_else(|_| app.path().document_dir())
        .map_err(|error| format!("Не удалось открыть папку загрузок: {error}"))?;

    tauri::async_runtime::spawn_blocking(move || {
        #[cfg(windows)]
        {
            let service_value = service_call(vpn_ipc::VpnRequest::Diagnostics)?;
            let service: ServiceDiagnostics =
                serde_json::from_value(service_value).map_err(|error| error.to_string())?;
            let timestamp = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_err(|error| error.to_string())?
                .as_millis() as u64;
            let filename = format!("Warpy-diagnostics-{timestamp}-{}.zip", std::process::id());
            let destination = destination_dir.join(filename);
            let temporary = destination.with_extension("zip.tmp");
            let manifest = DiagnosticsManifest {
                bundle_version: 1,
                created_at_unix_ms: timestamp,
                app_version,
                platform: std::env::consts::OS,
                architecture: std::env::consts::ARCH,
                settings: settings_summary.normalize(),
                service: service.summary(),
            };
            let app_log = collect_sanitized_log(&app_log, false);
            if let Err(error) = write_bundle(&temporary, &manifest, &service, &app_log)
                .and_then(|_| fs::rename(&temporary, &destination).map_err(|e| e.to_string()))
            {
                let _ = fs::remove_file(&temporary);
                return Err(format!("Не удалось сохранить диагностику: {error}"));
            }
            Ok(destination.to_string_lossy().into_owned())
        }

        #[cfg(not(windows))]
        {
            let _ = (app_version, app_log, destination_dir, settings_summary);
            Err("Экспорт диагностики доступен только в Windows".to_string())
        }
    })
    .await
    .map_err(|error| format!("Сбой экспорта диагностики: {error}"))?
}

#[tauri::command]
fn log_message(app: tauri::AppHandle, message: String) {
    let Ok(app_dir) = app_data_dir(&app) else {
        return;
    };
    let log_path = app_dir.join("app.log");
    let _ = rotate_log(&log_path);
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(log_path) {
        let single_line: String = message
            .replace(['\r', '\n'], " ")
            .chars()
            .take(MAX_LOG_MESSAGE_CHARS)
            .collect();
        let _ = writeln!(file, "{single_line}");
    }
}

#[tauri::command]
fn select_executable() -> Result<Option<String>, String> {
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        let script = r#"
            Add-Type -AssemblyName System.Windows.Forms;
            $f = New-Object System.Windows.Forms.OpenFileDialog;
            $f.Filter = "Applications (*.exe)|*.exe";
            $f.InitialDirectory = "C:\Program Files";
            $f.Title = "Выберите программу";
            if ($f.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                Write-Output $f.FileName
            }
        "#;
        let output = Command::new("powershell")
            .args(["-NoProfile", "-NonInteractive", "-Command", script])
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .map_err(|error| error.to_string())?;

        if !output.status.success() {
            return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
        }
        let result = String::from_utf8_lossy(&output.stdout).trim().to_string();
        Ok((!result.is_empty()).then_some(result))
    }
    #[cfg(not(target_os = "windows"))]
    {
        Ok(None)
    }
}

#[tauri::command]
fn get_running_processes(exclude_system: bool) -> Result<Vec<String>, String> {
    let mut system = System::new();
    system.refresh_processes();
    let mut names = BTreeSet::new();
    for process in system.processes().values() {
        let name = process.name().trim();
        if name.is_empty() || !name.to_ascii_lowercase().ends_with(".exe") {
            continue;
        }
        if exclude_system {
            let path = process.exe().to_string_lossy().to_ascii_lowercase();
            if path.starts_with("c:\\windows\\") {
                continue;
            }
        }
        names.insert(name.to_string());
    }
    Ok(names.into_iter().collect())
}

fn main() {
    #[cfg(windows)]
    if let Some(command) = std::env::args_os().nth(1) {
        let command = command.to_string_lossy();
        let result = match command.as_ref() {
            "--service" => vpn_service::dispatch(),
            "--install-service" => vpn_service::install_or_update(),
            "--stop-service" => vpn_service::stop(),
            "--uninstall-service" => vpn_service::uninstall(),
            "--autostart" => run_app(true, false, false),
            "--post-update-health-check" => run_app(false, true, false),
            "--rollback-shutdown" => run_app(false, false, true),
            _ => run_app(false, false, false),
        };
        if matches!(
            command.as_ref(),
            "--service" | "--install-service" | "--stop-service" | "--uninstall-service"
        ) {
            if let Err(error) = result {
                vpn_service::log_error(&error);
                std::process::exit(1);
            }
            return;
        }
        return;
    }

    run_app(false, false, false).expect("failed to run Warpy Tauri app");
}

fn run_app(
    autostart_launch: bool,
    post_update_launch: bool,
    rollback_shutdown: bool,
) -> Result<(), String> {
    tauri::Builder::default()
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            if args
                .iter()
                .any(|argument| argument == "--rollback-shutdown")
            {
                app.exit(0);
                return;
            }
            if args.iter().any(|argument| argument == "--autostart") {
                return;
            }
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .manage(AppState {
            settings_io: Mutex::new(()),
            tray_menu_io: Mutex::new(()),
            autostart_launch,
            post_update_launch,
        })
        .invoke_handler(tauri::generate_handler![
            get_vpn_status,
            get_vpn_runtime_snapshot,
            get_vpn_started_at,
            get_vpn_network_stats,
            get_kill_switch_status,
            start_vpn,
            forget_vpn_outbound,
            switch_vpn_outbound,
            stop_vpn,
            cancel_vpn_start,
            set_resume_on_boot,
            update_tray_menu,
            is_autostart_launch,
            is_post_update_launch,
            confirm_launch_health,
            fetch_subscription,
            load_settings,
            save_settings,
            get_app_version,
            export_diagnostics,
            updates::check_for_update,
            updates::install_update,
            log_message,
            select_executable,
            get_running_processes
        ])
        .setup(move |app| {
            if rollback_shutdown {
                app.handle().exit(0);
                return Ok(());
            }
            let initial_tray =
                tray_menu::build(app.handle(), &tray_menu::TrayMenuSnapshot::default())
                    .map_err(std::io::Error::other)?;

            tauri::tray::TrayIconBuilder::with_id(tray_menu::TRAY_ID)
                .icon(app.default_window_icon().cloned().unwrap())
                .tooltip(initial_tray.tooltip)
                .menu(&initial_tray.menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.unminimize();
                            let _ = window.set_focus();
                        }
                    }
                    "quit" => match service_call(vpn_ipc::VpnRequest::Stop) {
                        Ok(_) => app.exit(0),
                        Err(error) => {
                            log_message(app.clone(), format!("tray exit failed: {error}"));
                            if let Some(window) = app.get_webview_window("main") {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                    },
                    id => {
                        if let Some(command) = tray_menu::command_from_menu_id(id) {
                            if let Err(error) = app.emit(tray_menu::TRAY_COMMAND_EVENT, command) {
                                log_message(
                                    app.clone(),
                                    format!("tray command emit failed: {error}"),
                                );
                            }
                        }
                    }
                })
                .on_tray_icon_event(|tray, event| {
                    if let tauri::tray::TrayIconEvent::Click {
                        button: tauri::tray::MouseButton::Left,
                        button_state: tauri::tray::MouseButtonState::Up,
                        ..
                    } = event
                    {
                        let app = tray.app_handle();
                        if let Some(window) = app.get_webview_window("main") {
                            if window.is_visible().unwrap_or(false) {
                                let _ = window.hide();
                            } else {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                        }
                    }
                })
                .build(app)?;

            if let Err(error) = service_call(vpn_ipc::VpnRequest::AttachUi {
                process_id: std::process::id(),
            }) {
                log_message(
                    app.handle().clone(),
                    format!("service attach failed: {error}"),
                );
            }

            if let Ok(dir) = app.path().app_data_dir() {
                let _ = fs::remove_file(dir.join("config.json"));
            }
            if let Some(window) = app.get_webview_window("main") {
                if autostart_launch {
                    let _ = window.hide();
                } else {
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .run(tauri::generate_context!())
        .map_err(|error| error.to_string())
}

#[cfg(all(test, windows))]
mod tests {
    use super::{
        ensure_text_size, protect_for_current_user, unprotect_for_current_user,
        validate_runtime_outbound,
    };

    #[test]
    fn dpapi_round_trip() {
        let source = br#"{"profile":"secret"}"#;
        let protected = protect_for_current_user(source).expect("protect settings");
        assert_ne!(protected, source);
        assert_eq!(
            unprotect_for_current_user(&protected).expect("unprotect settings"),
            source
        );
    }

    #[test]
    fn runtime_outbound_accepts_only_canonical_profile_ids() {
        assert!(validate_runtime_outbound("profile-1").is_ok());
        assert!(validate_runtime_outbound("profile-999").is_ok());
        for invalid in ["", "profile-0", "profile-01", "profile-x", "../profile-1"] {
            assert!(validate_runtime_outbound(invalid).is_err(), "{invalid}");
        }
    }

    #[test]
    fn text_size_limit_accepts_exact_boundary() {
        assert!(ensure_text_size("1234", 4, "test").is_ok());
        assert!(ensure_text_size("12345", 4, "test").is_err());
    }
}
