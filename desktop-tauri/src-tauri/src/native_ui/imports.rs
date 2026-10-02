use super::config::{self, text};
use base64::{
    engine::general_purpose::{STANDARD, STANDARD_NO_PAD},
    Engine,
};
use serde::Serialize;
use serde_json::{json, Value};
use std::collections::HashSet;

#[derive(Serialize)]
pub(super) struct Imported {
    pub profiles: Vec<Value>,
    pub skipped: usize,
    pub format: String,
}
fn property<'a>(v: &'a Value, keys: &[&str]) -> &'a Value {
    keys.iter()
        .find_map(|key| v.get(*key))
        .unwrap_or(&Value::Null)
}
fn string(v: &Value) -> String {
    v.as_str().unwrap_or("").trim().into()
}
fn secret(v: &Value) -> String {
    if let Some(s) = v.as_str() {
        s.into()
    } else if v.is_number() {
        v.to_string()
    } else {
        String::new()
    }
}
fn first_secret(v: &Value, keys: &[&str], trim: bool) -> String {
    keys.iter()
        .map(|k| secret(&v[*k]))
        .map(|s| if trim { s.trim().into() } else { s })
        .find(|s| !s.is_empty())
        .unwrap_or_default()
}
fn first(v: &Value) -> String {
    string(if let Some(a) = v.as_array() {
        a.first().unwrap_or(&Value::Null)
    } else {
        v
    })
}
fn strings(v: &Value) -> Vec<String> {
    let values = if let Some(a) = v.as_array() {
        a.clone()
    } else {
        vec![v.clone()]
    };
    values
        .iter()
        .map(string)
        .filter(|s| !s.is_empty())
        .collect()
}
fn scalars(v: &Value) -> String {
    let values = if let Some(a) = v.as_array() {
        a.clone()
    } else {
        vec![v.clone()]
    };
    values
        .iter()
        .map(secret)
        .map(|s| s.trim().into())
        .filter(|s: &String| !s.is_empty())
        .collect::<Vec<_>>()
        .join(",")
}
fn number(v: &Value) -> Value {
    json!(v.as_i64().or_else(|| v.as_str()?.parse().ok()).unwrap_or(0))
}
fn header(v: &Value) -> String {
    v.as_object()
        .and_then(|v| v.iter().find(|(key, _)| key.eq_ignore_ascii_case("host")))
        .map(|(_, v)| first(v))
        .unwrap_or_default()
}
fn name(value: &Value, key: &str, protocol: &str, host: &str) -> String {
    let name = string(&value[key]);
    if !name.is_empty() && name.chars().count() <= 128 && !name.chars().any(|c| c.is_control()) {
        name
    } else {
        format!("{protocol} {host}")
    }
}
fn transport(s: &str) -> &str {
    match s {
        "h2" => "http",
        "http-upgrade" | "http_upgrade" => "httpupgrade",
        "splithttp" | "split-http" | "split_http" => "xhttp",
        _ => s,
    }
}

fn from_outbound(o: &Value) -> Option<Value> {
    let protocol = string(&o["type"]).to_lowercase();
    let peer = &o["peers"][0];
    let host = if text(o, "server").is_empty() {
        string(&peer["address"])
    } else {
        string(&o["server"])
    };
    let port = if o["server_port"].is_null() {
        number(&peer["port"])
    } else {
        number(&o["server_port"])
    };
    if host.is_empty() || !(1..=65535).contains(&port.as_i64()?) {
        return None;
    }
    let mut p = json!({"protocol":protocol,"name":name(o,"tag",&protocol,&host),"host":host,"port":port,"raw":""});
    let tls = &o["tls"];
    let reality = &tls["reality"];
    if reality["enabled"] == true && !string(&reality["public_key"]).is_empty() {
        p["security"] = "reality".into();
        p["pbk"] = string(&reality["public_key"]).into();
        p["sid"] = string(&reality["short_id"]).into();
    } else if tls["enabled"] == true {
        p["security"] = "tls".into();
    }
    {
        let (a, b) = ("server_name", "sni");
        let v = string(&tls[a]);
        if !v.is_empty() {
            p[b] = v.into();
        }
    }
    p["insecure"] = (tls["insecure"] == true).into();
    let alpn = strings(&tls["alpn"]);
    if !alpn.is_empty() {
        p["alpn"] = json!(alpn);
    }
    let fp = string(&tls["utls"]["fingerprint"]);
    if !fp.is_empty() {
        p["fp"] = fp.into();
    }
    let t = &o["transport"];
    let kind = string(&t["type"]);
    let kind = transport(&kind);
    if !kind.is_empty()
        && !matches!(
            kind,
            "tcp" | "raw" | "ws" | "grpc" | "http" | "httpupgrade" | "xhttp"
        )
    {
        return None;
    }
    if !kind.is_empty() && !matches!(protocol.as_str(), "vless" | "trojan" | "vmess") {
        return None;
    }
    if matches!(protocol.as_str(), "vless" | "trojan" | "vmess") {
        p["transport"] = if kind.is_empty() { "tcp" } else { kind }.into();
        if !text(t, "path").is_empty() {
            p["path"] = string(&t["path"]).into();
        }
        if kind == "grpc" {
            if !text(t, "service_name").is_empty() {
                p["serviceName"] = string(&t["service_name"]).into();
            }
        } else {
            if kind == "xhttp" {
                p["xhttpMode"] =
                    if matches!(text(t, "mode"), "stream-up" | "stream-one" | "packet-up") {
                        text(t, "mode")
                    } else {
                        "stream-one"
                    }
                    .into();
            }
            let host = header(&t["headers"]);
            let host = if host.is_empty() {
                first(&t["host"])
            } else {
                host
            };
            if !host.is_empty() {
                p["hostHeader"] = host.into();
            }
        }
    }
    match protocol.as_str() {
        "vless" => {
            for (a, b) in [
                ("uuid", "uuid"),
                ("flow", "flow"),
                ("packet_encoding", "packetEncoding"),
            ] {
                p[b] = string(&o[a]).into();
            }
        }
        "trojan" => p["uuid"] = secret(&o["password"]).into(),
        "hysteria2" => {
            p["uuid"] = secret(&o["password"]).into();
            let obfs = &o["obfs"];
            if !text(obfs, "type").is_empty() && !secret(&obfs["password"]).is_empty() {
                p["obfsType"] = string(&obfs["type"]).into();
                p["obfsPassword"] = secret(&obfs["password"]).into();
            }
            p["serverPorts"] = strings(&o["server_ports"]).join(",").into();
            for (a, b) in [
                ("hop_interval", "hopInterval"),
                ("hop_interval_max", "hopIntervalMax"),
            ] {
                p[b] = string(&o[a]).into();
            }
            for (a, b) in [("up_mbps", "upMbps"), ("down_mbps", "downMbps")] {
                p[b] = number(&o[a]);
            }
        }
        "vmess" => {
            p["uuid"] = string(&o["uuid"]).into();
            p["encryption"] = if text(o, "security").is_empty() {
                "auto"
            } else {
                text(o, "security")
            }
            .into();
            p["alterId"] = number(&o["alter_id"]);
            p["packetEncoding"] = string(&o["packet_encoding"]).into();
        }
        "shadowsocks" => {
            p["encryption"] = string(&o["method"]).into();
            p["password"] = secret(&o["password"]).into();
            p["plugin"] = string(&o["plugin"]).into();
            p["pluginOptions"] = string(&o["plugin_opts"]).into();
            if !text(&p, "plugin").is_empty()
                && !matches!(text(&p, "plugin"), "obfs-local" | "v2ray-plugin")
            {
                return None;
            }
        }
        "socks" | "naive" => {
            p["username"] = string(&o["username"]).into();
            p["password"] = secret(&o["password"]).into();
            if protocol == "naive" {
                p["naiveQuic"] = (o["quic"] == true).into();
            }
        }
        "wireguard" => {
            p["privateKey"] = secret(&o["private_key"]).into();
            p["peerPublicKey"] = if text(o, "peer_public_key").is_empty() {
                string(&peer["public_key"])
            } else {
                string(&o["peer_public_key"])
            }
            .into();
            p["preSharedKey"] = if secret(&o["pre_shared_key"]).is_empty() {
                secret(&peer["pre_shared_key"])
            } else {
                secret(&o["pre_shared_key"])
            }
            .into();
            p["localAddress"] = strings(property(o, &["local_address", "address"]))
                .join(",")
                .into();
            p["reserved"] = scalars(if o["reserved"].is_null() {
                &peer["reserved"]
            } else {
                &o["reserved"]
            })
            .into();
            p["mtu"] = number(&o["mtu"]);
        }
        "tuic" => {
            p["uuid"] = string(&o["uuid"]).into();
            p["password"] = secret(&o["password"]).into();
            p["congestionControl"] = if text(o, "congestion_control").is_empty() {
                "cubic"
            } else {
                text(o, "congestion_control")
            }
            .into();
            p["udpRelayMode"] = if text(o, "udp_relay_mode").is_empty() {
                "native"
            } else {
                text(o, "udp_relay_mode")
            }
            .into();
        }
        "hysteria" => {
            p["uuid"] = first_secret(o, &["auth_str", "auth"], false).into();
            p["obfsPassword"] = secret(&o["obfs"]).into();
            p["upMbps"] = number(&o["up_mbps"]);
            p["downMbps"] = number(&o["down_mbps"]);
        }
        _ => return None,
    }
    config::normalize(&p).ok()?;
    Some(p)
}
fn from_clash(o: &Value) -> Option<Value> {
    let raw = string(&o["type"]).to_lowercase();
    let protocol = match raw.as_str() {
        "ss" => "shadowsocks",
        "socks5" => "socks",
        "hy2" => "hysteria2",
        "wg" => "wireguard",
        _ => &raw,
    };
    let host = string(&o["server"]);
    let port = number(&o["port"]);
    if host.is_empty() || !(1..=65535).contains(&port.as_i64()?) {
        return None;
    }
    let mut p = json!({"protocol":protocol,"name":name(o,"name",protocol,&host),"host":host,"port":port,"raw":""});
    let reality = property(o, &["reality-opts", "reality_opts"]);
    let public = string(property(reality, &["public-key", "public_key"]));
    if !public.is_empty() {
        p["security"] = "reality".into();
        p["pbk"] = public.into();
        let sid = string(property(reality, &["short-id", "short_id"]));
        if !sid.is_empty() {
            p["sid"] = sid.into();
        }
    } else if o["tls"] == true
        || matches!(
            protocol,
            "trojan" | "hysteria2" | "tuic" | "hysteria" | "naive"
        )
    {
        p["security"] = "tls".into();
    }
    let sni = string(property(o, &["servername", "server-name", "sni", "peer"]));
    if !sni.is_empty() {
        p["sni"] = sni.into();
    }
    p["insecure"] =
        (property(o, &["skip-cert-verify", "skip_cert_verify"]) == &Value::Bool(true)).into();
    let alpn = strings(&o["alpn"]);
    if !alpn.is_empty() {
        p["alpn"] = json!(alpn);
    }
    let fp = string(property(
        o,
        &["client-fingerprint", "client_fingerprint", "fingerprint"],
    ));
    if !fp.is_empty() {
        p["fp"] = fp.into();
    }
    let network = string(&o["network"]).to_lowercase();
    let kind = if network.is_empty() || protocol == "hysteria2" {
        "tcp"
    } else {
        transport(&network)
    };
    if protocol == "hysteria2" && !network.is_empty() && network != "udp"
        || !matches!(
            kind,
            "tcp" | "raw" | "ws" | "grpc" | "http" | "httpupgrade" | "xhttp"
        )
    {
        return None;
    }
    let options = match kind {
        "ws" => property(o, &["ws-opts", "ws_opts"]),
        "grpc" => property(o, &["grpc-opts", "grpc_opts"]),
        "http" => {
            if network == "h2" {
                property(o, &["h2-opts", "h2_opts"])
            } else {
                property(o, &["http-opts", "http_opts"])
            }
        }
        "xhttp" => property(o, &["xhttp-opts", "xhttp_opts"]),
        "httpupgrade" => property(o, &["http-upgrade-opts", "http_upgrade_opts"]),
        _ => &Value::Null,
    };
    p["transport"] = if kind == "raw" { "raw" } else { kind }.into();
    p["path"] = first(&options["path"]).into();
    p["serviceName"] = if kind == "grpc" {
        string(property(
            options,
            &[
                "grpc-service-name",
                "grpc_service_name",
                "service-name",
                "service_name",
            ],
        ))
    } else {
        String::new()
    }
    .into();
    p["xhttpMode"] = if kind == "xhttp" {
        if matches!(
            text(options, "mode"),
            "stream-up" | "stream-one" | "packet-up"
        ) {
            text(options, "mode")
        } else {
            "stream-up"
        }
    } else {
        ""
    }
    .into();
    let h = header(&options["headers"]);
    p["hostHeader"] = if h.is_empty() {
        first(&options["host"])
    } else {
        h
    }
    .into();
    match protocol {
        "vless" => {
            p["uuid"] = first_secret(o, &["uuid"], true).into();
            p["flow"] = string(&o["flow"]).into();
            p["packetEncoding"] =
                string(property(o, &["packet-encoding", "packet_encoding"])).into();
        }
        "trojan" => p["uuid"] = first_secret(o, &["password"], false).into(),
        "hysteria2" => {
            p["uuid"] =
                first_secret(o, &["password", "auth", "auth-str", "auth_str"], false).into();
            let obfs = &o["obfs"];
            let kind = if obfs.is_object() {
                string(&obfs["type"])
            } else {
                string(obfs)
            };
            let password = first_secret(obfs, &["password"], false);
            let password = if password.is_empty() {
                first_secret(o, &["obfs-password", "obfs_password"], false)
            } else {
                password
            };
            if !kind.is_empty() && !password.is_empty() {
                p["obfsType"] = kind.into();
                p["obfsPassword"] = password.into();
            }
            p["serverPorts"] = scalars(property(
                o,
                &["ports", "server-ports", "server_ports", "mport"],
            ))
            .into();
            p["hopInterval"] = string(property(o, &["hop-interval", "hop_interval"])).into();
            p["hopIntervalMax"] =
                string(property(o, &["hop-interval-max", "hop_interval_max"])).into();
            p["upMbps"] = number(property(o, &["up", "up-mbps", "up_mbps"]));
            p["downMbps"] = number(property(o, &["down", "down-mbps", "down_mbps"]));
        }
        "vmess" => {
            p["uuid"] = first_secret(o, &["uuid"], true).into();
            p["encryption"] = if text(o, "cipher").is_empty() {
                "auto"
            } else {
                text(o, "cipher")
            }
            .into();
            p["alterId"] = number(property(o, &["alterId", "alter-id", "alter_id"]));
            p["packetEncoding"] =
                string(property(o, &["packet-encoding", "packet_encoding"])).into();
        }
        "shadowsocks" => {
            p["encryption"] = string(&o["cipher"]).into();
            p["password"] = first_secret(o, &["password"], false).into();
            p["plugin"] = string(&o["plugin"]).into();
            let opts = property(o, &["plugin-opts", "plugin_opts"]);
            p["pluginOptions"] = if opts.is_string() {
                secret(opts)
            } else {
                opts.as_object()
                    .into_iter()
                    .flatten()
                    .map(|(k, v)| {
                        if v == true {
                            k.clone()
                        } else {
                            format!(
                                "{k}={}",
                                if v.is_string() {
                                    secret(v)
                                } else {
                                    v.to_string()
                                }
                            )
                        }
                    })
                    .collect::<Vec<_>>()
                    .join(";")
            }
            .into();
            if !text(&p, "plugin").is_empty()
                && !matches!(text(&p, "plugin"), "obfs-local" | "v2ray-plugin")
            {
                return None;
            }
        }
        "socks" | "naive" => {
            p["username"] = string(&o["username"]).into();
            p["password"] = first_secret(o, &["password"], false).into();
            if protocol == "naive" {
                p["naiveQuic"] = (o["quic"] == true).into();
            }
        }
        "wireguard" => {
            p["privateKey"] = first_secret(o, &["private-key", "private_key"], false).into();
            p["peerPublicKey"] = string(property(o, &["public-key", "public_key"])).into();
            p["preSharedKey"] =
                first_secret(o, &["pre-shared-key", "pre_shared_key", "psk"], false).into();
            let mut addresses = strings(property(o, &["ip", "address"]));
            addresses.extend(strings(&o["ipv6"]));
            p["localAddress"] = addresses.join(",").into();
            p["reserved"] = scalars(&o["reserved"]).into();
            p["mtu"] = number(&o["mtu"]);
        }
        "tuic" => {
            p["uuid"] = first_secret(o, &["uuid"], true).into();
            p["password"] = first_secret(o, &["password"], false).into();
            let congestion = string(property(
                o,
                &[
                    "congestion-controller",
                    "congestion_control",
                    "congestion-control",
                ],
            ));
            p["congestionControl"] = if congestion.is_empty() {
                "cubic".into()
            } else {
                congestion
            }
            .into();
            let relay = string(property(o, &["udp-relay-mode", "udp_relay_mode"]));
            p["udpRelayMode"] = if relay.is_empty() {
                "native".into()
            } else {
                relay
            }
            .into();
        }
        "hysteria" => {
            p["uuid"] =
                first_secret(o, &["auth-str", "auth_str", "auth", "password"], false).into();
            p["obfsPassword"] = first_secret(o, &["obfs"], false).into();
            p["upMbps"] = number(property(o, &["up", "up-mbps", "up_mbps"]));
            p["downMbps"] = number(property(o, &["down", "down-mbps", "down_mbps"]));
        }
        _ => return None,
    }
    config::normalize(&p).ok()?;
    Some(p)
}
fn deduplicate(
    values: impl Iterator<Item = Option<Value>>,
    format: &str,
) -> Result<Imported, String> {
    let mut result = Imported {
        profiles: Vec::new(),
        skipped: 0,
        format: format.into(),
    };
    let mut seen = HashSet::new();
    for value in values {
        if let Some(p) = value {
            if seen.insert(config::profile_key(&p)) {
                result.profiles.push(p);
                if result.profiles.len() > 2000 {
                    return Err("В подписке слишком много профилей".into());
                }
            }
        } else {
            result.skipped += 1;
        }
    }
    Ok(result)
}
fn attempt(value: &str, prefix: &str) -> Result<Imported, String> {
    let mut result = deduplicate(
        value
            .split_whitespace()
            .filter(|v| !v.starts_with('#') && v.contains("://"))
            .map(config::parse_link),
        if prefix.is_empty() {
            "uri-list"
        } else {
            "base64"
        },
    )?;
    if !result.profiles.is_empty() {
        return Ok(result);
    }
    if let Ok(json) = serde_json::from_str::<Value>(value) {
        let values = if let Some(array) = json.as_array() {
            array.clone()
        } else {
            json["outbounds"]
                .as_array()
                .into_iter()
                .flatten()
                .chain(json["endpoints"].as_array().into_iter().flatten())
                .cloned()
                .collect()
        };
        if !values.is_empty() {
            let parsed = deduplicate(
                values.iter().map(from_outbound),
                &format!("{prefix}sing-box-json"),
            )?;
            if !parsed.profiles.is_empty() {
                return Ok(parsed);
            }
        }
    }
    fn valid_yaml(value: &serde_yaml_ng::Value, depth: usize) -> bool {
        if depth > 20 {
            return false;
        }
        match value {
            serde_yaml_ng::Value::Sequence(v) => v.iter().all(|v| valid_yaml(v, depth + 1)),
            serde_yaml_ng::Value::Mapping(v) => v
                .iter()
                .all(|(k, v)| k.as_str() != Some("<<") && valid_yaml(v, depth + 1)),
            serde_yaml_ng::Value::Tagged(_) => false,
            _ => true,
        }
    }
    if let Ok(yaml) = serde_yaml_ng::from_str::<serde_yaml_ng::Value>(value) {
        if valid_yaml(&yaml, 0) {
            if let Ok(yaml) = serde_json::to_value(yaml) {
                if let Some(values) = yaml["proxies"].as_array() {
                    let parsed = deduplicate(
                        values.iter().map(from_clash),
                        &format!("{prefix}clash-yaml"),
                    )?;
                    if !parsed.profiles.is_empty() {
                        return Ok(parsed);
                    }
                }
            }
        }
    }
    result.format = if prefix.is_empty() {
        "uri-list".into()
    } else {
        "base64".into()
    };
    Ok(result)
}
pub(super) fn parse_payload(value: &str) -> Result<Imported, String> {
    let value = value.trim_start_matches('\u{feff}').trim();
    if value.len() > 2 * 1024 * 1024 {
        return Err("Подписка превышает допустимый размер".into());
    }
    let result = attempt(value, "")?;
    if !result.profiles.is_empty() {
        return Ok(result);
    }
    let compact = value
        .split_whitespace()
        .collect::<String>()
        .replace('-', "+")
        .replace('_', "/");
    let decoded = STANDARD
        .decode(&compact)
        .or_else(|_| STANDARD_NO_PAD.decode(compact.trim_end_matches('=')));
    if let Ok(decoded) = decoded {
        if let Ok(decoded) = String::from_utf8(decoded) {
            let result = attempt(decoded.trim_start_matches('\u{feff}').trim(), "base64-")?;
            if !result.profiles.is_empty() {
                return Ok(result);
            }
        }
    }
    Err("Подписка не содержит поддерживаемых профилей".into())
}
pub(super) fn subscription_url(value: &str) -> Option<url::Url> {
    let mut url = url::Url::parse(value.trim()).ok()?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return None;
    }
    url.set_fragment(None);
    Some(url)
}
pub(super) fn display_name(value: &str) -> Result<String, String> {
    let url = url::Url::parse(value).map_err(|e| e.to_string())?;
    let fragment = percent_encoding::percent_decode_str(url.fragment().unwrap_or(""))
        .decode_utf8()
        .unwrap_or_default();
    let fragment = fragment.trim();
    if !fragment.is_empty()
        && fragment.chars().count() <= 64
        && !fragment.chars().any(char::is_control)
    {
        return Ok(fragment.into());
    }
    let host = url
        .host_str()
        .unwrap_or("SUBSCRIPTION")
        .trim_start_matches("www.");
    let parts: Vec<_> = host.split('.').collect();
    let first = parts[0];
    let token = first.len() >= 8 && first.chars().all(|c| c.is_ascii_hexdigit())
        || first.len() >= 6 && first.chars().all(|c| c.is_ascii_digit());
    let label = if (matches!(first, "api" | "sub" | "subs" | "subscription" | "panel") || token)
        && parts.len() > 1
    {
        parts[1]
    } else {
        first
    };
    let label = label
        .strip_prefix("with")
        .filter(|s| s.chars().next().is_some_and(|c| c.is_ascii_alphanumeric()))
        .unwrap_or(label);
    Ok(label.replace(['-', '_'], " ").to_uppercase())
}
pub(super) fn replace_profiles(
    existing: &[Value],
    id: &str,
    imported: &[Value],
    group: &str,
    legacy: &str,
) -> Vec<Value> {
    let replacement: Vec<_> = imported
        .iter()
        .map(|p| {
            let mut p = p.clone();
            p["subscriptionId"] = id.into();
            p["group"] = group.into();
            p
        })
        .collect();
    let mut next = Vec::new();
    let mut inserted = false;
    for p in existing {
        if text(p, "subscriptionId") == id
            || !legacy.is_empty()
                && text(p, "subscriptionId").is_empty()
                && text(p, "group").trim().eq_ignore_ascii_case(legacy.trim())
        {
            if !inserted {
                next.extend(replacement.clone());
                inserted = true;
            }
        } else {
            next.push(p.clone());
        }
    }
    if !inserted {
        next.extend(replacement);
    }
    next
}
pub(super) fn refresh_due(subscription: &Value, now: u64) -> bool {
    let checked = subscription["lastCheckedAt"].as_u64().unwrap_or(0);
    checked == 0 || checked > now || now - checked >= 24 * 60 * 60 * 1000
}
pub(super) fn find_profile(profiles: &[Value], previous: &Value, id: &str) -> Option<usize> {
    let key = config::profile_key(previous);
    profiles.iter().position(|p| {
        config::profile_key(p) == key
            && (text(previous, "subscriptionId") != id || text(p, "subscriptionId") == id)
    })
}
fn content_key(p: &Value) -> String {
    let raw = text(p, "raw").trim();
    if !raw.is_empty() {
        return raw.replacen("hy2://", "hysteria2://", 1);
    }
    let mut values: Vec<Value> = serde_json::from_str(&config::profile_key(p)).unwrap();
    values.push(p["name"].clone());
    json!(values).to_string()
}
pub(super) fn profiles_equal(a: &[Value], b: &[Value]) -> bool {
    a.len() == b.len()
        && a.iter()
            .zip(b)
            .all(|(a, b)| content_key(a) == content_key(b))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rust_imports_preserve_legacy_regression_cases() {
        let cases: Vec<Value> =
            serde_json::from_str(include_str!("../../../test/fixtures/rust-imports.json")).unwrap();
        let mut failures = Vec::new();
        for (i, case) in cases.iter().enumerate() {
            let a = case["args"].as_array().unwrap();
            let result: Result<Value, String> = match text(case, "name") {
                "parseSubscriptionPayload" => a[0]
                    .as_str()
                    .ok_or("Invalid payload".into())
                    .and_then(parse_payload)
                    .map(|v| serde_json::to_value(v).unwrap()),
                "subscriptionProfileKey" => Ok(config::profile_key(&a[0]).into()),
                "subscriptionProfilesEqual" => Ok(match (a[0].as_array(), a[1].as_array()) {
                    (Some(a), Some(b)) => profiles_equal(a, b),
                    _ => false,
                }
                .into()),
                "subscriptionRefreshDue" => {
                    Ok(refresh_due(&a[0], a.get(1).and_then(Value::as_u64).unwrap_or(0)).into())
                }
                "subscriptionDisplayName" => {
                    display_name(a[0].as_str().unwrap()).map(Value::String)
                }
                "replaceSubscriptionProfiles" => {
                    if let (Some(existing), Some(imported)) = (a[0].as_array(), a[2].as_array()) {
                        if imported.is_empty()
                            || a[1].as_str().unwrap_or("").is_empty()
                            || a[3].as_str().unwrap_or("").is_empty()
                        {
                            Err("Invalid replacement".into())
                        } else {
                            Ok(json!(replace_profiles(
                                existing,
                                a[1].as_str().unwrap(),
                                imported,
                                a[3].as_str().unwrap(),
                                a.get(4).and_then(Value::as_str).unwrap_or("")
                            )))
                        }
                    } else {
                        Err("Invalid profiles".into())
                    }
                }
                "findProfileIndexAfterSubscriptionUpdate" => Ok(json!(a[0]
                    .as_array()
                    .and_then(|p| find_profile(p, &a[1], a[2].as_str().unwrap_or("")))
                    .map(|i| i as i64)
                    .unwrap_or(-1))),
                name => panic!("Unknown case {name}"),
            };
            if case["error"] == true {
                if result.is_ok() {
                    failures.push(format!("Case {i} {} should fail", case["name"]));
                }
            } else {
                match result {
                    Ok(v) => {
                        if v != case["result"] {
                            failures.push(format!("Case {i} {} differs", case["name"]));
                            let directory = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                                .join("../../.artifacts/rust-client-migration");
                            std::fs::write(
                                directory.join(format!("import-case-{i}.json")),
                                json!({"expected":case["result"],"actual":v}).to_string(),
                            )
                            .unwrap();
                        }
                    }
                    Err(error) => failures.push(format!("Case {i} {}: {error}", case["name"])),
                }
            }
        }
        assert!(failures.is_empty(), "{}", failures.join("\n"));
    }
    #[test]
    fn oversized_payloads_and_profile_sets_are_rejected() {
        assert!(parse_payload(&"x".repeat(2 * 1024 * 1024 + 1)).is_err());
        let profiles = (0..2001)
            .map(|i| Some(json!({"protocol":"socks","host":format!("server-{i}"),"port":1080})));
        assert!(deduplicate(profiles, "uri-list").is_err());
    }
}
