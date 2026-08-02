#![cfg(windows)]

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    ffi::{c_void, OsStr},
    fs,
    os::windows::ffi::OsStrExt,
    path::{Path, PathBuf},
    ptr,
    sync::atomic::{AtomicBool, Ordering},
    thread,
    time::{Duration, Instant},
};
use windows_service::{
    service::ServiceAccess,
    service_manager::{ServiceManager, ServiceManagerAccess},
};
use windows_sys::Win32::{
    Foundation::{
        CloseHandle, LocalFree, ERROR_NO_DATA, ERROR_PIPE_CONNECTED, ERROR_PIPE_LISTENING,
        GENERIC_READ, GENERIC_WRITE, HANDLE, INVALID_HANDLE_VALUE,
    },
    Security::{
        Authorization::{ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1},
        PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES,
    },
    Storage::FileSystem::{
        CreateFileW, ReadFile, WriteFile, FILE_ATTRIBUTE_NORMAL, OPEN_EXISTING, PIPE_ACCESS_DUPLEX,
    },
    System::{
        Pipes::{
            ConnectNamedPipe, CreateNamedPipeW, DisconnectNamedPipe, GetNamedPipeClientProcessId,
            GetNamedPipeServerProcessId, SetNamedPipeHandleState, WaitNamedPipeW, PIPE_NOWAIT,
            PIPE_READMODE_BYTE, PIPE_REJECT_REMOTE_CLIENTS, PIPE_TYPE_BYTE,
        },
        Threading::{OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION},
    },
};

const PIPE_NAME: &str = r"\\.\pipe\WarpyVpnService.v1";
const SERVICE_NAME: &str = "WarpyVpnService";
const PIPE_BUFFER_BYTES: u32 = 64 * 1024;
const MAX_FRAME_BYTES: usize = 1024 * 1024;
const CONNECT_TIMEOUT_MS: u32 = 1_000;
const IO_TIMEOUT: Duration = Duration::from_secs(3);
const START_RESPONSE_TIMEOUT: Duration = Duration::from_secs(40);
const POLL_INTERVAL: Duration = Duration::from_millis(4);
const RESPONSE_ACK: u8 = 0xA5;

#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "command", rename_all = "camelCase")]
pub(crate) enum VpnRequest {
    Status,
    RuntimeSnapshot,
    StartedAt,
    Diagnostics,
    NetworkStats,
    Health,
    KillSwitchStatus,
    AttachUi {
        process_id: u32,
    },
    SetAutostart {
        enabled: bool,
    },
    SetAutoMode {
        enabled: bool,
    },
    SetPreferredOutbound {
        outbound: String,
    },
    ForgetOutbound {
        outbound: String,
    },
    Start {
        config: String,
        kill_switch: bool,
        auto_mode: bool,
    },
    SwitchOutbound {
        outbound: String,
    },
    Stop,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "status", content = "data", rename_all = "camelCase")]
enum VpnResponse {
    Ok(Value),
    Error(String),
}

pub(crate) fn call(request: &VpnRequest) -> Result<Result<Value, String>, String> {
    call_named(PIPE_NAME, request, true)
}

fn call_named(
    pipe_name: &str,
    request: &VpnRequest,
    verify_service: bool,
) -> Result<Result<Value, String>, String> {
    let pipe_name = wide(pipe_name);
    let deadline = Instant::now() + Duration::from_millis(CONNECT_TIMEOUT_MS as u64);
    let pipe = loop {
        let _ = unsafe { WaitNamedPipeW(pipe_name.as_ptr(), 100) };
        let handle = unsafe {
            CreateFileW(
                pipe_name.as_ptr(),
                GENERIC_READ | GENERIC_WRITE,
                0,
                ptr::null(),
                OPEN_EXISTING,
                FILE_ATTRIBUTE_NORMAL,
                ptr::null_mut(),
            )
        };
        if let Ok(pipe) = OwnedHandle::new(handle) {
            break pipe;
        }
        if Instant::now() >= deadline {
            return Err(format!(
                "Служба VPN недоступна: {}",
                std::io::Error::last_os_error()
            ));
        }
        thread::sleep(Duration::from_millis(10));
    };
    set_nonblocking(pipe.raw())?;
    if verify_service {
        verify_service_process(pipe.raw())?;
    } else {
        verify_peer_process_image(pipe.raw(), false)?;
    }

    let request_bytes = serde_json::to_vec(request).map_err(|error| error.to_string())?;
    write_frame(pipe.raw(), &request_bytes, IO_TIMEOUT)?;
    let response_timeout = response_timeout(request);
    let response_bytes = read_frame(pipe.raw(), response_timeout)?;
    write_all(pipe.raw(), &[RESPONSE_ACK], IO_TIMEOUT)?;
    let response: VpnResponse =
        serde_json::from_slice(&response_bytes).map_err(|error| error.to_string())?;

    Ok(match response {
        VpnResponse::Ok(value) => Ok(value),
        VpnResponse::Error(error) => Err(error),
    })
}

fn response_timeout(request: &VpnRequest) -> Duration {
    match request {
        VpnRequest::Start { .. } => START_RESPONSE_TIMEOUT,
        VpnRequest::SwitchOutbound { .. }
        | VpnRequest::SetPreferredOutbound { .. }
        | VpnRequest::ForgetOutbound { .. } => Duration::from_secs(8),
        VpnRequest::Stop | VpnRequest::SetAutostart { .. } | VpnRequest::SetAutoMode { .. } => {
            Duration::from_secs(10)
        }
        _ => IO_TIMEOUT,
    }
}

pub(crate) fn run_server<F, E>(
    running: &AtomicBool,
    handler: F,
    on_client_error: E,
) -> Result<(), String>
where
    F: Fn(VpnRequest) -> Result<Value, String>,
    E: Fn(&str),
{
    run_server_named(PIPE_NAME, running, handler, on_client_error)
}

fn run_server_named<F, E>(
    pipe_name: &str,
    running: &AtomicBool,
    handler: F,
    on_client_error: E,
) -> Result<(), String>
where
    F: Fn(VpnRequest) -> Result<Value, String>,
    E: Fn(&str),
{
    let security = PipeSecurity::new()?;
    let pipe_name = wide(pipe_name);

    while running.load(Ordering::Acquire) {
        let attributes = security.attributes();
        let handle = unsafe {
            CreateNamedPipeW(
                pipe_name.as_ptr(),
                PIPE_ACCESS_DUPLEX,
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_NOWAIT | PIPE_REJECT_REMOTE_CLIENTS,
                1,
                PIPE_BUFFER_BYTES,
                PIPE_BUFFER_BYTES,
                0,
                &attributes,
            )
        };
        let pipe = OwnedHandle::new(handle)?;

        if !wait_for_client(pipe.raw(), running)? {
            continue;
        }
        if !running.load(Ordering::Acquire) {
            unsafe {
                let _ = DisconnectNamedPipe(pipe.raw());
            }
            break;
        }

        let client_result = (|| {
            verify_peer_process_image(pipe.raw(), true)?;
            let request_bytes = read_frame(pipe.raw(), IO_TIMEOUT)?;
            let response = match serde_json::from_slice::<VpnRequest>(&request_bytes) {
                Ok(request) => match handler(request) {
                    Ok(value) => VpnResponse::Ok(value),
                    Err(error) => VpnResponse::Error(error),
                },
                Err(error) => VpnResponse::Error(format!("Некорректный запрос: {error}")),
            };
            let response_bytes =
                serde_json::to_vec(&response).map_err(|error| error.to_string())?;
            write_frame(pipe.raw(), &response_bytes, IO_TIMEOUT)?;
            let mut acknowledgement = [0_u8; 1];
            read_exact(pipe.raw(), &mut acknowledgement, IO_TIMEOUT)?;
            if acknowledgement[0] != RESPONSE_ACK {
                return Err("Некорректное подтверждение IPC-ответа".to_string());
            }
            Ok(())
        })();
        #[cfg(test)]
        if let Err(error) = &client_result {
            eprintln!("IPC server rejected request: {error}");
        }
        if let Err(error) = &client_result {
            on_client_error(error);
        }

        unsafe {
            let _ = DisconnectNamedPipe(pipe.raw());
        }
    }

    Ok(())
}

fn wait_for_client(handle: HANDLE, running: &AtomicBool) -> Result<bool, String> {
    while running.load(Ordering::Acquire) {
        if unsafe { ConnectNamedPipe(handle, ptr::null_mut()) } != 0 {
            return Ok(true);
        }

        match std::io::Error::last_os_error().raw_os_error() {
            Some(code) if code == ERROR_PIPE_CONNECTED as i32 => return Ok(true),
            Some(code) if code == ERROR_PIPE_LISTENING as i32 => {
                thread::sleep(Duration::from_millis(20));
            }
            Some(code) if code == ERROR_NO_DATA as i32 => return Ok(false),
            _ => {
                return Err(format!(
                    "Не удалось принять IPC-подключение: {}",
                    std::io::Error::last_os_error()
                ));
            }
        }
    }
    Ok(false)
}

fn set_nonblocking(handle: HANDLE) -> Result<(), String> {
    let mode = PIPE_READMODE_BYTE | PIPE_NOWAIT;
    if unsafe { SetNamedPipeHandleState(handle, &mode, ptr::null(), ptr::null()) } == 0 {
        return Err(format!(
            "Не удалось настроить IPC: {}",
            std::io::Error::last_os_error()
        ));
    }
    Ok(())
}

fn read_frame(handle: HANDLE, timeout: Duration) -> Result<Vec<u8>, String> {
    let mut length = [0_u8; 4];
    read_exact(handle, &mut length, timeout)?;
    let length = u32::from_le_bytes(length) as usize;
    if length == 0 || length > MAX_FRAME_BYTES {
        return Err("Некорректный размер IPC-сообщения".to_string());
    }

    let mut payload = vec![0_u8; length];
    read_exact(handle, &mut payload, timeout)?;
    Ok(payload)
}

fn write_frame(handle: HANDLE, payload: &[u8], timeout: Duration) -> Result<(), String> {
    if payload.is_empty() || payload.len() > MAX_FRAME_BYTES {
        return Err("Некорректный размер IPC-сообщения".to_string());
    }
    write_all(handle, &(payload.len() as u32).to_le_bytes(), timeout)?;
    write_all(handle, payload, timeout)
}

fn read_exact(handle: HANDLE, buffer: &mut [u8], timeout: Duration) -> Result<(), String> {
    let deadline = Instant::now() + timeout;
    let mut offset = 0;
    while offset < buffer.len() {
        let mut read = 0_u32;
        let result = unsafe {
            ReadFile(
                handle,
                buffer[offset..].as_mut_ptr(),
                (buffer.len() - offset) as u32,
                &mut read,
                ptr::null_mut(),
            )
        };
        if result != 0 && read > 0 {
            offset += read as usize;
            continue;
        }
        if result == 0
            && std::io::Error::last_os_error().raw_os_error() != Some(ERROR_NO_DATA as i32)
        {
            return Err(format!(
                "Ошибка чтения IPC: {}",
                std::io::Error::last_os_error()
            ));
        }
        if Instant::now() >= deadline {
            return Err("Истекло время ожидания ответа службы VPN".to_string());
        }
        thread::sleep(POLL_INTERVAL);
    }
    Ok(())
}

fn write_all(handle: HANDLE, buffer: &[u8], timeout: Duration) -> Result<(), String> {
    let deadline = Instant::now() + timeout;
    let mut offset = 0;
    while offset < buffer.len() {
        let mut written = 0_u32;
        let result = unsafe {
            WriteFile(
                handle,
                buffer[offset..].as_ptr(),
                (buffer.len() - offset) as u32,
                &mut written,
                ptr::null_mut(),
            )
        };
        if result != 0 && written > 0 {
            offset += written as usize;
            continue;
        }
        if result == 0
            && std::io::Error::last_os_error().raw_os_error() != Some(ERROR_NO_DATA as i32)
        {
            return Err(format!(
                "Ошибка записи IPC: {}",
                std::io::Error::last_os_error()
            ));
        }
        if Instant::now() >= deadline {
            return Err("Истекло время передачи запроса службе VPN".to_string());
        }
        thread::sleep(POLL_INTERVAL);
    }
    Ok(())
}

fn named_pipe_process_id(pipe: HANDLE, client: bool) -> Result<u32, String> {
    let mut process_id = 0_u32;
    let result = unsafe {
        if client {
            GetNamedPipeClientProcessId(pipe, &mut process_id)
        } else {
            GetNamedPipeServerProcessId(pipe, &mut process_id)
        }
    };
    if result == 0 || process_id == 0 {
        return Err("Не удалось проверить IPC-процесс".to_string());
    }
    Ok(process_id)
}

fn verify_service_process(pipe: HANDLE) -> Result<(), String> {
    let pipe_process_id = named_pipe_process_id(pipe, false)?;
    let manager = ServiceManager::local_computer(None::<&str>, ServiceManagerAccess::CONNECT)
        .map_err(|error| format!("Не удалось проверить службу VPN: {error}"))?;
    let service = manager
        .open_service(SERVICE_NAME, ServiceAccess::QUERY_STATUS)
        .map_err(|error| format!("Не удалось проверить службу VPN: {error}"))?;
    let service_process_id = service
        .query_status()
        .map_err(|error| format!("Не удалось проверить службу VPN: {error}"))?
        .process_id
        .ok_or_else(|| "Служба VPN не запущена".to_string())?;
    if pipe_process_id != service_process_id {
        return Err("IPC-канал не принадлежит службе Warpy".to_string());
    }
    Ok(())
}

fn verify_peer_process_image(pipe: HANDLE, client: bool) -> Result<(), String> {
    let process_id = named_pipe_process_id(pipe, client)?;

    let actual = process_image_path(process_id)?;
    let expected = std::env::current_exe().map_err(|error| error.to_string())?;
    if !same_path(&actual, &expected) {
        return Err(format!(
            "IPC-процесс не совпадает: ожидался `{}`, получен `{}`",
            normalized_path(&expected),
            normalized_path(&actual)
        ));
    }
    Ok(())
}

fn process_image_path(process_id: u32) -> Result<PathBuf, String> {
    let process = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, process_id) };
    let process = OwnedHandle::new(process)?;
    let mut buffer = vec![0_u16; 32_768];
    let mut length = buffer.len() as u32;
    if unsafe { QueryFullProcessImageNameW(process.raw(), 0, buffer.as_mut_ptr(), &mut length) }
        == 0
    {
        return Err(format!(
            "Не удалось проверить IPC-процесс: {}",
            std::io::Error::last_os_error()
        ));
    }
    buffer.truncate(length as usize);
    Ok(PathBuf::from(String::from_utf16_lossy(&buffer)))
}

fn same_path(left: &Path, right: &Path) -> bool {
    normalized_path(left).eq_ignore_ascii_case(&normalized_path(right))
}

fn normalized_path(path: &Path) -> String {
    fs::canonicalize(path)
        .unwrap_or_else(|_| path.to_path_buf())
        .to_string_lossy()
        .trim_start_matches(r"\\?\")
        .to_string()
}

fn wide(value: &str) -> Vec<u16> {
    OsStr::new(value).encode_wide().chain(Some(0)).collect()
}

struct OwnedHandle(HANDLE);

impl OwnedHandle {
    fn new(handle: HANDLE) -> Result<Self, String> {
        if handle.is_null() || handle == INVALID_HANDLE_VALUE {
            return Err(std::io::Error::last_os_error().to_string());
        }
        Ok(Self(handle))
    }

    fn raw(&self) -> HANDLE {
        self.0
    }
}

impl Drop for OwnedHandle {
    fn drop(&mut self) {
        unsafe {
            let _ = CloseHandle(self.0);
        }
    }
}

struct PipeSecurity(PSECURITY_DESCRIPTOR);

impl PipeSecurity {
    fn new() -> Result<Self, String> {
        let descriptor_text = wide("D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;IU)");
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
                "Не удалось защитить IPC: {}",
                std::io::Error::last_os_error()
            ));
        }
        Ok(Self(descriptor))
    }

    fn attributes(&self) -> SECURITY_ATTRIBUTES {
        SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: self.0.cast::<c_void>(),
            bInheritHandle: 0,
        }
    }
}

impl Drop for PipeSecurity {
    fn drop(&mut self) {
        unsafe {
            let _ = LocalFree(self.0 as _);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{
        call_named, normalized_path, response_timeout, run_server_named, VpnRequest,
        START_RESPONSE_TIMEOUT,
    };
    use serde_json::json;
    use std::{
        path::Path,
        sync::{
            atomic::{AtomicBool, Ordering},
            Arc,
        },
        thread,
    };

    #[test]
    fn request_round_trip_preserves_config() {
        let request = VpnRequest::Start {
            config: r#"{"inbounds":[],"outbounds":[]}"#.to_string(),
            kill_switch: true,
            auto_mode: true,
        };
        let encoded = serde_json::to_vec(&request).expect("serialize request");
        let decoded: VpnRequest = serde_json::from_slice(&encoded).expect("deserialize request");
        match decoded {
            VpnRequest::Start {
                config,
                kill_switch,
                auto_mode,
            } => {
                assert!(config.contains("outbounds"));
                assert!(kill_switch);
                assert!(auto_mode);
            }
            _ => panic!("wrong request kind"),
        }
    }

    #[test]
    fn start_waits_for_the_full_tunnel_validation_budget() {
        let request = VpnRequest::Start {
            config: "{}".to_string(),
            kill_switch: false,
            auto_mode: false,
        };
        assert_eq!(response_timeout(&request), START_RESPONSE_TIMEOUT);
        assert!(response_timeout(&request) > std::time::Duration::from_secs(30));
    }

    #[test]
    fn autostart_request_round_trip_preserves_policy() {
        let encoded = serde_json::to_vec(&VpnRequest::SetAutostart { enabled: true })
            .expect("serialize request");
        let decoded: VpnRequest = serde_json::from_slice(&encoded).expect("deserialize request");
        assert!(matches!(
            decoded,
            VpnRequest::SetAutostart { enabled: true }
        ));
    }

    #[test]
    fn auto_mode_request_round_trip_preserves_policy() {
        let encoded = serde_json::to_vec(&VpnRequest::SetAutoMode { enabled: true })
            .expect("serialize request");
        let decoded: VpnRequest = serde_json::from_slice(&encoded).expect("deserialize request");
        assert!(matches!(decoded, VpnRequest::SetAutoMode { enabled: true }));
    }

    #[test]
    fn preferred_outbound_request_round_trip_preserves_target() {
        let encoded = serde_json::to_vec(&VpnRequest::SetPreferredOutbound {
            outbound: "profile-3".to_string(),
        })
        .expect("serialize request");
        let decoded: VpnRequest = serde_json::from_slice(&encoded).expect("deserialize request");
        assert!(matches!(
            decoded,
            VpnRequest::SetPreferredOutbound { outbound } if outbound == "profile-3"
        ));
    }

    #[test]
    fn selector_request_round_trip_preserves_outbound() {
        let encoded = serde_json::to_vec(&VpnRequest::SwitchOutbound {
            outbound: "profile-2".to_string(),
        })
        .expect("serialize request");
        let decoded: VpnRequest = serde_json::from_slice(&encoded).expect("deserialize request");
        assert!(matches!(
            decoded,
            VpnRequest::SwitchOutbound { outbound } if outbound == "profile-2"
        ));
    }

    #[test]
    fn health_request_round_trip_keeps_command() {
        let encoded = serde_json::to_vec(&VpnRequest::Health).expect("serialize request");
        let decoded: VpnRequest = serde_json::from_slice(&encoded).expect("deserialize request");
        assert!(matches!(decoded, VpnRequest::Health));
    }

    #[test]
    fn diagnostics_request_round_trip_keeps_command() {
        let encoded = serde_json::to_vec(&VpnRequest::Diagnostics).expect("serialize request");
        let decoded: VpnRequest = serde_json::from_slice(&encoded).expect("deserialize request");
        assert!(matches!(decoded, VpnRequest::Diagnostics));
    }

    #[test]
    fn runtime_snapshot_request_round_trip_keeps_command() {
        let encoded = serde_json::to_vec(&VpnRequest::RuntimeSnapshot).expect("serialize request");
        let decoded: VpnRequest = serde_json::from_slice(&encoded).expect("deserialize request");
        assert!(matches!(decoded, VpnRequest::RuntimeSnapshot));
    }

    #[test]
    fn extended_path_prefix_is_ignored() {
        assert_eq!(
            normalized_path(Path::new(r"\\?\C:\Program Files\Warpy\warpy-desktop.exe")),
            r"C:\Program Files\Warpy\warpy-desktop.exe"
        );
    }

    #[test]
    fn named_pipe_round_trip_authenticates_same_binary() {
        let running = Arc::new(AtomicBool::new(true));
        let server_running = Arc::clone(&running);
        let pipe_name = format!(r"\\.\pipe\WarpyVpnService.test.{}", std::process::id());
        let server_pipe_name = pipe_name.clone();
        let server = thread::spawn(move || {
            run_server_named(
                &server_pipe_name,
                &server_running,
                |request| {
                    server_running.store(false, Ordering::Release);
                    match request {
                        VpnRequest::Status => Ok(json!("Connected")),
                        _ => Err("unexpected request".to_string()),
                    }
                },
                |_| {},
            )
        });

        let response = call_named(&pipe_name, &VpnRequest::Status, false);
        if response.is_err() {
            running.store(false, Ordering::Release);
        }
        let server_result = server.join().expect("join IPC server");
        server_result.expect("serve IPC request");
        assert_eq!(
            response
                .expect("call IPC server")
                .expect("service response"),
            json!("Connected")
        );
    }
}
