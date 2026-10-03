#![cfg(windows)]

use serde_json::{json, Value};
use std::{ffi::c_void, net::TcpListener, os::windows::ffi::OsStrExt, ptr, time::Duration};
use windows_sys::Win32::Networking::WinHttp::{
    WinHttpCloseHandle, WinHttpConnect, WinHttpOpen, WinHttpOpenRequest, WinHttpQueryHeaders,
    WinHttpReceiveResponse, WinHttpSendRequest, WinHttpSetCredentials, WinHttpSetTimeouts,
    ERROR_WINHTTP_CANNOT_CONNECT, ERROR_WINHTTP_CONNECTION_ERROR, ERROR_WINHTTP_NAME_NOT_RESOLVED,
    ERROR_WINHTTP_SECURE_FAILURE, ERROR_WINHTTP_TIMEOUT, INTERNET_DEFAULT_HTTPS_PORT,
    WINHTTP_ACCESS_TYPE_NAMED_PROXY, WINHTTP_AUTH_SCHEME_BASIC, WINHTTP_AUTH_TARGET_PROXY,
    WINHTTP_FLAG_SECURE, WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_QUERY_STATUS_CODE,
};

const PROBE_TARGETS: [(&str, &str); 2] = [
    ("speed.cloudflare.com", "/__down?bytes=1024"),
    ("www.gstatic.com", "/generate_204"),
];
const PROBE_ATTEMPTS: usize = 3;
const PROBE_RETRY_DELAY: Duration = Duration::from_millis(600);

#[derive(Clone)]
pub(crate) struct TunnelProbe {
    address: String,
    password: String,
}

pub(crate) fn configure_tunnel_probe(config: &mut Value) -> Result<TunnelProbe, String> {
    let port = TcpListener::bind("127.0.0.1:0")
        .and_then(|listener| listener.local_addr())
        .map_err(|error| error.to_string())?
        .port();
    let password = crate::vpn_selector::random_secret()?;
    config
        .get_mut("inbounds")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| "VPN inbounds are missing".to_string())?
        .push(
            json!({"type": "mixed", "tag": "health-proxy-in", "listen": "127.0.0.1",
            "listen_port": port, "users": [{"username": "warpy", "password": password}]}),
        );
    let rules = config
        .get_mut("route")
        .and_then(|route| route.get_mut("rules"))
        .and_then(Value::as_array_mut)
        .ok_or_else(|| "VPN routing rules are missing".to_string())?;
    // The authenticated probe cannot use the direct fallback or another installed VPN.
    rules.insert(
        0,
        json!({
            "inbound": ["health-proxy-in"],
            "action": "route",
            "outbound": "proxy"
        }),
    );
    Ok(TunnelProbe {
        address: format!("127.0.0.1:{port}"),
        password,
    })
}

pub(crate) fn verify_tunnel(probe: &TunnelProbe) -> Result<(), String> {
    verify_tunnel_with_attempts(probe, PROBE_ATTEMPTS)
}

pub(crate) fn verify_tunnel_once(probe: &TunnelProbe) -> Result<(), String> {
    verify_tunnel_with_attempts(probe, 1)
}

fn verify_tunnel_with_attempts(probe: &TunnelProbe, attempts: usize) -> Result<(), String> {
    let mut last_error = "Нет обмена данными через VPN-туннель".to_string();
    for attempt in 0..attempts {
        match probe_once(probe) {
            Ok(()) => return Ok(()),
            Err(error) => last_error = error,
        }
        if attempt + 1 < attempts {
            std::thread::sleep(PROBE_RETRY_DELAY);
        }
    }
    Err(last_error)
}

fn probe_once(probe: &TunnelProbe) -> Result<(), String> {
    probe_targets(|server, path| probe_target(probe, server, path))
}

fn probe_targets(mut check: impl FnMut(&str, &str) -> Result<(), String>) -> Result<(), String> {
    let mut last_error = String::new();
    for (server, path) in PROBE_TARGETS {
        match check(server, path) {
            Ok(()) => return Ok(()),
            Err(error) => last_error = error,
        }
    }
    Err(last_error)
}

fn probe_target(probe: &TunnelProbe, server: &str, path: &str) -> Result<(), String> {
    let agent = wide("Warpy tunnel check");
    let proxy = wide(&probe.address);
    let session = WinHttpHandle::new(unsafe {
        WinHttpOpen(
            agent.as_ptr(),
            WINHTTP_ACCESS_TYPE_NAMED_PROXY,
            proxy.as_ptr(),
            ptr::null(),
            0,
        )
    })?;
    if unsafe { WinHttpSetTimeouts(session.raw(), 1_500, 2_000, 2_000, 3_000) } == 0 {
        return Err(winhttp_error("Не удалось настроить проверку туннеля"));
    }

    let server = wide(server);
    let connection = WinHttpHandle::new(unsafe {
        WinHttpConnect(
            session.raw(),
            server.as_ptr(),
            INTERNET_DEFAULT_HTTPS_PORT,
            0,
        )
    })?;
    let verb = wide("GET");
    let path = wide(path);
    let request = WinHttpHandle::new(unsafe {
        WinHttpOpenRequest(
            connection.raw(),
            verb.as_ptr(),
            path.as_ptr(),
            ptr::null(),
            ptr::null(),
            ptr::null(),
            WINHTTP_FLAG_SECURE,
        )
    })?;

    let username = wide("warpy");
    let password = wide(&probe.password);
    for attempt in 0..2 {
        if unsafe {
            WinHttpSetCredentials(
                request.raw(),
                WINHTTP_AUTH_TARGET_PROXY,
                WINHTTP_AUTH_SCHEME_BASIC,
                username.as_ptr(),
                password.as_ptr(),
                ptr::null_mut(),
            )
        } == 0
        {
            return Err(winhttp_error("Не удалось авторизовать проверку VPN"));
        }
        if unsafe { WinHttpSendRequest(request.raw(), ptr::null(), 0, ptr::null(), 0, 0, 0) } == 0 {
            return Err(winhttp_error("Не удалось отправить запрос через туннель"));
        }
        if unsafe { WinHttpReceiveResponse(request.raw(), ptr::null_mut()) } == 0 {
            return Err(winhttp_error("Сервер не ответил через VPN-туннель"));
        }

        let mut status_code = 0_u32;
        let mut status_size = std::mem::size_of::<u32>() as u32;
        let mut header_index = 0_u32;
        if unsafe {
            WinHttpQueryHeaders(
                request.raw(),
                WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                ptr::null(),
                (&mut status_code as *mut u32).cast::<c_void>(),
                &mut status_size,
                &mut header_index,
            )
        } == 0
        {
            return Err(winhttp_error("Не удалось проверить ответ VPN"));
        }
        if status_code == 407 && attempt == 0 {
            continue;
        }
        if !successful_probe_status(status_code) {
            return Err(format!("Проверка VPN вернула HTTP {status_code}"));
        }
        return Ok(());
    }
    Err("Проверка VPN не авторизована".to_string())
}

fn successful_probe_status(status: u32) -> bool {
    (200..300).contains(&status)
}

fn winhttp_error(context: &str) -> String {
    let error = std::io::Error::last_os_error();
    match error.raw_os_error().map(|code| code as u32) {
        Some(ERROR_WINHTTP_TIMEOUT) => "Сервер VPN не ответил вовремя".to_string(),
        Some(ERROR_WINHTTP_NAME_NOT_RESOLVED) => {
            "Не удалось разрешить DNS через VPN-туннель".to_string()
        }
        Some(ERROR_WINHTTP_CANNOT_CONNECT) | Some(ERROR_WINHTTP_CONNECTION_ERROR) => {
            "Сервер недоступен через VPN-туннель".to_string()
        }
        Some(ERROR_WINHTTP_SECURE_FAILURE) => {
            "Не удалось установить защищённое соединение через VPN".to_string()
        }
        _ => format!("{context}: {error}"),
    }
}

fn wide(value: &str) -> Vec<u16> {
    std::ffi::OsStr::new(value)
        .encode_wide()
        .chain(Some(0))
        .collect()
}

struct WinHttpHandle(*mut c_void);

impl WinHttpHandle {
    fn new(handle: *mut c_void) -> Result<Self, String> {
        if handle.is_null() {
            return Err(winhttp_error("Не удалось начать проверку VPN-туннеля"));
        }
        Ok(Self(handle))
    }

    fn raw(&self) -> *mut c_void {
        self.0
    }
}

impl Drop for WinHttpHandle {
    fn drop(&mut self) {
        unsafe {
            let _ = WinHttpCloseHandle(self.0);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{configure_tunnel_probe, successful_probe_status};
    use serde_json::json;

    #[test]
    fn one_blocked_endpoint_does_not_mark_a_working_tunnel_down() {
        let mut visited = Vec::new();
        let result = super::probe_targets(|server, _| {
            visited.push(server.to_string());
            if server == "speed.cloudflare.com" {
                Err("HTTP 504".into())
            } else {
                Ok(())
            }
        });
        assert!(result.is_ok());
        assert_eq!(visited, ["speed.cloudflare.com", "www.gstatic.com"]);
        let mut visited = Vec::new();
        assert!(super::probe_targets(|server, _| {
            visited.push(server.to_string());
            Ok(())
        })
        .is_ok());
        assert_eq!(visited, ["speed.cloudflare.com"]);
        assert!(super::probe_targets(|_, _| Err("unreachable".into())).is_err());
    }

    #[test]
    #[ignore = "requires bundled sing-box and HTTPS connectivity; never creates a TUN"]
    fn isolated_core_probe_cannot_fall_back_to_direct() {
        use std::{
            fs,
            net::TcpStream,
            os::windows::process::CommandExt,
            process::{Command, Stdio},
            time::Duration,
        };
        let binary = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("bin/sing-box-x86_64-pc-windows-msvc.exe");
        for reachable in [true, false] {
            let mut config = json!({"log": {"level": "error"}, "inbounds": [],
                "outbounds": [{"type": "direct", "tag": "proxy"}, {"type": "direct", "tag": "direct"}],
                "route": {"final": "direct", "rules": []}});
            if !reachable {
                config["outbounds"][0] = json!({"type": "socks", "tag": "proxy",
                    "server": "127.0.0.1", "server_port": 9, "connect_timeout": "500ms"});
            }
            let probe = configure_tunnel_probe(&mut config).unwrap();
            let path =
                std::env::temp_dir().join(format!("warpy-probe-test-{}.json", probe.password));
            fs::write(&path, config.to_string()).unwrap();
            let mut child = Command::new(&binary)
                .args(["run", "-c"])
                .arg(&path)
                .creation_flags(0x08000000)
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .unwrap();
            let outcome = std::panic::catch_unwind(|| {
                for _ in 0..30 {
                    if TcpStream::connect(&probe.address).is_ok() {
                        break;
                    }
                    std::thread::sleep(Duration::from_millis(100));
                }
                let result = super::verify_tunnel_once(&probe);
                assert_eq!(result.is_ok(), reachable, "probe result: {result:?}");
                if reachable {
                    let mut wrong = probe.clone();
                    wrong.password = "wrong".to_string();
                    assert!(super::verify_tunnel_once(&wrong).is_err());
                }
            });
            let _ = child.kill();
            let _ = child.wait();
            let _ = fs::remove_file(&path);
            if let Err(error) = outcome {
                std::panic::resume_unwind(error);
            }
        }
    }

    #[test]
    fn verification_uses_authenticated_proxy_before_any_split_rule() {
        let mut config = json!({"inbounds": [], "route": {"final": "direct", "rules": [
            {"inbound": ["tun-in"], "action": "sniff"},
            {"process_name": ["warpy-desktop.exe"], "action": "route", "outbound": "direct"},
            {"domain_suffix": ["gstatic.com"], "action": "route", "outbound": "direct"}
        ]}});
        let probe = configure_tunnel_probe(&mut config).unwrap();
        let rule = &config["route"]["rules"][0];
        assert_eq!(rule["inbound"], json!(["health-proxy-in"]));
        assert_eq!(rule["outbound"], "proxy");
        assert_eq!(config["route"]["rules"][2]["outbound"], "direct");
        let inbound = &config["inbounds"][0];
        assert_eq!(inbound["listen"], "127.0.0.1");
        assert_eq!(inbound["users"][0]["password"], probe.password);
        assert!(probe.password.len() >= 32);
    }

    #[test]
    fn accepts_generate_204_without_requiring_a_response_body() {
        assert!(successful_probe_status(204));
        assert!(successful_probe_status(200));
        assert!(!successful_probe_status(503));
    }
}
