#![cfg(windows)]

use crate::{
    diagnostics::{collect_sanitized_log, ServiceDiagnostics},
    network_context::{NetworkContextReader, NetworkContextSnapshot},
    vpn_engine::{read_vpn_network_stats, rotate_log, EnginePaths, VpnEngine},
    vpn_ipc::{run_server, VpnRequest},
    windows_connectivity::{ConnectivityEvent, ConnectivitySchedule, NetworkChangeSubscription},
};
use serde_json::{json, Value};
use std::{
    ffi::{OsStr, OsString},
    fs::{self, OpenOptions},
    io::Write,
    os::windows::ffi::OsStrExt,
    path::PathBuf,
    ptr,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{sync_channel, Receiver, RecvTimeoutError},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use windows_service::{
    define_windows_service,
    service::{
        PowerEventParam, ServiceAccess, ServiceAction, ServiceActionType, ServiceControl,
        ServiceControlAccept, ServiceErrorControl, ServiceExitCode, ServiceFailureActions,
        ServiceFailureResetPeriod, ServiceInfo, ServiceStartType, ServiceState, ServiceStatus,
        ServiceType, SessionChangeReason,
    },
    service_control_handler::{self, ServiceControlHandlerResult},
    service_dispatcher,
    service_manager::{ServiceManager, ServiceManagerAccess},
};
use windows_sys::Win32::{
    Foundation::{CloseHandle, LocalFree, ERROR_SERVICE_DOES_NOT_EXIST},
    Security::{
        Authorization::{ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1},
        SetFileSecurityW, DACL_SECURITY_INFORMATION, PROTECTED_DACL_SECURITY_INFORMATION,
        PSECURITY_DESCRIPTOR,
    },
    System::Threading::{GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION},
};

pub(crate) const SERVICE_NAME: &str = "WarpyVpnService";
const SERVICE_DISPLAY_NAME: &str = "Warpy VPN Service";
const SERVICE_DESCRIPTION: &str = "Provides the privileged VPN tunnel for Warpy.";
const SERVICE_TYPE: ServiceType = ServiceType::OWN_PROCESS;
const RESUME_SESSION_FILE: &str = "resume.dat";

define_windows_service!(ffi_service_main, service_main);

pub(crate) fn dispatch() -> Result<(), String> {
    service_dispatcher::start(SERVICE_NAME, ffi_service_main).map_err(|error| error.to_string())
}

fn service_main(_arguments: Vec<OsString>) {
    if let Err(error) = run_service() {
        append_service_log(&format!("service stopped with error: {error}"));
    }
}

fn run_service() -> Result<(), String> {
    let running = Arc::new(AtomicBool::new(true));
    let (connectivity_sender, connectivity_receiver) = sync_channel(16);
    let handler_running = Arc::clone(&running);
    let handler_connectivity_sender = connectivity_sender.clone();
    let event_handler = move |event| match event {
        ServiceControl::Interrogate => ServiceControlHandlerResult::NoError,
        ServiceControl::Stop | ServiceControl::Shutdown => {
            handler_running.store(false, Ordering::Release);
            let _ = handler_connectivity_sender.try_send(ConnectivityEvent::Stop);
            ServiceControlHandlerResult::NoError
        }
        ServiceControl::PowerEvent(
            PowerEventParam::ResumeAutomatic
            | PowerEventParam::ResumeSuspend
            | PowerEventParam::ResumeCritical,
        ) => {
            let _ = handler_connectivity_sender.try_send(ConnectivityEvent::Resume);
            ServiceControlHandlerResult::NoError
        }
        ServiceControl::SessionChange(change)
            if change.reason == SessionChangeReason::SessionUnlock =>
        {
            let _ = handler_connectivity_sender.try_send(ConnectivityEvent::Unlock);
            ServiceControlHandlerResult::NoError
        }
        _ => ServiceControlHandlerResult::NotImplemented,
    };
    let status_handle = service_control_handler::register(SERVICE_NAME, event_handler)
        .map_err(|error| error.to_string())?;

    status_handle
        .set_service_status(service_status(
            ServiceState::StartPending,
            ServiceControlAccept::empty(),
            ServiceExitCode::Win32(0),
            Duration::from_secs(10),
        ))
        .map_err(|error| error.to_string())?;

    let worker_result = (|| {
        let paths = service_engine_paths()?;
        let _ = fs::remove_file(paths.app_dir.join(RESUME_SESSION_FILE));
        let engine = Arc::new(VpnEngine::new());
        engine.prepare(&paths)?;
        let ui_process_id = Arc::new(Mutex::new(None::<u32>));
        let network_context = Arc::new(Mutex::new(NetworkContextSnapshot::default()));
        status_handle
            .set_service_status(service_status(
                ServiceState::Running,
                ServiceControlAccept::STOP
                    | ServiceControlAccept::SHUTDOWN
                    | ServiceControlAccept::POWER_EVENT
                    | ServiceControlAccept::SESSION_CHANGE,
                ServiceExitCode::Win32(0),
                Duration::ZERO,
            ))
            .map_err(|error| error.to_string())?;

        let network_subscription =
            match NetworkChangeSubscription::register(connectivity_sender.clone()) {
                Ok(subscription) => Some(subscription),
                Err(error) => {
                    append_service_log(&format!("connectivity notifications unavailable: {error}"));
                    None
                }
            };

        let connectivity_engine = Arc::clone(&engine);
        let connectivity_running = Arc::clone(&running);
        let connectivity_ui_process_id = Arc::clone(&ui_process_id);
        let connectivity_network_context = Arc::clone(&network_context);
        let connectivity_monitor = thread::spawn(move || {
            run_connectivity_monitor(
                connectivity_running,
                connectivity_engine,
                connectivity_ui_process_id,
                connectivity_network_context,
                connectivity_receiver,
            );
        });

        let monitor_engine = Arc::clone(&engine);
        let monitor_paths = paths.clone();
        let monitor_running = Arc::clone(&running);
        let monitor_ui_process_id = Arc::clone(&ui_process_id);
        let monitor = thread::spawn(move || {
            while monitor_running.load(Ordering::Acquire) {
                if attached_ui_is_alive(monitor_ui_process_id.as_ref()) {
                    if let Some(event) = monitor_engine.maintain(&monitor_paths) {
                        append_service_log(&event);
                    }
                } else if monitor_engine.status() != "Stopped" {
                    match monitor_engine.stop() {
                        Ok(()) => append_service_log("VPN stopped: Warpy tray process exited"),
                        Err(error) => append_service_log(&format!(
                            "failed to stop VPN after Warpy tray process exited: {error}"
                        )),
                    }
                }
                for _ in 0..5 {
                    if !monitor_running.load(Ordering::Acquire) {
                        return;
                    }
                    thread::sleep(Duration::from_millis(100));
                }
            }
        });

        let result = run_server(
            &running,
            |request| {
                handle_request(
                    engine.as_ref(),
                    &paths,
                    ui_process_id.as_ref(),
                    network_context.as_ref(),
                    request,
                )
            },
            |error| append_service_log(&format!("IPC: {error}")),
        );
        running.store(false, Ordering::Release);
        drop(network_subscription);
        let _ = connectivity_sender.try_send(ConnectivityEvent::Stop);
        let _ = monitor.join();
        let _ = connectivity_monitor.join();
        let _ = engine.stop();
        result
    })();

    let exit_code = if worker_result.is_ok() {
        ServiceExitCode::Win32(0)
    } else {
        ServiceExitCode::ServiceSpecific(1)
    };
    let _ = status_handle.set_service_status(service_status(
        ServiceState::Stopped,
        ServiceControlAccept::empty(),
        exit_code,
        Duration::ZERO,
    ));
    worker_result
}

fn run_connectivity_monitor(
    running: Arc<AtomicBool>,
    engine: Arc<VpnEngine>,
    ui_process_id: Arc<Mutex<Option<u32>>>,
    network_context: Arc<Mutex<NetworkContextSnapshot>>,
    receiver: Receiver<ConnectivityEvent>,
) {
    let reader = match NetworkContextReader::new() {
        Ok(reader) => Some(reader),
        Err(error) => {
            append_service_log(&error);
            None
        }
    };
    let mut network_generation = 0_u64;
    refresh_network_context(
        reader.as_ref(),
        network_context.as_ref(),
        &mut network_generation,
    );
    let mut schedule = ConnectivitySchedule::default();
    while running.load(Ordering::Acquire) {
        let now = Instant::now();
        match receiver.recv_timeout(schedule.wait_duration(now)) {
            Ok(ConnectivityEvent::Stop) | Err(RecvTimeoutError::Disconnected) => return,
            Ok(event) => schedule.push(event, Instant::now()),
            Err(RecvTimeoutError::Timeout) => {}
        }

        if !running.load(Ordering::Acquire) {
            return;
        }
        let Some(trigger) = schedule.take_due(Instant::now()) else {
            continue;
        };
        refresh_network_context(
            reader.as_ref(),
            network_context.as_ref(),
            &mut network_generation,
        );
        if !attached_ui_is_alive(ui_process_id.as_ref()) {
            continue;
        }
        if let Some(event) = engine.handle_connectivity_event(trigger.label()) {
            append_service_log(&event);
        }
    }
}

fn refresh_network_context(
    reader: Option<&NetworkContextReader>,
    network_context: &Mutex<NetworkContextSnapshot>,
    generation: &mut u64,
) {
    *generation = generation.saturating_add(1);
    let snapshot = reader
        .and_then(|reader| match reader.read(*generation) {
            Ok(snapshot) => Some(snapshot),
            Err(error) => {
                append_service_log(&error);
                None
            }
        })
        .unwrap_or(NetworkContextSnapshot {
            generation: *generation,
            ..NetworkContextSnapshot::default()
        });
    if let Ok(mut current) = network_context.lock() {
        *current = snapshot;
    }
}

fn handle_request(
    engine: &VpnEngine,
    paths: &EnginePaths,
    ui_process_id: &Mutex<Option<u32>>,
    network_context: &Mutex<NetworkContextSnapshot>,
    request: VpnRequest,
) -> Result<Value, String> {
    match request {
        VpnRequest::Status => Ok(json!(engine.status())),
        VpnRequest::RuntimeSnapshot => {
            let network = network_context
                .lock()
                .map_err(|_| "Network context is unavailable".to_string())?
                .clone();
            let competing_vpn = crate::vpn_kill_switch::competing_vpn_active()?;
            Ok(json!({
                "status": engine.status(),
                "desiredRunning": engine.desired_running(),
                "network": network,
                "competingVpn": competing_vpn,
            }))
        }
        VpnRequest::StartedAt => Ok(json!(engine.started_at_ms())),
        VpnRequest::Diagnostics => serde_json::to_value(collect_service_diagnostics(engine, paths))
            .map_err(|error| error.to_string()),
        VpnRequest::NetworkStats => {
            serde_json::to_value(read_vpn_network_stats()?).map_err(|error| error.to_string())
        }
        VpnRequest::KillSwitchStatus => Ok(json!(engine.kill_switch_status())),
        VpnRequest::AttachUi { process_id } => {
            if !process_is_running(process_id) {
                return Err("Warpy tray process is not running".to_string());
            }
            *ui_process_id
                .lock()
                .map_err(|_| "Warpy tray state is unavailable".to_string())? = Some(process_id);
            Ok(Value::Null)
        }
        VpnRequest::SetAutostart { enabled } => {
            let process_id = ui_process_id
                .lock()
                .map_err(|_| "Warpy tray state is unavailable".to_string())?
                .filter(|process_id| process_is_running(*process_id));
            crate::windows_autostart::configure(enabled, process_id)?;
            Ok(Value::Null)
        }
        VpnRequest::ForgetOutbound { outbound } => {
            if !attached_ui_is_alive(ui_process_id) {
                return Err("Warpy must be running in the notification area".to_string());
            }
            engine.forget_outbound(&outbound)?;
            append_service_log(&format!("selector forgot {outbound}"));
            Ok(Value::Null)
        }
        VpnRequest::Start {
            config,
            kill_switch,
        } => {
            if !attached_ui_is_alive(ui_process_id) {
                return Err("Warpy must be running in the notification area".to_string());
            }
            match engine.start(paths, &config, kill_switch) {
                Ok(()) => {
                    append_service_log(&format!("kill switch: {}", engine.kill_switch_status()));
                    Ok(Value::Null)
                }
                Err(error) => {
                    append_service_log(&format!("start failed: {error}"));
                    Err(error)
                }
            }
        }
        VpnRequest::SwitchOutbound { outbound } => {
            if !attached_ui_is_alive(ui_process_id) {
                return Err("Warpy must be running in the notification area".to_string());
            }
            engine.switch_outbound(&outbound)?;
            append_service_log(&format!("selector switched to {outbound}"));
            Ok(Value::Null)
        }
        VpnRequest::Stop => {
            engine.stop()?;
            Ok(Value::Null)
        }
    }
}

fn collect_service_diagnostics(engine: &VpnEngine, paths: &EnginePaths) -> ServiceDiagnostics {
    let status = engine.status();
    let uptime_seconds = engine.started_at_ms().and_then(|started_at| {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .ok()?
            .as_millis() as u64;
        matches!(status.as_str(), "Connected" | "Recovering" | "Validating")
            .then_some(now.saturating_sub(started_at) / 1_000)
    });
    ServiceDiagnostics {
        status,
        uptime_seconds,
        kill_switch: engine.kill_switch_status(),
        service_log: collect_sanitized_log(&paths.app_dir.join("service.log"), false),
        core_errors: collect_sanitized_log(&paths.app_dir.join("sing-box.log"), true),
    }
}

fn attached_ui_is_alive(ui_process_id: &Mutex<Option<u32>>) -> bool {
    ui_process_id
        .lock()
        .ok()
        .and_then(|process_id| *process_id)
        .is_some_and(process_is_running)
}

fn process_is_running(process_id: u32) -> bool {
    const STILL_ACTIVE_EXIT_CODE: u32 = 259;

    if process_id == 0 {
        return false;
    }
    let process = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, process_id) };
    if process.is_null() {
        return false;
    }
    let mut exit_code = 0_u32;
    let running = unsafe { GetExitCodeProcess(process, &mut exit_code) } != 0
        && exit_code == STILL_ACTIVE_EXIT_CODE;
    unsafe {
        let _ = CloseHandle(process);
    }
    running
}

fn service_engine_paths() -> Result<EnginePaths, String> {
    let executable = std::env::current_exe().map_err(|error| error.to_string())?;
    let binary_dir = executable
        .parent()
        .ok_or_else(|| "Некорректный путь службы Warpy".to_string())?;
    let core = [
        binary_dir.join("sing-box.exe"),
        binary_dir.join("sing-box-x86_64-pc-windows-msvc.exe"),
    ]
    .into_iter()
    .find(|path| path.is_file())
    .ok_or_else(|| "VPN-ядро sing-box не найдено".to_string())?;
    let app_dir = program_data_dir()?;
    fs::create_dir_all(&app_dir).map_err(|error| error.to_string())?;
    protect_service_directory(&app_dir)?;

    Ok(EnginePaths {
        app_dir,
        core,
        wintun_sources: vec![
            binary_dir.join("bin").join("wintun.dll"),
            binary_dir.join("wintun.dll"),
        ],
    })
}

fn service_status(
    current_state: ServiceState,
    controls_accepted: ServiceControlAccept,
    exit_code: ServiceExitCode,
    wait_hint: Duration,
) -> ServiceStatus {
    ServiceStatus {
        service_type: SERVICE_TYPE,
        current_state,
        controls_accepted,
        exit_code,
        checkpoint: 0,
        wait_hint,
        process_id: None,
    }
}

pub(crate) fn install_or_update() -> Result<(), String> {
    let manager = ServiceManager::local_computer(
        None::<&str>,
        ServiceManagerAccess::CONNECT | ServiceManagerAccess::CREATE_SERVICE,
    )
    .map_err(|error| error.to_string())?;
    let service_info = ServiceInfo {
        name: OsString::from(SERVICE_NAME),
        display_name: OsString::from(SERVICE_DISPLAY_NAME),
        service_type: SERVICE_TYPE,
        start_type: ServiceStartType::AutoStart,
        error_control: ServiceErrorControl::Normal,
        executable_path: std::env::current_exe().map_err(|error| error.to_string())?,
        launch_arguments: vec![OsString::from("--service")],
        dependencies: vec![],
        account_name: None,
        account_password: None,
    };
    let access = ServiceAccess::QUERY_STATUS
        | ServiceAccess::START
        | ServiceAccess::STOP
        | ServiceAccess::CHANGE_CONFIG;

    let service = match manager.open_service(SERVICE_NAME, access) {
        Ok(service) => {
            stop_and_wait(&service, Duration::from_secs(10))?;
            service
                .change_config(&service_info)
                .map_err(|error| error.to_string())?;
            service
        }
        Err(error) if service_does_not_exist(&error) => manager
            .create_service(&service_info, access)
            .map_err(|error| error.to_string())?,
        Err(error) => return Err(error.to_string()),
    };

    service
        .set_description(SERVICE_DESCRIPTION)
        .map_err(|error| error.to_string())?;
    service
        .update_failure_actions(ServiceFailureActions {
            reset_period: ServiceFailureResetPeriod::After(Duration::from_secs(24 * 60 * 60)),
            reboot_msg: Some(OsString::new()),
            command: Some(OsString::new()),
            actions: Some(vec![
                ServiceAction {
                    action_type: ServiceActionType::Restart,
                    delay: Duration::from_secs(2),
                },
                ServiceAction {
                    action_type: ServiceActionType::Restart,
                    delay: Duration::from_secs(5),
                },
                ServiceAction {
                    action_type: ServiceActionType::Restart,
                    delay: Duration::from_secs(15),
                },
            ]),
        })
        .map_err(|error| error.to_string())?;
    service
        .set_failure_actions_on_non_crash_failures(true)
        .map_err(|error| error.to_string())?;
    service
        .start::<&OsStr>(&[])
        .map_err(|error| error.to_string())?;
    wait_for_state(&service, ServiceState::Running, Duration::from_secs(10))
}

pub(crate) fn stop() -> Result<(), String> {
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)
        .map_err(|error| error.to_string())?;
    let service = match manager.open_service(
        SERVICE_NAME,
        ServiceAccess::QUERY_STATUS | ServiceAccess::STOP,
    ) {
        Ok(service) => service,
        Err(error) if service_does_not_exist(&error) => return Ok(()),
        Err(error) => return Err(error.to_string()),
    };
    stop_and_wait(&service, Duration::from_secs(10))
}

pub(crate) fn uninstall() -> Result<(), String> {
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)
        .map_err(|error| error.to_string())?;
    let service = match manager.open_service(
        SERVICE_NAME,
        ServiceAccess::QUERY_STATUS | ServiceAccess::STOP | ServiceAccess::DELETE,
    ) {
        Ok(service) => service,
        Err(error) if service_does_not_exist(&error) => return Ok(()),
        Err(error) => return Err(error.to_string()),
    };

    stop_and_wait(&service, Duration::from_secs(10))?;
    service.delete().map_err(|error| error.to_string())?;
    drop(service);

    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline {
        match manager.open_service(SERVICE_NAME, ServiceAccess::QUERY_STATUS) {
            Err(error) if service_does_not_exist(&error) => return Ok(()),
            _ => thread::sleep(Duration::from_millis(200)),
        }
    }
    Err("Служба Warpy не удалилась за отведенное время".to_string())
}

fn stop_and_wait(
    service: &windows_service::service::Service,
    timeout: Duration,
) -> Result<(), String> {
    let state = service
        .query_status()
        .map_err(|error| error.to_string())?
        .current_state;
    if state == ServiceState::Stopped {
        return Ok(());
    }
    if state != ServiceState::StopPending {
        service.stop().map_err(|error| error.to_string())?;
    }
    wait_for_state(service, ServiceState::Stopped, timeout)
}

fn wait_for_state(
    service: &windows_service::service::Service,
    expected: ServiceState,
    timeout: Duration,
) -> Result<(), String> {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        let state = service
            .query_status()
            .map_err(|error| error.to_string())?
            .current_state;
        if state == expected {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(100));
    }
    Err(format!("Служба Warpy не перешла в состояние {expected:?}"))
}

fn service_does_not_exist(error: &windows_service::Error) -> bool {
    matches!(
        error,
        windows_service::Error::Winapi(error)
            if error.raw_os_error() == Some(ERROR_SERVICE_DOES_NOT_EXIST as i32)
    )
}

fn program_data_dir() -> Result<PathBuf, String> {
    std::env::var_os("ProgramData")
        .map(PathBuf::from)
        .map(|path| path.join("Warpy"))
        .ok_or_else(|| "Системная папка ProgramData недоступна".to_string())
}

fn protect_service_directory(path: &std::path::Path) -> Result<(), String> {
    let descriptor_text: Vec<u16> = OsStr::new("D:P(A;;FA;;;SY)(A;;FA;;;BA)")
        .encode_wide()
        .chain(Some(0))
        .collect();
    let mut descriptor: PSECURITY_DESCRIPTOR = ptr::null_mut();
    if unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            descriptor_text.as_ptr(),
            SDDL_REVISION_1,
            &mut descriptor,
            ptr::null_mut(),
        )
    } == 0
    {
        return Err(format!(
            "Не удалось создать права каталога службы: {}",
            std::io::Error::last_os_error()
        ));
    }

    let path_wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let result = unsafe {
        SetFileSecurityW(
            path_wide.as_ptr(),
            DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
            descriptor,
        )
    };
    unsafe {
        let _ = LocalFree(descriptor as _);
    }
    if result == 0 {
        return Err(format!(
            "Не удалось защитить каталог службы: {}",
            std::io::Error::last_os_error()
        ));
    }
    Ok(())
}

fn append_service_log(message: &str) {
    let Ok(dir) = program_data_dir() else {
        return;
    };
    if fs::create_dir_all(&dir).is_err() {
        return;
    }
    let path = dir.join("service.log");
    let _ = rotate_log(&path);
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(file, "{}", message.replace(['\r', '\n'], " "));
    }
}

pub(crate) fn log_error(message: &str) {
    append_service_log(message);
}

#[cfg(test)]
mod tests {
    use super::process_is_running;

    #[test]
    fn current_process_is_running() {
        assert!(process_is_running(std::process::id()));
    }

    #[test]
    fn zero_is_not_a_running_process() {
        assert!(!process_is_running(0));
    }
}
