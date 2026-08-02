#![cfg(windows)]

use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    fmt,
    io::{Read, Write},
    net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream},
    ptr,
    time::Duration,
};
use windows_sys::Win32::Security::Cryptography::{
    BCryptGenRandom, BCRYPT_USE_SYSTEM_PREFERRED_RNG,
};

const SELECTOR_TAG: &str = "proxy";
const CONTROLLER_TIMEOUT: Duration = Duration::from_secs(2);
const PROBE_TIMEOUT_MS: u64 = 4_000;
const PROBE_RESPONSE_TIMEOUT: Duration = Duration::from_secs(5);
const PROBE_URL: &str = "https%3A%2F%2Fwww.google.com%2Fgenerate_204";
const MAX_RESPONSE_BYTES: usize = 64 * 1024;

#[derive(Clone, Debug)]
pub(crate) struct SelectorControl {
    address: SocketAddr,
    secret: String,
    selector: String,
    outbounds: BTreeSet<String>,
    outbound_ids: BTreeMap<String, String>,
    selected: String,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum SelectorSwitchError {
    Rejected(String),
    RolledBack(String),
    RollbackFailed(String),
}

impl SelectorSwitchError {
    pub(crate) fn rollback_failed(&self) -> bool {
        matches!(self, Self::RollbackFailed(_))
    }
}

impl fmt::Display for SelectorSwitchError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Rejected(message) | Self::RolledBack(message) | Self::RollbackFailed(message) => {
                formatter.write_str(message)
            }
        }
    }
}

impl SelectorControl {
    pub(crate) fn selected(&self) -> &str {
        &self.selected
    }

    pub(crate) fn supports(&self, outbound: &str) -> bool {
        self.outbounds.contains(outbound)
    }

    pub(crate) fn health_targets(&self) -> Vec<(String, String)> {
        self.outbounds
            .iter()
            .filter_map(|outbound| {
                self.outbound_ids
                    .get(outbound)
                    .map(|profile_id| (outbound.clone(), profile_id.clone()))
            })
            .collect()
    }

    pub(crate) fn profile_id(&self, outbound: &str) -> Option<&str> {
        self.outbound_ids.get(outbound).map(String::as_str)
    }

    pub(crate) fn forget(&mut self, outbound: &str) -> Result<(), String> {
        if !self.outbounds.remove(outbound) {
            return Err("SELECTOR_UNKNOWN_OUTBOUND".to_string());
        }
        self.outbound_ids.remove(outbound);
        Ok(())
    }

    pub(crate) fn same_runtime(&self, other: &Self) -> bool {
        self.address == other.address && self.secret == other.secret
    }

    pub(crate) fn probe_outbound(&self, outbound: &str) -> Result<u64, String> {
        if !self.supports(outbound) && outbound != self.selector {
            return Err("SELECTOR_UNKNOWN_OUTBOUND".to_string());
        }
        verify_outbound(self, outbound)
    }

    pub(crate) fn select(&mut self, outbound: &str) -> Result<(), String> {
        if !self.supports(outbound) {
            return Err("SELECTOR_UNKNOWN_OUTBOUND".to_string());
        }
        send_selection(self, outbound)?;
        self.selected = outbound.to_string();
        Ok(())
    }

    pub(crate) fn select_verified(&mut self, outbound: &str) -> Result<(), SelectorSwitchError> {
        if self.selected() == outbound {
            return Ok(());
        }
        if !self.supports(outbound) {
            return Err(SelectorSwitchError::Rejected(
                "SELECTOR_UNKNOWN_OUTBOUND".to_string(),
            ));
        }

        self.probe_outbound(outbound).map_err(|error| {
            SelectorSwitchError::Rejected(format!("Новый сервер не передает данные: {error}"))
        })?;

        let previous = self.selected().to_string();
        self.select(outbound)
            .map_err(SelectorSwitchError::Rejected)?;
        let selector = self.selector.clone();
        if let Err(error) = self.probe_outbound(&selector) {
            let rollback = self.select(&previous);
            return Err(match rollback {
                Ok(()) => SelectorSwitchError::RolledBack(format!(
                    "Новый сервер не передает данные: {error}"
                )),
                Err(rollback_error) => SelectorSwitchError::RollbackFailed(format!(
                    "Новый сервер не передает данные, откат не удался: {error}; {rollback_error}"
                )),
            });
        }
        Ok(())
    }
}

pub(crate) fn update_selected_outbound(config: &str, outbound: &str) -> Result<String, String> {
    let mut config: Value = serde_json::from_str(config)
        .map_err(|error| format!("Некорректная конфигурация: {error}"))?;
    let selector = config
        .get_mut("outbounds")
        .and_then(Value::as_array_mut)
        .and_then(|outbounds| {
            outbounds.iter_mut().find(|candidate| {
                candidate.get("type").and_then(Value::as_str) == Some("selector")
                    && candidate.get("tag").and_then(Value::as_str) == Some(SELECTOR_TAG)
            })
        })
        .ok_or_else(|| "SELECTOR_NOT_AVAILABLE".to_string())?;
    let supported = selector
        .get("outbounds")
        .and_then(Value::as_array)
        .is_some_and(|outbounds| {
            outbounds
                .iter()
                .any(|value| value.as_str() == Some(outbound))
        });
    if !supported {
        return Err("SELECTOR_UNKNOWN_OUTBOUND".to_string());
    }
    selector
        .as_object_mut()
        .ok_or_else(|| "SELECTOR_NOT_AVAILABLE".to_string())?
        .insert("default".to_string(), Value::String(outbound.to_string()));
    Ok(config.to_string())
}

pub(crate) fn prepare_config(config: &str) -> Result<(String, Option<SelectorControl>), String> {
    let mut config: Value = serde_json::from_str(config)
        .map_err(|error| format!("Некорректная конфигурация: {error}"))?;
    let Some(selector) = config
        .get("outbounds")
        .and_then(Value::as_array)
        .and_then(|outbounds| {
            outbounds.iter().find(|outbound| {
                outbound.get("type").and_then(Value::as_str) == Some("selector")
                    && outbound.get("tag").and_then(Value::as_str) == Some(SELECTOR_TAG)
            })
        })
    else {
        return Ok((config.to_string(), None));
    };

    let outbounds = selector
        .get("outbounds")
        .and_then(Value::as_array)
        .map(|values| {
            values
                .iter()
                .filter_map(Value::as_str)
                .map(str::to_string)
                .collect::<BTreeSet<_>>()
        })
        .filter(|values| !values.is_empty())
        .ok_or_else(|| "Selector VPN не содержит серверов".to_string())?;
    let selected = selector
        .get("default")
        .and_then(Value::as_str)
        .filter(|selected| outbounds.contains(*selected))
        .or_else(|| outbounds.first().map(String::as_str))
        .ok_or_else(|| "Selector VPN не содержит активного сервера".to_string())?
        .to_string();
    let outbound_ids = build_outbound_ids(&config, &outbounds)?;

    let port = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .and_then(|listener| listener.local_addr())
        .map_err(|error| format!("Не удалось подготовить управление VPN: {error}"))?
        .port();
    let address = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    let secret = random_secret()?;

    let root = config
        .as_object_mut()
        .ok_or_else(|| "Конфигурация VPN должна быть объектом".to_string())?;
    let experimental = root
        .entry("experimental")
        .or_insert_with(|| json!({}))
        .as_object_mut()
        .ok_or_else(|| "Некорректный раздел experimental".to_string())?;
    experimental.insert(
        "clash_api".to_string(),
        json!({
            "external_controller": address.to_string(),
            "secret": secret,
        }),
    );

    Ok((
        config.to_string(),
        Some(SelectorControl {
            address,
            secret,
            selector: SELECTOR_TAG.to_string(),
            outbounds,
            outbound_ids,
            selected,
        }),
    ))
}

fn build_outbound_ids(
    config: &Value,
    outbounds: &BTreeSet<String>,
) -> Result<BTreeMap<String, String>, String> {
    let configured = config
        .get("outbounds")
        .and_then(Value::as_array)
        .ok_or_else(|| "Selector VPN не содержит серверов".to_string())?;
    outbounds
        .iter()
        .map(|tag| {
            let outbound = configured
                .iter()
                .find(|candidate| candidate.get("tag").and_then(Value::as_str) == Some(tag))
                .ok_or_else(|| format!("Selector VPN не содержит сервер {tag}"))?;
            Ok((tag.clone(), outbound_fingerprint(outbound)?))
        })
        .collect()
}

fn outbound_fingerprint(outbound: &Value) -> Result<String, String> {
    let mut identity = outbound.clone();
    identity
        .as_object_mut()
        .ok_or_else(|| "Некорректный сервер VPN".to_string())?
        .remove("tag");
    let encoded = serde_json::to_vec(&identity).map_err(|error| error.to_string())?;
    let mut digest = Sha256::new();
    digest.update(b"warpy-health-profile-v1\0");
    digest.update(encoded);
    Ok(format!("{:x}", digest.finalize()))
}

fn send_selection(control: &SelectorControl, outbound: &str) -> Result<(), String> {
    let body =
        serde_json::to_string(&json!({ "name": outbound })).map_err(|error| error.to_string())?;
    let request = format!(
        "PUT /proxies/{} HTTP/1.1\r\nHost: {}\r\nAuthorization: Bearer {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        control.selector,
        control.address,
        control.secret,
        body.len(),
        body,
    );

    let response = send_request(control, &request, CONTROLLER_TIMEOUT)?;
    if !(200..300).contains(&response.status) {
        return Err(format!(
            "Не удалось переключить сервер (HTTP {})",
            response.status
        ));
    }
    Ok(())
}

fn verify_outbound(control: &SelectorControl, outbound: &str) -> Result<u64, String> {
    let request = format!(
        "GET /proxies/{outbound}/delay?url={PROBE_URL}&timeout={PROBE_TIMEOUT_MS} HTTP/1.1\r\nHost: {}\r\nAuthorization: Bearer {}\r\nAccept: application/json\r\nConnection: close\r\n\r\n",
        control.address, control.secret,
    );
    let response = send_request(control, &request, PROBE_RESPONSE_TIMEOUT)?;
    if !(200..300).contains(&response.status) {
        return Err(format!("проверка сервера вернула HTTP {}", response.status));
    }
    let body: Value = serde_json::from_slice(&response.body)
        .map_err(|error| format!("проверка сервера вернула некорректный ответ: {error}"))?;
    let delay = body
        .get("delay")
        .and_then(Value::as_u64)
        .unwrap_or_default();
    if delay == 0 {
        return Err("проверка сервера не получила данные".to_string());
    }
    Ok(delay)
}

struct ControllerResponse {
    status: u16,
    body: Vec<u8>,
}

fn send_request(
    control: &SelectorControl,
    request: &str,
    timeout: Duration,
) -> Result<ControllerResponse, String> {
    let mut stream = TcpStream::connect_timeout(&control.address, CONTROLLER_TIMEOUT)
        .map_err(|error| format!("Управление VPN недоступно: {error}"))?;
    stream
        .set_read_timeout(Some(timeout))
        .and_then(|_| stream.set_write_timeout(Some(timeout)))
        .map_err(|error| format!("Не удалось настроить управление VPN: {error}"))?;
    stream
        .write_all(request.as_bytes())
        .map_err(|error| format!("Не удалось отправить команду VPN: {error}"))?;

    let mut response = Vec::with_capacity(1024);
    stream
        .take(MAX_RESPONSE_BYTES as u64)
        .read_to_end(&mut response)
        .map_err(|error| format!("Не удалось получить ответ VPN: {error}"))?;
    let headers_end = response
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .ok_or_else(|| "Управление VPN вернуло некорректный ответ".to_string())?;
    let status_line = String::from_utf8_lossy(&response[..headers_end])
        .lines()
        .next()
        .unwrap_or_default()
        .to_string();
    let status = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|value| value.parse::<u16>().ok())
        .ok_or_else(|| "Управление VPN вернуло некорректный ответ".to_string())?;
    Ok(ControllerResponse {
        status,
        body: response[headers_end + 4..].to_vec(),
    })
}

fn random_secret() -> Result<String, String> {
    let mut bytes = [0_u8; 32];
    let status = unsafe {
        BCryptGenRandom(
            ptr::null_mut(),
            bytes.as_mut_ptr(),
            bytes.len() as u32,
            BCRYPT_USE_SYSTEM_PREFERRED_RNG,
        )
    };
    if status < 0 {
        return Err(format!(
            "Не удалось защитить управление VPN (0x{:08X})",
            status as u32
        ));
    }
    Ok(bytes.iter().map(|byte| format!("{byte:02x}")).collect())
}

#[cfg(test)]
mod tests {
    use super::{prepare_config, update_selected_outbound, SelectorControl, SelectorSwitchError};
    use std::{
        collections::{BTreeMap, BTreeSet},
        io::{Read, Write},
        net::TcpListener,
        thread,
    };

    #[test]
    fn injects_private_controller_for_proxy_selector() {
        let config = r#"{
          "outbounds": [
            {"type":"selector","tag":"proxy","outbounds":["profile-1","profile-2"],"default":"profile-2"},
            {"type":"direct","tag":"profile-1"},
            {"type":"direct","tag":"profile-2"}
          ]
        }"#;
        let (prepared, control) = prepare_config(config).expect("prepare selector config");
        let prepared: serde_json::Value = serde_json::from_str(&prepared).unwrap();
        let endpoint = prepared
            .pointer("/experimental/clash_api/external_controller")
            .and_then(serde_json::Value::as_str)
            .unwrap();
        let secret = prepared
            .pointer("/experimental/clash_api/secret")
            .and_then(serde_json::Value::as_str)
            .unwrap();

        assert!(endpoint.starts_with("127.0.0.1:"));
        assert_eq!(secret.len(), 64);
        let control = control.expect("selector control");
        assert_eq!(control.selected(), "profile-2");
        assert!(control.supports("profile-1"));
        assert!(!control.supports("profile-3"));
    }

    #[test]
    fn leaves_single_profile_config_without_controller() {
        let config = r#"{"outbounds":[{"type":"direct","tag":"proxy"}]}"#;
        let (prepared, control) = prepare_config(config).expect("prepare config");
        let prepared: serde_json::Value = serde_json::from_str(&prepared).unwrap();

        assert!(prepared.get("experimental").is_none());
        assert!(control.is_none());
    }

    #[test]
    fn profile_fingerprint_is_stable_across_runtime_tags() {
        let config = r#"{
          "outbounds": [
            {"type":"selector","tag":"proxy","outbounds":["profile-1","profile-2","profile-3"]},
            {"type":"trojan","tag":"profile-1","server":"example.com","server_port":443,"password":"same-secret"},
            {"type":"trojan","tag":"profile-2","server":"example.com","server_port":443,"password":"same-secret"},
            {"type":"trojan","tag":"profile-3","server":"example.com","server_port":443,"password":"other-secret"}
          ]
        }"#;
        let (_, control) = prepare_config(config).expect("prepare selector config");
        let targets = control.expect("selector control").health_targets();
        let first = &targets[0].1;
        let second = &targets[1].1;
        let third = &targets[2].1;

        assert_eq!(first.len(), 64);
        assert_eq!(first, second);
        assert_ne!(first, third);
        assert!(!first.contains("same-secret"));
    }

    #[test]
    fn sends_authenticated_selector_request_and_updates_state() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind test controller");
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept selector request");
            let mut request = [0_u8; 2048];
            let count = stream.read(&mut request).expect("read selector request");
            let request = String::from_utf8_lossy(&request[..count]);
            assert!(request.starts_with("PUT /proxies/proxy HTTP/1.1\r\n"));
            assert!(request.contains("Authorization: Bearer private-secret\r\n"));
            assert!(request.ends_with(r#"{"name":"profile-2"}"#));
            stream
                .write_all(
                    b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                )
                .expect("write selector response");
        });
        let mut control = SelectorControl {
            address,
            secret: "private-secret".to_string(),
            selector: "proxy".to_string(),
            outbounds: BTreeSet::from(["profile-1".to_string(), "profile-2".to_string()]),
            outbound_ids: BTreeMap::from([
                ("profile-1".to_string(), "id-1".to_string()),
                ("profile-2".to_string(), "id-2".to_string()),
            ]),
            selected: "profile-1".to_string(),
        };

        control.select("profile-2").expect("select outbound");
        server.join().expect("join test controller");
        assert_eq!(control.selected(), "profile-2");
    }

    #[test]
    fn rejects_unknown_outbound_without_contacting_controller() {
        let mut control = SelectorControl {
            address: "127.0.0.1:9".parse().unwrap(),
            secret: "private-secret".to_string(),
            selector: "proxy".to_string(),
            outbounds: BTreeSet::from(["profile-1".to_string()]),
            outbound_ids: BTreeMap::from([("profile-1".to_string(), "id-1".to_string())]),
            selected: "profile-1".to_string(),
        };

        assert_eq!(
            control.select("profile-2").unwrap_err(),
            "SELECTOR_UNKNOWN_OUTBOUND"
        );
        assert_eq!(control.selected(), "profile-1");
    }

    #[test]
    fn unreachable_outbound_is_rejected_before_selection() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind test controller");
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept delay request");
            let mut request = [0_u8; 2048];
            let count = stream.read(&mut request).expect("read delay request");
            let request = String::from_utf8_lossy(&request[..count]);
            assert!(request.starts_with("GET /proxies/profile-2/delay?"));
            let body = r#"{"message":"timeout"}"#;
            let response = format!(
                "HTTP/1.1 504 Gateway Timeout\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            stream
                .write_all(response.as_bytes())
                .expect("write delay response");
        });
        let mut control = SelectorControl {
            address,
            secret: "private-secret".to_string(),
            selector: "proxy".to_string(),
            outbounds: BTreeSet::from(["profile-1".to_string(), "profile-2".to_string()]),
            outbound_ids: BTreeMap::from([
                ("profile-1".to_string(), "id-1".to_string()),
                ("profile-2".to_string(), "id-2".to_string()),
            ]),
            selected: "profile-1".to_string(),
        };

        let error = control.select_verified("profile-2").unwrap_err();
        server.join().expect("join test controller");
        assert!(matches!(error, SelectorSwitchError::Rejected(_)));
        assert!(error
            .to_string()
            .contains("Новый сервер не передает данные"));
        assert_eq!(control.selected(), "profile-1");
    }

    #[test]
    fn failed_post_selection_verification_rolls_selector_back() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind test controller");
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let responses = [
                ("GET /proxies/profile-2/delay?", 200, r#"{"delay":42}"#),
                ("PUT /proxies/proxy HTTP/1.1", 204, ""),
                ("GET /proxies/proxy/delay?", 503, r#"{"message":"failed"}"#),
                ("PUT /proxies/proxy HTTP/1.1", 204, ""),
            ];
            for (expected, status, body) in responses {
                let (mut stream, _) = listener.accept().expect("accept selector request");
                let mut request = [0_u8; 2048];
                let count = stream.read(&mut request).expect("read selector request");
                let request = String::from_utf8_lossy(&request[..count]);
                assert!(
                    request.starts_with(expected),
                    "unexpected request: {request}"
                );
                let reason = if status == 204 { "No Content" } else { "OK" };
                let response = format!(
                    "HTTP/1.1 {status} {reason}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                stream
                    .write_all(response.as_bytes())
                    .expect("write selector response");
            }
        });
        let mut control = SelectorControl {
            address,
            secret: "private-secret".to_string(),
            selector: "proxy".to_string(),
            outbounds: BTreeSet::from(["profile-1".to_string(), "profile-2".to_string()]),
            outbound_ids: BTreeMap::from([
                ("profile-1".to_string(), "id-1".to_string()),
                ("profile-2".to_string(), "id-2".to_string()),
            ]),
            selected: "profile-1".to_string(),
        };

        let error = control.select_verified("profile-2").unwrap_err();
        server.join().expect("join test controller");
        assert!(matches!(error, SelectorSwitchError::RolledBack(_)));
        assert!(error
            .to_string()
            .contains("Новый сервер не передает данные"));
        assert_eq!(control.selected(), "profile-1");
    }

    #[test]
    fn reports_failed_post_selection_rollback_separately() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind test controller");
        let address = listener.local_addr().unwrap();
        let server = thread::spawn(move || {
            let responses = [
                ("GET /proxies/profile-2/delay?", 200, r#"{"delay":42}"#),
                ("PUT /proxies/proxy HTTP/1.1", 204, ""),
                ("GET /proxies/proxy/delay?", 503, r#"{"message":"failed"}"#),
                (
                    "PUT /proxies/proxy HTTP/1.1",
                    500,
                    r#"{"message":"failed"}"#,
                ),
            ];
            for (expected, status, body) in responses {
                let (mut stream, _) = listener.accept().expect("accept selector request");
                let mut request = [0_u8; 2048];
                let count = stream.read(&mut request).expect("read selector request");
                let request = String::from_utf8_lossy(&request[..count]);
                assert!(
                    request.starts_with(expected),
                    "unexpected request: {request}"
                );
                let response = format!(
                    "HTTP/1.1 {status} Test\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                stream
                    .write_all(response.as_bytes())
                    .expect("write selector response");
            }
        });
        let mut control = SelectorControl {
            address,
            secret: "private-secret".to_string(),
            selector: "proxy".to_string(),
            outbounds: BTreeSet::from(["profile-1".to_string(), "profile-2".to_string()]),
            outbound_ids: BTreeMap::from([
                ("profile-1".to_string(), "id-1".to_string()),
                ("profile-2".to_string(), "id-2".to_string()),
            ]),
            selected: "profile-1".to_string(),
        };

        let error = control.select_verified("profile-2").unwrap_err();
        server.join().expect("join test controller");
        assert!(matches!(error, SelectorSwitchError::RollbackFailed(_)));
        assert!(error.rollback_failed());
        assert_eq!(control.selected(), "profile-2");
    }

    #[test]
    fn updates_selector_default_for_recovery() {
        let config = r#"{
          "outbounds": [
            {"type":"selector","tag":"proxy","outbounds":["profile-1","profile-2"],"default":"profile-1"},
            {"type":"direct","tag":"profile-1"},
            {"type":"direct","tag":"profile-2"}
          ]
        }"#;

        let updated = update_selected_outbound(config, "profile-2").expect("update selector");
        let updated: serde_json::Value = serde_json::from_str(&updated).unwrap();
        assert_eq!(
            updated
                .pointer("/outbounds/0/default")
                .and_then(serde_json::Value::as_str),
            Some("profile-2")
        );
    }

    #[test]
    fn rejects_unknown_recovery_outbound() {
        let config = r#"{
          "outbounds": [
            {"type":"selector","tag":"proxy","outbounds":["profile-1"],"default":"profile-1"},
            {"type":"direct","tag":"profile-1"}
          ]
        }"#;

        assert_eq!(
            update_selected_outbound(config, "profile-2").unwrap_err(),
            "SELECTOR_UNKNOWN_OUTBOUND"
        );
    }
}
