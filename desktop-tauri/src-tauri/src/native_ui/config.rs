use base64::{
    engine::general_purpose::{STANDARD, STANDARD_NO_PAD, URL_SAFE_NO_PAD},
    Engine,
};
use serde_json::{json, Value};
use std::collections::HashSet;

pub(super) fn text<'a>(value: &'a Value, key: &str) -> &'a str {
    value[key].as_str().unwrap_or("")
}
fn fallback<'a>(value: &'a Value, key: &str, default: &'a str) -> &'a str {
    let s = text(value, key);
    if s.is_empty() {
        default
    } else {
        s
    }
}
fn number(value: &Value, key: &str) -> i64 {
    value[key]
        .as_i64()
        .or_else(|| value[key].as_str()?.parse().ok())
        .unwrap_or(0)
}
fn yes(value: &Value, key: &str) -> bool {
    value[key].as_bool().unwrap_or(false)
}
fn list(value: &str) -> Vec<String> {
    value
        .split([',', ';'])
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
        .collect()
}
fn decode(value: &str) -> Option<String> {
    percent_encoding::percent_decode_str(value)
        .decode_utf8()
        .ok()
        .map(|v| v.into_owned())
}
pub(super) fn encode_component(value: &str) -> String {
    const SET: &percent_encoding::AsciiSet = &percent_encoding::NON_ALPHANUMERIC
        .remove(b'-')
        .remove(b'_')
        .remove(b'.')
        .remove(b'!')
        .remove(b'~')
        .remove(b'*')
        .remove(b'\'')
        .remove(b'(')
        .remove(b')');
    percent_encoding::utf8_percent_encode(value, SET).to_string()
}
fn base64(value: &str) -> Option<String> {
    let normalized = value.trim().replace('-', "+").replace('_', "/");
    String::from_utf8(
        STANDARD
            .decode(&normalized)
            .or_else(|_| STANDARD_NO_PAD.decode(normalized.trim_end_matches('=')))
            .ok()?,
    )
    .ok()
}
fn transport(value: &str) -> &str {
    match value {
        "h2" => "http",
        "http-upgrade" | "http_upgrade" => "httpupgrade",
        "splithttp" | "split-http" | "split_http" => "xhttp",
        _ => value,
    }
}
fn supported(protocol: &str) -> bool {
    super::assets()["contract"]["protocols"]
        .as_array()
        .unwrap()
        .iter()
        .any(|v| v == protocol)
}
fn supported_transport(kind: &str) -> bool {
    super::assets()["contract"]["transports"]
        .as_array()
        .unwrap()
        .iter()
        .any(|v| v == kind)
}

pub(super) fn parse_link(source: &str) -> Option<Value> {
    let source = source.trim();
    let lower = source.to_lowercase();
    if lower.starts_with("vmess://") {
        let v: Value = serde_json::from_str(&base64(source[8..].split('#').next()?)?).ok()?;
        let port = number(&v, "port");
        let net = transport(fallback(&v, "net", "tcp"));
        if text(&v, "add").is_empty()
            || text(&v, "id").is_empty()
            || !(1..=65535).contains(&port)
            || !supported_transport(net)
        {
            return None;
        }
        return Some(
            json!({"protocol":"vmess","name":fallback(&v,"ps",text(&v,"add")),"host":text(&v,"add"),"port":port,"uuid":text(&v,"id"),
            "security":text(&v,"tls").to_lowercase(),"sni":text(&v,"sni"),"fp":fallback(&v,"fp","chrome"),"alpn":list(text(&v,"alpn")),
            "transport":net,"path":text(&v,"path"),"hostHeader":text(&v,"host"),"serviceName":if net=="grpc" {text(&v,"path")} else {""},
            "xhttpMode":if net=="xhttp" {fallback(&v,"mode","stream-one")} else {""},"encryption":fallback(&v,"scy","auto"),"alterId":number(&v,"aid"),"packetEncoding":text(&v,"packetEncoding"),"raw":source}),
        );
    }
    if lower.starts_with("ss://") {
        let body = source[5..].split('#').next()?;
        let decoded = if let Some((credential, endpoint)) = body.rsplit_once('@') {
            format!(
                "{}@{endpoint}",
                if credential.contains(':') {
                    decode(credential)?
                } else {
                    base64(credential)?
                }
            )
        } else {
            base64(body)?
        };
        let (credentials, endpoint) = decoded.rsplit_once('@')?;
        let (method, password) = credentials.split_once(':')?;
        let url = url::Url::parse(&format!("ss://{endpoint}")).ok()?;
        let plugin = url
            .query_pairs()
            .find(|(k, _)| k == "plugin")
            .map(|(_, v)| v.into_owned())
            .unwrap_or_default();
        let (plugin, options) = plugin.split_once(';').unwrap_or((&plugin, ""));
        if !plugin.is_empty() && !matches!(plugin, "obfs-local" | "v2ray-plugin") {
            return None;
        }
        let host = url.host_str()?.trim_matches(['[', ']']);
        let port = url.port()?;
        return Some(
            json!({"protocol":"shadowsocks","name":decode(source.split_once('#').map(|(_,s)|s).unwrap_or(host))?,"host":host,"port":port,
            "encryption":method,"password":password,"plugin":plugin,"pluginOptions":options,"raw":source}),
        );
    }
    let (scheme, rest) = source.split_once("://")?;
    let normalized = match scheme.to_lowercase().as_str() {
        "hy2" => "hysteria2",
        "socks5" => "socks",
        "wg" => "wireguard",
        "naive+https" | "naive+quic" => "naive",
        _ => scheme,
    };
    let url = url::Url::parse(&format!("{normalized}://{rest}")).ok()?;
    let username = decode(url.username())?;
    let password = decode(url.password().unwrap_or(""))?;
    let protocol = if normalized == "https" && !username.is_empty() && !password.is_empty() {
        "naive"
    } else {
        normalized
    };
    let host = url.host_str()?.trim_matches(['[', ']']);
    let port = url.port().unwrap_or(match protocol {
        "socks" => 1080,
        "wireguard" => 51820,
        _ => 443,
    });
    if !supported(protocol)
        || host.is_empty()
        || port == 0
        || matches!(
            protocol,
            "vless" | "trojan" | "hysteria2" | "tuic" | "naive"
        ) && username.is_empty()
        || protocol == "naive" && password.is_empty()
    {
        return None;
    }
    let query: std::collections::HashMap<_, _> = url
        .query_pairs()
        .map(|(k, v)| (k.into_owned(), v.into_owned()))
        .collect();
    let q = |keys: &[&str]| -> String {
        keys.iter()
            .find_map(|k| query.get(*k).filter(|s| !s.is_empty()).cloned())
            .unwrap_or_default()
    };
    let net = q(&["type"]);
    let net = transport(if net.is_empty() { "tcp" } else { &net });
    if !matches!(
        protocol,
        "hysteria2" | "hysteria" | "tuic" | "wireguard" | "socks" | "naive"
    ) && !supported_transport(net)
    {
        return None;
    }
    let opaque = if password.is_empty() {
        username.clone()
    } else {
        format!("{username}:{password}")
    };
    let obfs = q(&["obfs-password", "obfs_password"]);
    let mode = q(&["mode"]);
    let mut p = json!({"protocol":protocol,"name":decode(url.fragment().unwrap_or(host))?,"host":host,"port":port,
        "uuid":if matches!(protocol,"trojan"|"hysteria2"|"hysteria"){opaque}else{username.clone()},"security":if protocol=="naive"{"tls".to_string()}else{q(&["security"]).to_lowercase()},
        "sni":q(&["sni","peer"]),"pbk":q(&["pbk"]),"sid":q(&["sid"]),"flow":q(&["flow"]),"fp":q(&["fp"]),"transport":net,
        "path":decode(&q(&["path"]))?,"hostHeader":q(&["host"]),"serviceName":decode(&q(&["serviceName","service_name"]))?,
        "xhttpMode":if net=="xhttp" {if matches!(mode.as_str(),"stream-up"|"stream-one"|"packet-up"){mode}else{"stream-one".into()}}else{String::new()},
        "alpn":list(&q(&["alpn"])),"packetEncoding":q(&["packetEncoding","packet_encoding"]),"insecure":matches!(q(&["insecure","allowInsecure","allow_insecure"]).as_str(),"1"|"true"),
        "obfsType":({let kind=q(&["obfs","obfs-type"]);if kind.is_empty()&&!obfs.is_empty(){"salamander".to_string()}else{kind}}),"obfsPassword":obfs,
        "serverPorts":q(&["server_ports","server-ports","mport","ports"]),"hopInterval":q(&["hop_interval","hop-interval"]),"hopIntervalMax":q(&["hop_interval_max","hop-interval-max"]),
        "upMbps":q(&["up_mbps","upmbps"]).parse::<i64>().unwrap_or(0),"downMbps":q(&["down_mbps","downmbps"]).parse::<i64>().unwrap_or(0),
        "username":if matches!(protocol,"socks"|"naive"){username}else{String::new()},"password":if matches!(protocol,"tuic"|"socks"|"naive"){password}else{String::new()},"encryption":"","alterId":0,
        "privateKey":q(&["pk","private_key"]),"peerPublicKey":q(&["peer_pk","public_key"]),"preSharedKey":q(&["pre_shared_key","psk"]),"localAddress":q(&["local_address","address"]),"reserved":q(&["reserved"]),"mtu":q(&["mtu"]).parse::<i64>().unwrap_or(0),
        "congestionControl":({let s=q(&["congestion_control","congestion-control"]);if s.is_empty(){"cubic".to_string()}else{s}}),"udpRelayMode":({let s=q(&["udp_relay_mode","udp-relay-mode"]);if s.is_empty(){"native".to_string()}else{s}}),
        "naiveQuic":protocol=="naive"&&(scheme=="naive+quic"||matches!(q(&["quic"]).as_str(),"1"|"true")),"raw":source});
    if protocol == "hysteria" {
        p["uuid"] = q(&["auth", "auth_str"]).into();
        if text(&p, "uuid").is_empty() {
            p["uuid"] = decode(url.username())?.into();
        }
        let obfs = q(&["obfs"]);
        if !obfs.is_empty() {
            p["obfsPassword"] = obfs.into();
        }
    }
    if protocol == "wireguard"
        && ["privateKey", "peerPublicKey", "localAddress"]
            .iter()
            .any(|k| text(&p, k).is_empty())
    {
        return None;
    }
    Some(p)
}

pub(super) fn normalize(input: &Value) -> Result<Value, String> {
    let mut p = if text(input, "raw").is_empty() {
        input.clone()
    } else {
        parse_link(text(input, "raw")).unwrap_or_else(|| input.clone())
    };
    for key in ["name", "group", "subscriptionId"] {
        if let Some(v) = input.get(key) {
            p[key] = v.clone();
        }
    }
    let credentials = match text(&p, "protocol") {
        "vless" | "trojan" | "hysteria2" | "vmess" | "tuic" => !text(&p, "uuid").is_empty(),
        "shadowsocks" => !text(&p, "encryption").is_empty() && !text(&p, "password").is_empty(),
        "wireguard" => ["privateKey", "peerPublicKey", "localAddress"]
            .iter()
            .all(|k| !text(&p, k).is_empty()),
        "naive" => !text(&p, "username").is_empty() && !text(&p, "password").is_empty(),
        "socks" | "hysteria" => true,
        _ => false,
    };
    if !supported(text(&p, "protocol"))
        || text(&p, "host").is_empty()
        || number(&p, "port") == 0
        || !credentials
    {
        Err("Incomplete VPN profile".into())
    } else {
        Ok(p)
    }
}

fn tls(p: &Value, required: bool) -> Option<Value> {
    let security = fallback(
        p,
        "security",
        if !text(p, "pbk").is_empty() {
            "reality"
        } else if required {
            "tls"
        } else {
            ""
        },
    );
    if !required && (security == "none" || security.is_empty() && text(p, "pbk").is_empty()) {
        return None;
    }
    let mut v = json!({"enabled":true,"server_name":fallback(p,"sni",text(p,"host")),"insecure":yes(p,"insecure"),"utls":{"enabled":true,"fingerprint":fallback(p,"fp","chrome")}});
    if p["alpn"].as_array().is_some_and(|v| !v.is_empty()) {
        v["alpn"] = p["alpn"].clone();
    }
    if !text(p, "pbk").is_empty() {
        v["reality"] = json!({"enabled":true,"public_key":text(p,"pbk"),"short_id":text(p,"sid")});
    }
    Some(v)
}
fn outbound(p: &Value, tag: &str) -> Result<Value, String> {
    let protocol = text(p, "protocol");
    let mut v = json!({"type":protocol,"tag":tag,"server":text(p,"host"),"server_port":number(p,"port"),"connect_timeout":"10s"});
    if matches!(protocol, "vless" | "trojan" | "naive") {
        v["tcp_keep_alive"] = "30s".into();
        v["tcp_keep_alive_interval"] = "15s".into();
    }
    match protocol {
        "vless" | "vmess" => {
            v["uuid"] = p["uuid"].clone();
            if protocol == "vless" {
                if !text(p, "flow").is_empty() {
                    v["flow"] = p["flow"].clone();
                }
                v["packet_encoding"] = fallback(p, "packetEncoding", "xudp").into();
            } else {
                v["security"] = fallback(p, "encryption", "auto").into();
                v["alter_id"] = number(p, "alterId").into();
                if !text(p, "packetEncoding").is_empty() {
                    v["packet_encoding"] = p["packetEncoding"].clone();
                }
            }
        }
        "trojan" => v["password"] = p["uuid"].clone(),
        "hysteria2" => {
            v["password"] = p["uuid"].clone();
            v["tls"] = json!({"enabled":true,"server_name":fallback(p,"sni",text(p,"host")),"insecure":yes(p,"insecure"),"alpn":if p["alpn"].as_array().is_some_and(|v|!v.is_empty()){p["alpn"].clone()}else{json!(["h3"])}});
            if !text(p, "obfsType").is_empty() && !text(p, "obfsPassword").is_empty() {
                v["obfs"] = json!({"type":text(p,"obfsType"),"password":text(p,"obfsPassword")});
            }
            let ports = list(text(p, "serverPorts"));
            if !ports.is_empty() {
                v["server_ports"] = json!(ports);
                v["hop_interval"] = fallback(p, "hopInterval", "10s").into();
                if !text(p, "hopIntervalMax").is_empty() {
                    v["hop_interval_max"] = p["hopIntervalMax"].clone();
                }
            }
            for (k, to) in [("upMbps", "up_mbps"), ("downMbps", "down_mbps")] {
                if number(p, k) > 0 {
                    v[to] = number(p, k).into();
                }
            }
        }
        "shadowsocks" => {
            v["method"] = p["encryption"].clone();
            v["password"] = p["password"].clone();
            for (k, to) in [("plugin", "plugin"), ("pluginOptions", "plugin_opts")] {
                if !text(p, k).is_empty() {
                    v[to] = p[k].clone();
                }
            }
        }
        "socks" => {
            v["version"] = "5".into();
            for k in ["username", "password"] {
                if !text(p, k).is_empty() {
                    v[k] = p[k].clone();
                }
            }
        }
        "naive" => {
            v["username"] = p["username"].clone();
            v["password"] = p["password"].clone();
            v["quic"] = yes(p, "naiveQuic").into();
            v["tls"] = json!({"enabled":true,"server_name":fallback(p,"sni",text(p,"host")),"insecure":yes(p,"insecure")});
        }
        "tuic" => {
            v["uuid"] = p["uuid"].clone();
            v["password"] = text(p, "password").into();
            v["congestion_control"] = fallback(p, "congestionControl", "cubic").into();
            v["udp_relay_mode"] = fallback(p, "udpRelayMode", "native").into();
        }
        "hysteria" => {
            if !text(p, "uuid").is_empty() {
                v["auth_str"] = p["uuid"].clone();
            }
            if !text(p, "obfsPassword").is_empty() {
                v["obfs"] = p["obfsPassword"].clone();
            }
            for (k, to) in [("upMbps", "up_mbps"), ("downMbps", "down_mbps")] {
                if number(p, k) > 0 {
                    v[to] = number(p, k).into();
                }
            }
        }
        _ => return Err(format!("Unsupported protocol: {protocol}")),
    }
    if matches!(protocol, "vless" | "trojan" | "vmess" | "tuic" | "hysteria") {
        if let Some(t) = tls(p, matches!(protocol, "trojan" | "tuic" | "hysteria")) {
            v["tls"] = t;
        }
    }
    if matches!(protocol, "vless" | "trojan" | "vmess") {
        let kind = fallback(p, "transport", "tcp");
        if !supported_transport(kind) {
            return Err(format!("Unsupported transport: {kind}"));
        }
        if !matches!(kind, "tcp" | "raw") {
            let mut t = json!({"type":kind});
            if kind == "grpc" {
                t["service_name"] = text(p, "serviceName").into();
            } else {
                t["path"] = fallback(p, "path", "/").into();
            }
            if kind == "xhttp" {
                t["mode"] = fallback(p, "xhttpMode", "stream-one").into();
            }
            if !text(p, "hostHeader").is_empty() {
                match kind {
                    "ws" => t["headers"] = json!({"Host":text(p,"hostHeader")}),
                    "http" => t["host"] = json!([text(p, "hostHeader")]),
                    _ => t["host"] = p["hostHeader"].clone(),
                }
            }
            v["transport"] = t;
        }
    }
    Ok(v)
}
fn endpoint(p: &Value, tag: &str) -> Value {
    let mut peer = json!({"address":text(p,"host"),"port":number(p,"port"),"public_key":text(p,"peerPublicKey"),"allowed_ips":["0.0.0.0/0","::/0"]});
    if !text(p, "preSharedKey").is_empty() {
        peer["pre_shared_key"] = p["preSharedKey"].clone();
    }
    let reserved: Vec<_> = text(p, "reserved")
        .split([',', ';'])
        .filter_map(|v| v.trim().parse::<u8>().ok())
        .collect();
    if reserved.len() == 3 {
        peer["reserved"] = json!(reserved);
    }
    let mut v = json!({"type":"wireguard","tag":tag,"address":list(text(p,"localAddress")),"private_key":text(p,"privateKey"),"peers":[peer]});
    if number(p, "mtu") > 0 {
        v["mtu"] = number(p, "mtu").into();
    }
    v
}
fn is_ip(host: &str) -> bool {
    host.contains(':') || host.chars().all(|c| c.is_ascii_digit() || c == '.')
}
fn server_rule(p: &Value) -> Value {
    let host = text(p, "host");
    if is_ip(host) {
        json!({"ip_cidr":[if host.contains(':'){host.to_string()}else{format!("{host}/32")}],"action":"route","outbound":"direct"})
    } else {
        json!({"domain":[host],"action":"route","outbound":"direct"})
    }
}
pub(super) fn clean_domains(v: &Value) -> Vec<String> {
    let mut seen = HashSet::new();
    v.as_array()
        .into_iter()
        .flatten()
        .filter_map(|v| v.as_str())
        .map(|v| {
            let v = v.trim().to_lowercase();
            v.trim_start_matches("https://")
                .trim_start_matches("http://")
                .split(['/', ':'])
                .next()
                .unwrap_or("")
                .to_string()
        })
        .filter(|v| !v.is_empty() && seen.insert(v.clone()))
        .collect()
}
pub(super) fn selectable(
    input: &[Value],
    active: usize,
    settings: &Value,
) -> Result<Value, String> {
    let profiles: Vec<_> = input.iter().map(normalize).collect::<Result<_, _>>()?;
    let p = profiles
        .get(active)
        .ok_or("Active VPN profile is out of range")?;
    let assets = super::assets();
    let contract = &assets["contract"];
    let dns = &contract["dns"];
    let windows = &contract["platforms"]["windows"];
    let flow = &assets["flowDomains"];
    let suffix = &assets["flowSuffixes"];
    let ads = &assets["ads"];
    let raw_sites = clean_domains(&settings["sitesList"]);
    let sites: Vec<_> = raw_sites
        .into_iter()
        .filter(|d| {
            text(settings, "sitesMode") != "bypass"
                || d != "google.com"
                    && !assets["protectedDomains"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .any(|p| {
                            let p = p.as_str().unwrap();
                            d == p || d.ends_with(&format!(".{p}"))
                        })
        })
        .collect();
    let mut dns_rules = Vec::new();
    if !is_ip(text(p, "host")) {
        dns_rules.push(json!({"domain":[text(p,"host")],"action":"route","server":"local-dns"}));
    }
    if yes(settings, "adblock") {
        dns_rules.push(json!({"domain_suffix":ads,"action":"predefined","rcode":"NOERROR"}));
    }
    if !sites.is_empty() && matches!(text(settings, "sitesMode"), "bypass" | "only") {
        dns_rules.insert(0,json!({"domain_suffix":sites,"action":"route","server":if text(settings,"sitesMode")=="only"{"remote"}else{"local-dns"}}));
    }
    dns_rules.extend([json!({"domain":flow,"domain_suffix":suffix,"query_type":["A","AAAA"],"action":"route","server":"flow-dns"}),json!({"domain":flow,"domain_suffix":suffix,"query_type":["HTTPS"],"action":"predefined","rcode":"NOERROR"}),json!({"domain_suffix":["ru","xn--p1ai","su"],"query_type":["A","AAAA"],"action":"route","server":"flow-dns"}),json!({"domain_suffix":["ru","xn--p1ai","su"],"query_type":["HTTPS"],"action":"predefined","rcode":"NOERROR"})]);
    let mut hosts: HashSet<String> = dns_rules
        .iter()
        .flat_map(|r| {
            r["domain"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
                .map(str::to_string)
        })
        .collect();
    for p in &profiles {
        let host = text(p, "host");
        if !is_ip(host) && hosts.insert(host.into()) {
            dns_rules.insert(
                0,
                json!({"domain":[host],"action":"route","server":"local-dns"}),
            );
        }
    }
    let block_quic = yes(settings, "quic") || text(p, "protocol") == "naive";
    let reject =
        json!({"network":"udp","port":443,"action":"reject","method":"default","no_drop":true});
    let mut rules = Vec::new();
    if block_quic {
        let mut rule = reject.clone();
        rule["process_name"] = assets["browsers"].clone();
        rules.push(rule);
    }
    rules.push(json!({"inbound":["tun-in"],"action":"sniff","timeout":"300ms"}));
    let mut seen = HashSet::new();
    for p in &profiles {
        let rule = server_rule(p);
        if seen.insert(rule.to_string()) {
            rules.push(rule);
        }
    }
    rules.push(json!({"protocol":"dns","action":"hijack-dns"}));
    let mut google = reject.clone();
    google["domain"] = flow.clone();
    google["domain_suffix"] = suffix.clone();
    rules.push(google);
    rules.push(json!({"domain_suffix":contract["routing"]["healthDomainSuffixes"],"action":"route","outbound":"proxy"}));
    if block_quic {
        rules.push(reject);
    }
    if yes(settings, "adblock") {
        rules.push(json!({"domain_suffix":ads,"action":"reject"}));
    }
    if yes(settings, "lan") {
        rules.push(json!({"ip_is_private":true,"action":"route","outbound":"direct"}));
    }
    rules
        .push(json!({"domain_suffix":["ru","xn--p1ai","su"],"action":"route","outbound":"direct"}));
    let mut final_route = "proxy";
    let mut apps = HashSet::new();
    let apps: Vec<_> = settings["appsList"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(Value::as_str)
        .map(str::trim)
        .filter(|v| !v.is_empty() && apps.insert(v.to_string()))
        .collect();
    for (mode, values, key) in [
        (text(settings, "appsMode"), json!(apps), "process_name"),
        (text(settings, "sitesMode"), json!(sites), "domain_suffix"),
    ] {
        if values.as_array().is_some_and(|v| !v.is_empty()) && matches!(mode, "bypass" | "only") {
            let mut rule =
                json!({"action":"route","outbound":if mode=="only"{"proxy"}else{"direct"}});
            rule[key] = values;
            rules.push(rule);
            if mode == "only" {
                final_route = "direct";
            }
        }
    }
    rules.push(json!({"ip_cidr":["::/0"],"action":"reject"}));
    let tags: Vec<_> = (1..=profiles.len())
        .map(|i| format!("profile-{i}"))
        .collect();
    let mut outbounds = vec![
        json!({"type":"selector","tag":"proxy","outbounds":tags,"default":tags[active],"interrupt_exist_connections":true}),
    ];
    let mut endpoints = Vec::new();
    for (i, p) in profiles.iter().enumerate() {
        if text(p, "protocol") == "wireguard" {
            endpoints.push(endpoint(p, &tags[i]));
        } else {
            outbounds.push(outbound(p, &tags[i])?);
        }
    }
    outbounds.extend([
        json!({"type":"direct","tag":"direct"}),
        json!({"type":"block","tag":"block"}),
    ]);
    let mut tun = json!({"type":"tun","tag":"tun-in","interface_name":windows["interfaceName"],"address":windows["addresses"],"auto_route":true,"strict_route":windows["strictRoute"],"stack":windows["stack"],"mtu":if number(settings,"mtu")>0{settings["mtu"].clone()}else{windows["defaultMtu"].clone()}});
    let mut seen = HashSet::new();
    let addresses: Vec<_> = profiles
        .iter()
        .filter(|p| is_ip(text(p, "host")))
        .map(|p| {
            format!(
                "{}/{}",
                text(p, "host"),
                if text(p, "host").contains(':') {
                    128
                } else {
                    32
                }
            )
        })
        .filter(|v| seen.insert(v.clone()))
        .collect();
    if !addresses.is_empty() {
        tun["route_exclude_address"] = json!(addresses);
    }
    let mut config = json!({"log":{"level":"warn","timestamp":true},"dns":{"servers":[{"type":"fakeip","tag":"flow-dns","inet4_range":"198.18.0.0/15"},{"type":"https","tag":"remote","server":dns["remoteServer"],"server_port":dns["remotePort"],"path":dns["remotePath"],"detour":"proxy","tls":{"enabled":true,"server_name":dns["remoteTlsServerName"]}},{"type":"udp","tag":"local-dns","server":dns["localServer"],"server_port":dns["localPort"]}],"rules":dns_rules,"final":if text(settings,"sitesMode")=="only"&&!sites.is_empty(){"local-dns"}else{"remote"},"strategy":dns["strategy"]},"inbounds":[tun],"outbounds":outbounds,"route":{"rules":rules,"final":final_route,"auto_detect_interface":true,"default_domain_resolver":{"server":"local-dns","strategy":dns["strategy"]}}});
    if !endpoints.is_empty() {
        config["endpoints"] = json!(endpoints);
    }
    Ok(config)
}

pub(super) fn profile_key(p: &Value) -> String {
    let raw = text(p, "raw").trim();
    if !raw.is_empty() {
        return raw
            .split('#')
            .next()
            .unwrap()
            .replacen("hy2://", "hysteria2://", 1);
    }
    let keys = [
        "protocol",
        "host",
        "port",
        "uuid",
        "username",
        "password",
        "encryption",
        "alterId",
        "security",
        "sni",
        "pbk",
        "sid",
        "flow",
        "fp",
        "transport",
        "path",
        "hostHeader",
        "serviceName",
        "xhttpMode",
        "alpn",
        "packetEncoding",
        "insecure",
        "obfsType",
        "obfsPassword",
        "serverPorts",
        "hopInterval",
        "hopIntervalMax",
        "upMbps",
        "downMbps",
        "plugin",
        "pluginOptions",
        "privateKey",
        "peerPublicKey",
        "preSharedKey",
        "localAddress",
        "reserved",
        "mtu",
        "congestionControl",
        "udpRelayMode",
        "naiveQuic",
    ];
    json!(keys.iter().map(|k| p[*k].clone()).collect::<Vec<_>>()).to_string()
}
pub(super) fn single(profile: &Value, settings: &Value) -> Result<Value, String> {
    let mut value = selectable(std::slice::from_ref(profile), 0, settings)?;
    value["outbounds"].as_array_mut().unwrap().remove(0);
    for outbound in value["outbounds"].as_array_mut().unwrap() {
        if outbound["tag"] == "profile-1" {
            outbound["tag"] = "proxy".into();
        }
    }
    if let Some(endpoints) = value.get_mut("endpoints").and_then(Value::as_array_mut) {
        endpoints[0]["tag"] = "proxy".into();
    }
    Ok(value)
}
pub(super) fn runtime(
    profiles: &[Value],
    active: usize,
    settings: &Value,
    auto: bool,
) -> Result<(Value, Vec<usize>), String> {
    let target = profiles
        .get(active)
        .ok_or("Active VPN profile is out of range")?;
    if !auto {
        return Ok((
            selectable(std::slice::from_ref(target), 0, settings)?,
            vec![active],
        ));
    }
    let mut valid = Vec::new();
    let mut indexes = Vec::new();
    let mut selected = 0;
    for (i, p) in profiles.iter().enumerate() {
        match single(p, settings) {
            Ok(_) => {
                if i == active {
                    selected = valid.len();
                }
                valid.push(p.clone());
                indexes.push(i);
            }
            Err(error) => {
                if i == active {
                    return Err(error);
                }
            }
        }
    }
    Ok((selectable(&valid, selected, settings)?, indexes))
}

pub(super) fn share_link(p: &Value) -> Result<String, String> {
    if !text(p, "raw").trim().is_empty() {
        return Ok(text(p, "raw").trim().into());
    }
    let p = normalize(p)?;
    let encode = encode_component;
    let protocol = text(&p, "protocol");
    let host = text(&p, "host");
    let endpoint = format!(
        "{}:{}",
        if host.contains(':') {
            format!("[{host}]")
        } else {
            host.into()
        },
        number(&p, "port")
    );
    let fragment = format!("#{}", encode(fallback(&p, "name", host)));
    if protocol == "vmess" {
        let value = json!({"v":"2","ps":fallback(&p,"name",host),"add":host,"port":number(&p,"port").to_string(),"id":text(&p,"uuid"),"aid":number(&p,"alterId").to_string(),"scy":fallback(&p,"encryption","auto"),"net":fallback(&p,"transport","tcp"),"host":text(&p,"hostHeader"),"path":if text(&p,"transport")=="grpc"{text(&p,"serviceName")}else{text(&p,"path")},"tls":text(&p,"security"),"sni":text(&p,"sni"),"fp":text(&p,"fp"),"alpn":p["alpn"].as_array().map(|v|v.iter().filter_map(Value::as_str).collect::<Vec<_>>().join(",")).unwrap_or_default(),"mode":text(&p,"xhttpMode"),"packetEncoding":text(&p,"packetEncoding")});
        let body = [
            "v",
            "ps",
            "add",
            "port",
            "id",
            "aid",
            "scy",
            "net",
            "host",
            "path",
            "tls",
            "sni",
            "fp",
            "alpn",
            "mode",
            "packetEncoding",
        ]
        .iter()
        .map(|k| format!("{}:{}", json!(k), value[*k]))
        .collect::<Vec<_>>()
        .join(",");
        return Ok(format!(
            "vmess://{}",
            STANDARD.encode(format!("{{{body}}}"))
        ));
    }
    let mut pairs: Vec<(&str, String)> = Vec::new();
    let mut add = |from: &str, to: &'static str| {
        let v = if from == "alpn" {
            p["alpn"]
                .as_array()
                .map(|v| {
                    v.iter()
                        .filter_map(Value::as_str)
                        .collect::<Vec<_>>()
                        .join(",")
                })
                .unwrap_or_default()
        } else if from == "insecure" {
            if yes(&p, from) {
                "1".into()
            } else {
                String::new()
            }
        } else {
            text(&p, from).into()
        };
        if !v.is_empty() {
            pairs.push((to, v));
        }
    };
    let credentials;
    let scheme;
    match protocol {
        "shadowsocks" => {
            scheme = "ss";
            credentials = URL_SAFE_NO_PAD.encode(format!(
                "{}:{}",
                text(&p, "encryption"),
                text(&p, "password")
            ));
            if !text(&p, "plugin").is_empty() {
                let mut v = text(&p, "plugin").to_string();
                if !text(&p, "pluginOptions").is_empty() {
                    v.push(';');
                    v.push_str(text(&p, "pluginOptions"));
                }
                pairs.push(("plugin", v));
            }
        }
        "socks" => {
            scheme = "socks5";
            credentials = if text(&p, "username").is_empty() && text(&p, "password").is_empty() {
                String::new()
            } else {
                format!(
                    "{}:{}",
                    encode(text(&p, "username")),
                    encode(text(&p, "password"))
                )
            };
        }
        "wireguard" => {
            scheme = "wireguard";
            credentials = String::new();
            for (a, b) in [
                ("privateKey", "pk"),
                ("peerPublicKey", "peer_pk"),
                ("preSharedKey", "pre_shared_key"),
                ("localAddress", "local_address"),
                ("reserved", "reserved"),
            ] {
                add(a, b);
            }
            if number(&p, "mtu") > 0 {
                pairs.push(("mtu", number(&p, "mtu").to_string()));
            }
        }
        "naive" => {
            scheme = if yes(&p, "naiveQuic") {
                "naive+quic"
            } else {
                "naive+https"
            };
            credentials = format!(
                "{}:{}",
                encode(text(&p, "username")),
                encode(text(&p, "password"))
            );
            for k in ["sni", "alpn", "insecure"] {
                add(k, k);
            }
        }
        "hysteria" => {
            scheme = "hysteria";
            credentials = String::new();
            add("uuid", "auth");
            for k in ["sni", "alpn", "insecure"] {
                add(k, k);
            }
            add("obfsPassword", "obfs");
            for (a, b) in [("upMbps", "upmbps"), ("downMbps", "downmbps")] {
                if number(&p, a) > 0 {
                    pairs.push((b, number(&p, a).to_string()));
                }
            }
        }
        "hysteria2" => {
            scheme = "hysteria2";
            credentials = encode(text(&p, "uuid"));
            for (a, b) in [
                ("sni", "sni"),
                ("alpn", "alpn"),
                ("insecure", "insecure"),
                ("obfsType", "obfs"),
                ("obfsPassword", "obfs-password"),
                ("serverPorts", "server_ports"),
                ("hopInterval", "hop_interval"),
                ("hopIntervalMax", "hop_interval_max"),
            ] {
                add(a, b);
            }
            for (a, b) in [("upMbps", "up_mbps"), ("downMbps", "down_mbps")] {
                if number(&p, a) > 0 {
                    pairs.push((b, number(&p, a).to_string()));
                }
            }
        }
        "tuic" => {
            scheme = "tuic";
            credentials = format!(
                "{}:{}",
                encode(text(&p, "uuid")),
                encode(text(&p, "password"))
            );
            for (a, b) in [
                ("sni", "sni"),
                ("alpn", "alpn"),
                ("insecure", "insecure"),
                ("congestionControl", "congestion_control"),
                ("udpRelayMode", "udp_relay_mode"),
            ] {
                add(a, b);
            }
        }
        _ => {
            scheme = protocol;
            credentials = encode(text(&p, "uuid"));
            for (a, b) in [
                ("security", "security"),
                ("sni", "sni"),
                ("pbk", "pbk"),
                ("sid", "sid"),
                ("fp", "fp"),
                ("alpn", "alpn"),
                ("transport", "type"),
                ("hostHeader", "host"),
                ("path", "path"),
                ("serviceName", "serviceName"),
                ("xhttpMode", "mode"),
                ("packetEncoding", "packetEncoding"),
            ] {
                add(a, b);
            }
            if protocol == "vless" {
                add("flow", "flow");
            } else {
                add("insecure", "insecure");
            }
        }
    }
    let query = if pairs.is_empty() {
        String::new()
    } else {
        format!(
            "?{}",
            pairs
                .iter()
                .map(|(k, v)| format!("{}={}", encode(k), encode(v)))
                .collect::<Vec<_>>()
                .join("&")
        )
    };
    Ok(format!(
        "{scheme}://{}{endpoint}{query}{fragment}",
        if credentials.is_empty() {
            String::new()
        } else {
            format!("{credentials}@")
        }
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rust_config_preserves_legacy_regression_cases() {
        let cases: Vec<Value> =
            serde_json::from_str(include_str!("../../../test/fixtures/rust-config.json")).unwrap();
        fn difference(a: &Value, b: &Value, path: String) -> String {
            if let (Some(a), Some(b)) = (a.as_object(), b.as_object()) {
                for key in a.keys().chain(b.keys()) {
                    if a.get(key) != b.get(key) {
                        return difference(
                            a.get(key).unwrap_or(&Value::Null),
                            b.get(key).unwrap_or(&Value::Null),
                            format!("{path}/{key}"),
                        );
                    }
                }
            }
            if let (Some(a), Some(b)) = (a.as_array(), b.as_array()) {
                for i in 0..a.len().max(b.len()) {
                    if a.get(i) != b.get(i) {
                        return difference(
                            a.get(i).unwrap_or(&Value::Null),
                            b.get(i).unwrap_or(&Value::Null),
                            format!("{path}/{i}"),
                        );
                    }
                }
            }
            format!("{path}: {a} != {b}")
        }
        let mut failures = Vec::new();
        for (i, case) in cases.iter().enumerate() {
            let args = case["args"].as_array().unwrap();
            let empty = json!({});
            let settings = args.get(1).unwrap_or(&empty);
            let result: Result<Value, String> = match text(case, "name") {
                "parseProfileLink" => {
                    Ok(parse_link(args[0].as_str().unwrap()).unwrap_or(Value::Null))
                }
                "profileShareLink" => share_link(&args[0]).map(Value::String),
                "buildSingBoxConfig" => single(&args[0], settings),
                "buildSelectableSingBoxConfig" => {
                    let active = args.get(1).and_then(Value::as_u64).map(|v| v as usize);
                    if args.len() > 1 && active.is_none() {
                        Err("Invalid index".into())
                    } else {
                        let result = args[0]
                            .as_array()
                            .ok_or("Invalid profiles".into())
                            .and_then(|p| {
                                selectable(p, active.unwrap_or(0), args.get(2).unwrap_or(&empty))
                            });
                        result.map(|mut config|{if let Some(control)=args.get(3){if !text(control,"externalController").is_empty(){config["experimental"]=json!({"clash_api":{"external_controller":text(control,"externalController"),"secret":text(control,"secret")}});}}config})
                    }
                }
                "buildRuntimeSingBoxConfig" => args[0]
                    .as_array()
                    .ok_or("Invalid profiles".into())
                    .and_then(|p| {
                        runtime(
                            p,
                            args.get(1).and_then(Value::as_u64).unwrap_or(u64::MAX) as usize,
                            args.get(2).unwrap_or(&empty),
                            args.get(3).and_then(Value::as_bool).unwrap_or(false),
                        )
                    })
                    .map(|(config, indexes)| json!({"config":config,"profileIndexes":indexes})),
                name => panic!("Unknown case {name}"),
            };
            if case["error"] == true {
                if result.is_ok() {
                    failures.push(format!("Case {i} {} should fail", case["name"]));
                }
            } else {
                match result {
                    Ok(value) => {
                        if value != case["result"] {
                            failures.push(format!(
                                "Case {i} {} {}",
                                case["name"],
                                difference(&value, &case["result"], String::new())
                            ));
                        }
                    }
                    Err(error) => failures.push(format!("Case {i} {} {error}", case["name"])),
                }
            }
        }
        assert!(failures.is_empty(), "{}", failures.join("\n"));
    }
}
