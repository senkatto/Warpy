use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::Mutex,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use crate::vpn_recovery::{RecoveryState, MAX_RECOVERY_ATTEMPTS};

#[cfg(windows)]
use crate::vpn_kill_switch::{
    competing_vpn_active, KillSwitchState, VpnKillSwitch, COMPETING_VPN_ERROR,
};
#[cfg(windows)]
use crate::vpn_probe::{verify_tunnel, verify_tunnel_once};
#[cfg(windows)]
use crate::vpn_selector::{
    prepare_config as prepare_selector_config, remove_outbound, update_selected_outbound,
    SelectorControl,
};

#[cfg(not(windows))]
fn verify_tunnel() -> Result<(), String> {
    Ok(())
}

#[cfg(not(windows))]
fn verify_tunnel_once() -> Result<(), String> {
    Ok(())
}

const CREATE_NO_WINDOW: u32 = 0x08000000;
const MAX_APP_LOG_BYTES: u64 = 2 * 1024 * 1024;
const LOG_TAIL_BYTES: u64 = 8 * 1024;
const EXPECTED_CORE_SHA256: &str =
    "47FE53E73E99F219DE4495731E348EAE5FF0CFB831E31157B70B95A4BEF0D5B3";
const EXPECTED_CORE_BYTES: u64 = 65_593_344;
#[cfg(windows)]
const PHYSICAL_INTERFACE_RETRY_ATTEMPTS: usize = 60;
#[cfg(windows)]
const PHYSICAL_INTERFACE_RETRY_DELAY: Duration = Duration::from_millis(50);
#[cfg(windows)]
const STARTUP_OUTBOUND_PROBE_ATTEMPTS: usize = 3;
#[cfg(windows)]
const STARTUP_OUTBOUND_PROBE_RETRY_DELAY: Duration = Duration::from_millis(400);
#[cfg(windows)]
const CONNECTIVITY_EVENT_STARTUP_GRACE_MS: u64 = 20_000;
#[cfg(windows)]
const TUN_DNS_SERVER: &str = "1.1.1.1";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum EngineState {
    Stopped,
    Starting,
    Validating,
    Connected,
    Recovering,
    Stopping,
    Error,
}

impl EngineState {
    fn label(self) -> &'static str {
        match self {
            Self::Stopped => "Stopped",
            Self::Starting => "Starting",
            Self::Validating => "Validating",
            Self::Connected => "Connected",
            Self::Recovering => "Recovering",
            Self::Stopping => "Stopping",
            Self::Error => "Error",
        }
    }
}

#[derive(Clone)]
pub(crate) struct EnginePaths {
    pub(crate) app_dir: PathBuf,
    pub(crate) core: PathBuf,
    pub(crate) wintun_sources: Vec<PathBuf>,
}

pub(crate) struct VpnEngine {
    lifecycle: Mutex<()>,
    core: Mutex<Option<ManagedCore>>,
    started_at_ms: Mutex<Option<u64>>,
    verified_core: Mutex<Option<PathBuf>>,
    state: Mutex<EngineState>,
    desired_config: Mutex<Option<String>>,
    desired_kill_switch: Mutex<bool>,
    #[cfg(windows)]
    selector_control: Mutex<Option<SelectorControl>>,
    #[cfg(windows)]
    kill_switch: Mutex<VpnKillSwitch>,
    recovery: Mutex<RecoveryState>,
}

impl VpnEngine {
    pub(crate) fn new() -> Self {
        Self {
            lifecycle: Mutex::new(()),
            core: Mutex::new(None),
            started_at_ms: Mutex::new(None),
            verified_core: Mutex::new(None),
            state: Mutex::new(EngineState::Stopped),
            desired_config: Mutex::new(None),
            desired_kill_switch: Mutex::new(false),
            #[cfg(windows)]
            selector_control: Mutex::new(None),
            #[cfg(windows)]
            kill_switch: Mutex::new(VpnKillSwitch::new()),
            recovery: Mutex::new(RecoveryState::default()),
        }
    }

    pub(crate) fn status(&self) -> String {
        self.observe_core();
        self.current_state().label().to_string()
    }

    pub(crate) fn started_at_ms(&self) -> Option<u64> {
        self.started_at_ms.lock().ok().and_then(|value| *value)
    }

    pub(crate) fn desired_running(&self) -> bool {
        self.desired_config
            .lock()
            .map(|config| config.is_some())
            .unwrap_or(false)
    }

    pub(crate) fn kill_switch_status(&self) -> String {
        #[cfg(windows)]
        {
            self.kill_switch
                .lock()
                .map(|kill_switch| kill_switch.state().label())
                .unwrap_or_else(|_| "Error:Состояние защиты недоступно".to_string())
        }

        #[cfg(not(windows))]
        "Off".to_string()
    }

    pub(crate) fn prepare(&self, paths: &EnginePaths) -> Result<(), String> {
        let _lifecycle = self
            .lifecycle
            .lock()
            .map_err(|_| "Управление VPN недоступно".to_string())?;
        self.verify_core(&paths.core)?;
        ensure_wintun(paths)?;
        Ok(())
    }

    pub(crate) fn stop(&self) -> Result<(), String> {
        let _lifecycle = self
            .lifecycle
            .lock()
            .map_err(|_| "Управление VPN недоступно".to_string())?;
        self.set_desired_config(None);
        self.set_desired_kill_switch(false);
        self.clear_selector_control();
        self.reset_recovery();
        self.set_state(EngineState::Stopping);
        match self.stop_core() {
            Ok(()) => {
                self.disarm_kill_switch();
                self.set_state(EngineState::Stopped);
                Ok(())
            }
            Err(error) => {
                self.disarm_kill_switch();
                self.set_state(EngineState::Error);
                Err(error)
            }
        }
    }

    pub(crate) fn start(
        &self,
        paths: &EnginePaths,
        config: &str,
        kill_switch: bool,
    ) -> Result<(), String> {
        let _lifecycle = self
            .lifecycle
            .lock()
            .map_err(|_| "Управление VPN недоступно".to_string())?;
        #[cfg(windows)]
        if competing_vpn_active()? {
            return Err(COMPETING_VPN_ERROR.to_string());
        }
        #[cfg(windows)]
        let (prepared_config, selector_control) = prepare_selector_config(config)?;
        #[cfg(not(windows))]
        let prepared_config = config.to_string();
        #[cfg(windows)]
        self.set_selector_control(selector_control);
        self.set_desired_config(Some(prepared_config.clone()));
        self.set_desired_kill_switch(kill_switch);
        if !kill_switch {
            self.disarm_kill_switch();
        }
        self.reset_recovery();
        self.set_state(EngineState::Starting);
        let preserve_kill_switch = kill_switch && self.kill_switch_is_armed();
        let result = self.start_locked(
            paths,
            &prepared_config,
            EngineState::Validating,
            false,
            kill_switch,
            preserve_kill_switch,
        );
        if let Err(error) = result {
            let cleanup_error = self.stop_core().err();
            if !preserve_kill_switch {
                self.set_desired_config(None);
                self.set_desired_kill_switch(false);
                self.clear_selector_control();
                self.disarm_kill_switch();
            }
            self.set_state(EngineState::Error);
            self.clear_started_at();
            return Err(match cleanup_error {
                Some(cleanup) => format!("{error}; cleanup failed: {cleanup}"),
                None => error,
            });
        }
        Ok(())
    }

    #[cfg(windows)]
    pub(crate) fn switch_outbound(&self, outbound: &str) -> Result<(), String> {
        let _lifecycle = self
            .lifecycle
            .lock()
            .map_err(|_| "Управление VPN недоступно".to_string())?;
        self.switch_outbound_locked(outbound, None)
    }

    #[cfg(windows)]
    pub(crate) fn forget_outbound(&self, outbound: &str) -> Result<(), String> {
        let _lifecycle = self
            .lifecycle
            .lock()
            .map_err(|_| "VPN control is unavailable".to_string())?;
        let mut desired_config = self
            .desired_config
            .lock()
            .map_err(|_| "VPN configuration is unavailable".to_string())?;
        let updated_config = desired_config
            .as_deref()
            .ok_or_else(|| "VPN configuration is unavailable".to_string())
            .and_then(|config| remove_outbound(config, outbound))?;
        self.selector_control
            .lock()
            .map_err(|_| "Profile control is unavailable".to_string())?
            .as_mut()
            .ok_or_else(|| "SELECTOR_NOT_AVAILABLE".to_string())?
            .forget(outbound)?;
        *desired_config = Some(updated_config);
        Ok(())
    }

    #[cfg(windows)]
    fn switch_outbound_locked(
        &self,
        outbound: &str,
        expected_current: Option<&str>,
    ) -> Result<(), String> {
        if self.current_state() != EngineState::Connected {
            return Err("VPN не подключен".to_string());
        }

        let updated_config = self
            .desired_config
            .lock()
            .map_err(|_| "Конфигурация VPN недоступна".to_string())?
            .as_deref()
            .ok_or_else(|| "Конфигурация VPN недоступна".to_string())
            .and_then(|config| update_selected_outbound(config, outbound))?;
        let mut control_guard = self
            .selector_control
            .lock()
            .map_err(|_| "Управление профилями недоступно".to_string())?;
        let control = control_guard
            .as_mut()
            .ok_or_else(|| "SELECTOR_NOT_AVAILABLE".to_string())?;
        if expected_current.is_some_and(|expected| control.selected() != expected) {
            return Err("AUTO_STALE_RECOMMENDATION".to_string());
        }
        if !control.supports(outbound) {
            return Err("SELECTOR_UNKNOWN_OUTBOUND".to_string());
        }
        let result = control.select_verified(outbound);
        drop(control_guard);
        match result {
            Ok(()) => {
                *self
                    .desired_config
                    .lock()
                    .map_err(|_| "Конфигурация VPN недоступна".to_string())? = Some(updated_config);
                Ok(())
            }
            Err(error) => {
                if error.rollback_failed() {
                    self.request_recovery();
                }
                Err(error.to_string())
            }
        }
    }

    pub(crate) fn maintain(&self, paths: &EnginePaths) -> Option<String> {
        self.observe_core();
        let kill_switch_event = self.maintain_kill_switch(paths);
        if self.current_state() != EngineState::Recovering || !self.recovery_is_due() {
            return kill_switch_event;
        }

        let _lifecycle = match self.lifecycle.lock() {
            Ok(lock) => lock,
            Err(_) => {
                self.set_state(EngineState::Error);
                return Some("recovery: управление VPN недоступно".to_string());
            }
        };
        if self.current_state() != EngineState::Recovering {
            return None;
        }
        let config = match self.desired_config.lock() {
            Ok(config) => config.clone(),
            Err(_) => {
                self.set_state(EngineState::Error);
                return Some("recovery: конфигурация VPN недоступна".to_string());
            }
        };
        let Some(config) = config else {
            self.reset_recovery();
            self.set_state(EngineState::Stopped);
            return None;
        };
        let attempt = self.begin_recovery_attempt()?;
        let kill_switch = self.desired_kill_switch();

        match self.start_locked(
            paths,
            &config,
            EngineState::Recovering,
            true,
            kill_switch,
            kill_switch,
        ) {
            Ok(()) => {
                self.reset_recovery();
                Some(format!(
                    "recovery: соединение восстановлено с попытки {attempt}"
                ))
            }
            Err(error) => {
                self.clear_started_at();
                if attempt >= MAX_RECOVERY_ATTEMPTS {
                    self.set_state(EngineState::Error);
                } else {
                    self.schedule_recovery_after(attempt);
                    self.set_state(EngineState::Recovering);
                }
                Some(format!(
                    "recovery {attempt}/{MAX_RECOVERY_ATTEMPTS}: {error}"
                ))
            }
        }
    }

    #[cfg(windows)]
    pub(crate) fn handle_connectivity_event(&self, trigger: &str) -> Option<String> {
        self.observe_core();
        let _lifecycle = self.lifecycle.lock().ok()?;
        let state = self.current_state();
        let has_desired_config = self
            .desired_config
            .lock()
            .map(|config| config.is_some())
            .unwrap_or(false);
        if !has_desired_config {
            return None;
        }

        if state == EngineState::Connected
            && self.started_at_ms().is_some_and(|started_at| {
                now_ms().saturating_sub(started_at) < CONNECTIVITY_EVENT_STARTUP_GRACE_MS
            })
        {
            return None;
        }

        if state == EngineState::Connected && verify_tunnel().is_ok() {
            return None;
        }

        if !matches!(state, EngineState::Connected | EngineState::Error) {
            return None;
        }

        self.request_recovery();
        Some(format!(
            "connectivity {trigger}: tunnel validation failed, recovery requested"
        ))
    }

    fn start_locked(
        &self,
        paths: &EnginePaths,
        config: &str,
        validation_state: EngineState,
        quick_probe: bool,
        kill_switch: bool,
        preserve_kill_switch: bool,
    ) -> Result<(), String> {
        self.stop_core()?;

        let mut runtime_config = serde_json::from_str::<serde_json::Value>(config)
            .map_err(|error| format!("Некорректная конфигурация: {error}"))?;
        #[cfg(windows)]
        if let Some(interface) = physical_default_interface() {
            set_default_interface(&mut runtime_config, &interface);
        }
        let runtime_config =
            serde_json::to_string(&runtime_config).map_err(|error| error.to_string())?;

        fs::create_dir_all(&paths.app_dir).map_err(|error| error.to_string())?;
        let config_path = paths.app_dir.join("config.json");
        write_atomic(&config_path, runtime_config.as_bytes())?;
        let _config_guard = EphemeralFile(config_path.clone());

        self.verify_core(&paths.core)?;
        ensure_wintun(paths)?;

        let check_output = singbox_command(&paths.core)
            .arg("check")
            .arg("-c")
            .arg(&config_path)
            .output()
            .map_err(|error| format!("Не удалось проверить конфигурацию: {error}"))?;
        if !check_output.status.success() {
            let details = String::from_utf8_lossy(&check_output.stderr)
                .trim()
                .to_string();
            return Err(format!("Ошибка конфигурации VPN: {details}"));
        }

        let log_path = paths.app_dir.join("sing-box.log");
        rotate_log(&log_path)?;
        let log_file = fs::File::create(&log_path).map_err(|error| error.to_string())?;

        let mut child = singbox_command(&paths.core)
            .arg("run")
            .arg("-c")
            .arg(&config_path)
            .stdout(Stdio::from(
                log_file.try_clone().map_err(|error| error.to_string())?,
            ))
            .stderr(Stdio::from(log_file))
            .spawn()
            .map_err(|error| format!("Не удалось запустить VPN-ядро: {error}"))?;

        #[cfg(windows)]
        let job = match assign_kill_on_close_job(&child) {
            Ok(job) => job,
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(error);
            }
        };

        std::thread::sleep(std::time::Duration::from_millis(350));
        let initial_status = match child.try_wait() {
            Ok(status) => status,
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(error.to_string());
            }
        };
        if let Some(status) = initial_status {
            let details = last_log_message(&tail_file(&log_path));
            return Err(if details.is_empty() {
                format!("VPN-ядро завершилось сразу после запуска: {status}")
            } else {
                details
            });
        }

        #[cfg(windows)]
        if let Err(error) = configure_tun_dns() {
            let _ = child.kill();
            let _ = child.wait();
            cleanup_stale_tun_default_route();
            return Err(error);
        }

        self.set_state(validation_state);
        let probe_result = self.verify_started_outbound(quick_probe);
        if let Err(error) = probe_result {
            let _ = child.kill();
            let _ = child.wait();
            #[cfg(windows)]
            cleanup_stale_tun_default_route();
            return Err(error);
        }

        if kill_switch {
            if let Err(error) = self.arm_kill_switch(&paths.core, config, preserve_kill_switch) {
                let _ = child.kill();
                let _ = child.wait();
                return Err(error);
            }
        }

        let mut lock = match self.core.lock() {
            Ok(lock) => lock,
            Err(_) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err("Состояние VPN недоступно".to_string());
            }
        };
        *lock = Some(ManagedCore {
            child,
            #[cfg(windows)]
            _job: job,
        });
        *self
            .started_at_ms
            .lock()
            .map_err(|_| "Состояние VPN недоступно".to_string())? = Some(now_ms());
        self.set_state(EngineState::Connected);

        Ok(())
    }

    #[cfg(windows)]
    fn verify_started_outbound(&self, quick_probe: bool) -> Result<(), String> {
        let control = self
            .selector_control
            .lock()
            .map_err(|_| "Управление профилями VPN недоступно".to_string())?
            .clone();
        let Some(control) = control else {
            return if quick_probe {
                verify_tunnel_once()
            } else {
                verify_tunnel()
            };
        };

        let outbound = control.selected().to_string();
        let attempts = if quick_probe {
            2
        } else {
            STARTUP_OUTBOUND_PROBE_ATTEMPTS
        };
        let mut last_error = "Сервер VPN не передал контрольные данные".to_string();
        for attempt in 0..attempts {
            match control.probe_outbound(&outbound) {
                Ok(_) => {
                    // Prime the Windows DNS/TUN path, but do not tear down a verified
                    // outbound when WinHTTP itself is slow during adapter startup.
                    let _ = verify_tunnel_once();
                    return Ok(());
                }
                Err(error) => last_error = error,
            }
            if attempt + 1 < attempts {
                std::thread::sleep(STARTUP_OUTBOUND_PROBE_RETRY_DELAY);
            }
        }
        // The Clash delay endpoint can transiently return 503 even when the
        // selected tunnel is already carrying traffic. Trust a successful
        // end-to-end request before deciding to tear the tunnel down.
        if verify_tunnel_once().is_ok() {
            return Ok(());
        }
        Err(last_error)
    }

    #[cfg(not(windows))]
    fn verify_started_outbound(&self, quick_probe: bool) -> Result<(), String> {
        if quick_probe {
            verify_tunnel_once()
        } else {
            verify_tunnel()
        }
    }

    fn stop_core(&self) -> Result<(), String> {
        let mut lock = self
            .core
            .lock()
            .map_err(|_| "Состояние VPN недоступно".to_string())?;
        if let Some(mut core) = lock.take() {
            let _ = core.child.kill();
            let _ = core.child.wait();
        }
        #[cfg(windows)]
        cleanup_stale_tun_default_route();
        *self
            .started_at_ms
            .lock()
            .map_err(|_| "Состояние VPN недоступно".to_string())? = None;
        Ok(())
    }

    fn clear_started_at(&self) {
        if let Ok(mut started_at) = self.started_at_ms.lock() {
            *started_at = None;
        }
    }

    fn observe_core(&self) {
        let core_failed = match self.core.lock() {
            Ok(mut core) => match core.as_mut() {
                Some(process) => match process.child.try_wait() {
                    Ok(None) => false,
                    Ok(Some(_)) | Err(_) => {
                        *core = None;
                        true
                    }
                },
                None => false,
            },
            Err(_) => true,
        };
        if !core_failed {
            return;
        }

        self.clear_started_at();
        let should_recover = self
            .desired_config
            .lock()
            .map(|config| config.is_some())
            .unwrap_or(false);
        if should_recover {
            self.request_recovery();
        } else {
            self.set_state(EngineState::Error);
        }
    }

    fn recovery_is_due(&self) -> bool {
        self.recovery
            .lock()
            .map(|recovery| recovery.is_due())
            .unwrap_or(false)
    }

    fn begin_recovery_attempt(&self) -> Option<usize> {
        self.recovery.lock().ok()?.begin_attempt()
    }

    fn schedule_recovery_after(&self, attempt: usize) {
        if let Ok(mut recovery) = self.recovery.lock() {
            recovery.schedule_after(attempt);
        }
    }

    fn reset_recovery(&self) {
        if let Ok(mut recovery) = self.recovery.lock() {
            recovery.reset();
        }
    }

    fn request_recovery(&self) {
        if let Ok(mut recovery) = self.recovery.lock() {
            recovery.request_now();
        }
        self.set_state(EngineState::Recovering);
    }

    fn set_desired_config(&self, config: Option<String>) {
        if let Ok(mut desired) = self.desired_config.lock() {
            *desired = config;
        }
    }

    fn set_desired_kill_switch(&self, enabled: bool) {
        if let Ok(mut desired) = self.desired_kill_switch.lock() {
            *desired = enabled;
        }
    }

    #[cfg(windows)]
    fn set_selector_control(&self, control: Option<SelectorControl>) {
        if let Ok(mut current) = self.selector_control.lock() {
            *current = control;
        }
    }

    fn clear_selector_control(&self) {
        #[cfg(windows)]
        if let Ok(mut current) = self.selector_control.lock() {
            *current = None;
        }
    }

    fn desired_kill_switch(&self) -> bool {
        self.desired_kill_switch
            .lock()
            .map(|desired| *desired)
            .unwrap_or(false)
    }

    fn kill_switch_is_armed(&self) -> bool {
        #[cfg(windows)]
        {
            self.kill_switch
                .lock()
                .map(|kill_switch| kill_switch.state() == KillSwitchState::Armed)
                .unwrap_or(false)
        }

        #[cfg(not(windows))]
        false
    }

    #[cfg(windows)]
    fn arm_kill_switch(
        &self,
        core_path: &Path,
        config: &str,
        preserve_session: bool,
    ) -> Result<KillSwitchState, String> {
        let mut kill_switch = self
            .kill_switch
            .lock()
            .map_err(|_| "Состояние защиты недоступно".to_string())?;
        if preserve_session {
            kill_switch.reconcile(core_path, config)
        } else {
            kill_switch.arm(core_path, config)
        }
    }

    #[cfg(not(windows))]
    fn arm_kill_switch(
        &self,
        _core_path: &Path,
        _config: &str,
        _preserve_session: bool,
    ) -> Result<(), String> {
        Ok(())
    }

    fn disarm_kill_switch(&self) {
        #[cfg(windows)]
        if let Ok(mut kill_switch) = self.kill_switch.lock() {
            kill_switch.disarm();
        }
    }

    fn maintain_kill_switch(&self, paths: &EnginePaths) -> Option<String> {
        if !matches!(
            self.current_state(),
            EngineState::Connected | EngineState::Recovering | EngineState::Error
        ) || !self.desired_kill_switch()
        {
            return None;
        }

        let _lifecycle = self.lifecycle.try_lock().ok()?;
        if !matches!(
            self.current_state(),
            EngineState::Connected | EngineState::Recovering | EngineState::Error
        ) || !self.desired_kill_switch()
        {
            return None;
        }
        let config = self.desired_config.lock().ok()?.clone()?;

        #[cfg(windows)]
        {
            let mut kill_switch = self.kill_switch.lock().ok()?;
            let before = kill_switch.state();
            match kill_switch.reconcile(&paths.core, &config) {
                Ok(after) if after != before => Some(format!(
                    "kill switch: {} -> {}",
                    before.label(),
                    after.label()
                )),
                Ok(_) => None,
                Err(error) => Some(format!("kill switch: {error}")),
            }
        }

        #[cfg(not(windows))]
        None
    }

    fn current_state(&self) -> EngineState {
        self.state
            .lock()
            .map(|state| *state)
            .unwrap_or(EngineState::Error)
    }

    fn set_state(&self, state: EngineState) {
        if let Ok(mut current) = self.state.lock() {
            *current = state;
        }
    }

    fn verify_core(&self, path: &Path) -> Result<(), String> {
        let mut verified = self
            .verified_core
            .lock()
            .map_err(|_| "Проверка VPN-ядра недоступна".to_string())?;
        if verified.as_deref() == Some(path) {
            return Ok(());
        }

        let mut actual = String::new();
        for attempt in 0..20 {
            let mut file = fs::File::open(path).map_err(|error| error.to_string())?;
            if file.metadata().map_err(|error| error.to_string())?.len() != EXPECTED_CORE_BYTES {
                if attempt < 19 {
                    std::thread::sleep(std::time::Duration::from_millis(250));
                    continue;
                }
                break;
            }
            let mut hasher = Sha256::new();
            let mut buffer = [0_u8; 64 * 1024];
            loop {
                let count = file.read(&mut buffer).map_err(|error| error.to_string())?;
                if count == 0 {
                    break;
                }
                hasher.update(&buffer[..count]);
            }
            actual = format!("{:X}", hasher.finalize());
            if actual == EXPECTED_CORE_SHA256 {
                *verified = Some(path.to_path_buf());
                return Ok(());
            }
            break;
        }
        Err(format!(
            "VPN-ядро повреждено или подменено (SHA-256: {actual})"
        ))
    }
}

struct ManagedCore {
    child: Child,
    #[cfg(windows)]
    _job: WindowsJob,
}

struct EphemeralFile(PathBuf);

impl Drop for EphemeralFile {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

#[cfg(windows)]
struct WindowsJob(isize);

#[cfg(windows)]
unsafe impl Send for WindowsJob {}

#[cfg(windows)]
impl Drop for WindowsJob {
    fn drop(&mut self) {
        use windows_sys::Win32::Foundation::CloseHandle;
        unsafe {
            let _ = CloseHandle(self.0 as _);
        }
    }
}

#[cfg(windows)]
fn assign_kill_on_close_job(child: &Child) -> Result<WindowsJob, String> {
    use std::mem::{size_of, zeroed};
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
        SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };

    unsafe {
        let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
        if job.is_null() {
            return Err(format!(
                "Не удалось создать контейнер процесса: {}",
                std::io::Error::last_os_error()
            ));
        }

        let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = zeroed();
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        if SetInformationJobObject(
            job,
            JobObjectExtendedLimitInformation,
            &limits as *const _ as _,
            size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        ) == 0
        {
            let error = std::io::Error::last_os_error();
            let _ = CloseHandle(job);
            return Err(format!("Не удалось настроить контейнер процесса: {error}"));
        }

        if AssignProcessToJobObject(job, child.as_raw_handle() as _) == 0 {
            let error = std::io::Error::last_os_error();
            let _ = CloseHandle(job);
            return Err(format!("Не удалось закрепить VPN-ядро: {error}"));
        }

        Ok(WindowsJob(job as isize))
    }
}

fn ensure_wintun(paths: &EnginePaths) -> Result<(), String> {
    let binary_dir = paths
        .core
        .parent()
        .ok_or_else(|| "Некорректный путь VPN-ядра".to_string())?;
    let destination = binary_dir.join("wintun.dll");
    if destination.is_file() {
        return Ok(());
    }

    for source in &paths.wintun_sources {
        if source.is_file() {
            fs::copy(source, &destination).map_err(|error| error.to_string())?;
            return Ok(());
        }
    }

    Err("Библиотека Wintun не найдена".to_string())
}

fn singbox_command(binary_path: &Path) -> Command {
    let mut command = Command::new(binary_path);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    command
}

#[cfg(windows)]
fn cleanup_stale_tun_default_route() {
    use std::os::windows::process::CommandExt;

    let _ = Command::new("route.exe")
        .args(["delete", "0.0.0.0", "mask", "0.0.0.0", "172.29.99.2"])
        .creation_flags(CREATE_NO_WINDOW)
        .status();
}

#[cfg(windows)]
fn configure_tun_dns() -> Result<(), String> {
    use std::{mem::zeroed, ptr, slice};
    use windows_sys::Win32::NetworkManagement::IpHelper::{
        FreeMibTable, GetIfTable2, SetInterfaceDnsSettings, DNS_INTERFACE_SETTINGS,
        DNS_INTERFACE_SETTINGS_VERSION1, DNS_SETTING_NAMESERVER, MIB_IF_ROW2, MIB_IF_TABLE2,
    };

    const ATTEMPTS: usize = 20;
    for attempt in 0..ATTEMPTS {
        let mut table: *mut MIB_IF_TABLE2 = ptr::null_mut();
        if unsafe { GetIfTable2(&mut table) } == 0 && !table.is_null() {
            let interface_guid = unsafe {
                slice::from_raw_parts((*table).Table.as_ptr(), (*table).NumEntries as usize)
                    .iter()
                    .find_map(|row: &MIB_IF_ROW2| {
                        let alias_length = row
                            .Alias
                            .iter()
                            .position(|character| *character == 0)
                            .unwrap_or(row.Alias.len());
                        let alias = String::from_utf16_lossy(&row.Alias[..alias_length]);
                        alias
                            .eq_ignore_ascii_case("warpy-tun")
                            .then_some(row.InterfaceGuid)
                    })
            };
            unsafe { FreeMibTable(table.cast()) };

            if let Some(interface_guid) = interface_guid {
                // DNS traffic to this address enters the TUN and is handled by
                // the hijack-dns route. The synthetic TUN peer is not a DNS listener.
                let mut name_server: Vec<u16> =
                    format!("{TUN_DNS_SERVER}\0").encode_utf16().collect();
                let mut settings: DNS_INTERFACE_SETTINGS = unsafe { zeroed() };
                settings.Version = DNS_INTERFACE_SETTINGS_VERSION1;
                settings.Flags = u64::from(DNS_SETTING_NAMESERVER);
                settings.NameServer = name_server.as_mut_ptr();
                let status = unsafe { SetInterfaceDnsSettings(interface_guid, &settings) };
                if status == 0 {
                    return Ok(());
                }
            }
        }
        if attempt + 1 < ATTEMPTS {
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
    }

    Err("Не удалось настроить DNS интерфейса Warpy".to_string())
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn tail_file(path: &Path) -> String {
    let Ok(mut file) = fs::File::open(path) else {
        return String::new();
    };
    let length = file.metadata().map(|metadata| metadata.len()).unwrap_or(0);
    let offset = length.saturating_sub(LOG_TAIL_BYTES);
    if file.seek(SeekFrom::Start(offset)).is_err() {
        return String::new();
    }
    let mut output = String::new();
    let _ = file.read_to_string(&mut output);
    output.trim().to_string()
}

fn last_log_message(log: &str) -> String {
    log.lines()
        .rev()
        .map(strip_ansi)
        .map(|line| line.trim().to_string())
        .find(|line| !line.is_empty())
        .unwrap_or_default()
}

fn strip_ansi(value: &str) -> String {
    let mut output = String::with_capacity(value.len());
    let mut characters = value.chars();
    while let Some(character) = characters.next() {
        if character == '\u{1b}' {
            if matches!(characters.next(), Some('[')) {
                for control in characters.by_ref() {
                    if ('@'..='~').contains(&control) {
                        break;
                    }
                }
            }
            continue;
        }
        if !character.is_control() || character == '\t' {
            output.push(character);
        }
    }
    output
}

pub(crate) fn rotate_log(path: &Path) -> Result<(), String> {
    if path.metadata().map(|metadata| metadata.len()).unwrap_or(0) < MAX_APP_LOG_BYTES {
        return Ok(());
    }

    let rotated = path.with_extension("log.1");
    let _ = fs::remove_file(&rotated);
    fs::rename(path, rotated).map_err(|error| error.to_string())
}

pub(crate) fn write_atomic(path: &Path, contents: &[u8]) -> Result<(), String> {
    let temp_path = path.with_extension(format!("tmp-{}", std::process::id()));
    {
        let mut file = fs::File::create(&temp_path).map_err(|error| error.to_string())?;
        file.write_all(contents)
            .map_err(|error| error.to_string())?;
        file.sync_all().map_err(|error| error.to_string())?;
    }

    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::{
            MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
        };

        let from: Vec<u16> = temp_path.as_os_str().encode_wide().chain(Some(0)).collect();
        let to: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        let result = unsafe {
            MoveFileExW(
                from.as_ptr(),
                to.as_ptr(),
                MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
            )
        };
        if result == 0 {
            let error = std::io::Error::last_os_error();
            let _ = fs::remove_file(&temp_path);
            return Err(error.to_string());
        }
    }

    #[cfg(not(windows))]
    fs::rename(&temp_path, path).map_err(|error| error.to_string())?;

    Ok(())
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct VpnNetworkStats {
    available: bool,
    received: u64,
    transmitted: u64,
}

#[cfg(windows)]
fn set_default_interface(config: &mut serde_json::Value, interface: &str) {
    let Some(route) = config
        .get_mut("route")
        .and_then(serde_json::Value::as_object_mut)
    else {
        return;
    };
    route.insert(
        "default_interface".to_string(),
        serde_json::Value::String(interface.to_string()),
    );
    route.insert(
        "auto_detect_interface".to_string(),
        serde_json::Value::Bool(false),
    );
}

#[cfg(windows)]
fn physical_default_interface() -> Option<String> {
    use std::{mem::zeroed, thread};
    use windows_sys::Win32::NetworkManagement::IpHelper::{
        GetBestInterface, GetIfEntry2, MIB_IF_ROW2,
    };

    let destination = u32::from_ne_bytes([1, 1, 1, 1]);
    for _ in 0..PHYSICAL_INTERFACE_RETRY_ATTEMPTS {
        let mut index = 0_u32;
        if unsafe { GetBestInterface(destination, &mut index) } != 0 || index == 0 {
            return None;
        }

        let mut interface: MIB_IF_ROW2 = unsafe { zeroed() };
        interface.InterfaceIndex = index;
        if unsafe { GetIfEntry2(&mut interface) } != 0 {
            return None;
        }
        let alias_length = interface
            .Alias
            .iter()
            .position(|character| *character == 0)
            .unwrap_or(interface.Alias.len());
        let alias = String::from_utf16_lossy(&interface.Alias[..alias_length]);
        if !alias.is_empty() && !is_tunnel_interface(interface.Type, &alias) {
            return Some(alias);
        }
        thread::sleep(PHYSICAL_INTERFACE_RETRY_DELAY);
    }
    None
}

#[cfg(windows)]
fn is_tunnel_interface(interface_type: u32, alias: &str) -> bool {
    use windows_sys::Win32::NetworkManagement::IpHelper::{IF_TYPE_PPP, IF_TYPE_TUNNEL};

    if interface_type == IF_TYPE_TUNNEL || interface_type == IF_TYPE_PPP {
        return true;
    }

    let alias = alias.to_ascii_lowercase();
    [
        "warpy-tun",
        "sing-tun",
        "wintun",
        "wireguard",
        "openvpn",
        "vpn",
        "tap",
    ]
    .iter()
    .any(|marker| alias.contains(marker))
}

#[cfg(windows)]
pub(crate) fn read_vpn_network_stats() -> Result<VpnNetworkStats, String> {
    use std::{ffi::c_void, ptr, slice};
    use windows_sys::Win32::NetworkManagement::IpHelper::{
        FreeMibTable, GetIfTable2, MIB_IF_ROW2, MIB_IF_TABLE2,
    };

    let mut table: *mut MIB_IF_TABLE2 = ptr::null_mut();
    let status = unsafe { GetIfTable2(&mut table) };
    if status != 0 {
        return Err(format!(
            "Не удалось получить сетевые счётчики Windows: {status}"
        ));
    }
    if table.is_null() {
        return Err("Windows вернула пустую таблицу сетевых интерфейсов".to_string());
    }

    let mut received = 0_u64;
    let mut transmitted = 0_u64;
    let mut available = false;
    unsafe {
        let rows: &[MIB_IF_ROW2] =
            slice::from_raw_parts((*table).Table.as_ptr(), (*table).NumEntries as usize);
        for row in rows {
            let alias_len = row
                .Alias
                .iter()
                .position(|character| *character == 0)
                .unwrap_or(row.Alias.len());
            let alias = String::from_utf16_lossy(&row.Alias[..alias_len]);
            if alias.eq_ignore_ascii_case("warpy-tun") {
                available = true;
                received = received.saturating_add(row.InOctets);
                transmitted = transmitted.saturating_add(row.OutOctets);
            }
        }
        FreeMibTable(table.cast::<c_void>());
    }

    Ok(VpnNetworkStats {
        available,
        received,
        transmitted,
    })
}

#[cfg(not(windows))]
pub(crate) fn read_vpn_network_stats() -> Result<VpnNetworkStats, String> {
    Ok(VpnNetworkStats {
        available: false,
        received: 0,
        transmitted: 0,
    })
}

#[cfg(test)]
mod log_tests {
    use super::{is_tunnel_interface, last_log_message, set_default_interface, EngineState};

    #[test]
    fn runtime_config_is_bound_to_the_physical_interface() {
        let mut config = serde_json::json!({
            "route": {
                "auto_detect_interface": true,
                "final": "proxy"
            }
        });

        set_default_interface(&mut config, "Ethernet");

        assert_eq!(config["route"]["default_interface"], "Ethernet");
        assert_eq!(config["route"]["auto_detect_interface"], false);
    }

    #[test]
    fn stale_sing_box_tunnel_is_never_selected_as_the_physical_interface() {
        assert!(is_tunnel_interface(131, "sing-tun Tunnel"));
        assert!(is_tunnel_interface(6, "Warpy-Tun"));
        assert!(is_tunnel_interface(6, "OpenVPN Data Channel Offload"));
        assert!(!is_tunnel_interface(
            6,
            "Realtek PCIe GbE Family Controller"
        ));
        assert!(!is_tunnel_interface(71, "Wi-Fi"));
    }

    #[test]
    fn extracts_clean_final_log_line() {
        let log = "\u{1b}[36mINFO\u{1b}[0m ready\n\u{1b}[31mFATAL\u{1b}[0m access denied\n";
        assert_eq!(last_log_message(log), "FATAL access denied");
    }

    #[test]
    fn engine_states_keep_stable_ipc_labels() {
        assert_eq!(EngineState::Starting.label(), "Starting");
        assert_eq!(EngineState::Validating.label(), "Validating");
        assert_eq!(EngineState::Connected.label(), "Connected");
        assert_eq!(EngineState::Recovering.label(), "Recovering");
        assert_eq!(EngineState::Stopping.label(), "Stopping");
    }
}
