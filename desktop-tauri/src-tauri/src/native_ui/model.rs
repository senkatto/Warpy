use super::{
    assets,
    config::{self, text},
};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub(super) enum Overlay {
    Profiles,
    Settings,
    Add,
    Share,
    Language,
    Running,
    Speed,
    Confirm,
    Message,
    Unsaved,
}
#[derive(Default, Clone)]
pub(super) struct Field {
    pub value: String,
    pub checked: bool,
    pub read_only: bool,
    pub multiline: bool,
    pub numeric: bool,
}
#[derive(Default, Clone)]
pub(super) struct Probe {
    pub delay: Option<u64>,
    pub checking: bool,
    pub checked_at: u64,
}
#[derive(Default)]
pub(super) struct Speed {
    pub running: bool,
    pub kind: String,
    pub started: f64,
    pub stage: String,
    pub value: String,
    pub unit: String,
    pub results: Option<[f64; 3]>,
    pub progress: f64,
}
pub(super) struct Ui {
    pub settings: Value,
    pub loaded: bool,
    pub fields: HashMap<String, Field>,
    pub overlays: HashSet<Overlay>,
    pub tunneling: bool,
    pub group: Option<String>,
    pub hover: String,
    pub focus: String,
    pub caret: usize,
    pub anchor: usize,
    pub scroll: HashMap<String, f32>,
    pub select: Option<(String, [f32; 4])>,
    pub tooltip: String,
    pub status: String,
    pub command: Option<String>,
    pub error: String,
    pub command_error: String,
    pub connected_unix: u64,
    pub animation_start: f64,
    pub connected_at: Option<f64>,
    pub alert_until: f64,
    pub visible: bool,
    pub speed: String,
    pub ping: Option<u64>,
    pub last_stats: Option<(u64, u64, f64)>,
    pub probes: HashMap<String, Probe>,
    pub kill_switch_status: String,
    pub processes: Vec<String>,
    pub selected_processes: HashSet<String>,
    pub processes_message: String,
    pub share: String,
    pub qr: String,
    pub message: String,
    pub confirm: String,
    pub confirming: Option<String>,
    pub busy: HashSet<String>,
    pub update: Option<Value>,
    pub update_status: String,
    pub dismissed_update: String,
    pub update_installing: bool,
    pub update_percent: Option<u64>,
    pub speedtest: Speed,
    pub version: String,
}
impl Ui {
    pub fn new() -> Self {
        let mut ui = Self {
            settings: json!({"schemaVersion":8,"profiles":[],"subscriptions":[],"active":0,"preferredProfileKey":"","adblock":false,"quic":false,"lan":false,"killSwitch":false,"resumeOnBoot":false,"mtu":0,"appsMode":"off","appsList":[],"sitesMode":"off","sitesList":[],"lang":system_language()}),
            loaded: false,
            fields: HashMap::new(),
            overlays: HashSet::new(),
            tunneling: false,
            group: None,
            hover: String::new(),
            focus: String::new(),
            caret: 0,
            anchor: 0,
            scroll: HashMap::new(),
            select: None,
            tooltip: String::new(),
            status: "stopped".into(),
            command: None,
            error: String::new(),
            command_error: String::new(),
            connected_unix: 0,
            animation_start: 0.0,
            connected_at: None,
            alert_until: 0.0,
            visible: true,
            speed: "—".into(),
            ping: None,
            last_stats: None,
            probes: HashMap::new(),
            kill_switch_status: "Off".into(),
            processes: Vec::new(),
            selected_processes: HashSet::new(),
            processes_message: String::new(),
            share: String::new(),
            qr: String::new(),
            message: String::new(),
            confirm: String::new(),
            confirming: None,
            busy: HashSet::new(),
            update: None,
            update_status: String::new(),
            dismissed_update: String::new(),
            update_installing: false,
            update_percent: None,
            speedtest: Speed {
                value: "0".into(),
                unit: "Мбит/с".into(),
                ..Default::default()
            },
            version: env!("CARGO_PKG_VERSION").into(),
        };
        for id in [
            "s-mtu",
            "s-apps-mode",
            "s-apps-list",
            "s-sites-mode",
            "s-sites-list",
            "s-lang",
            "s-quic",
            "s-lan",
            "s-kill-switch",
            "s-resume-on-boot",
            "share-link",
            "running-apps-search",
            "running-apps-exclude-system",
        ] {
            ui.fields.insert(
                id.into(),
                Field {
                    multiline: matches!(id, "s-apps-list" | "s-sites-list" | "share-link"),
                    numeric: id == "s-mtu",
                    read_only: id == "share-link",
                    ..Default::default()
                },
            );
        }
        ui.fields
            .get_mut("running-apps-exclude-system")
            .unwrap()
            .checked = true;
        ui.load_fields();
        ui
    }
    pub fn t(&self, key: &str) -> String {
        assets()["translations"][text(&self.settings, "lang")][key]
            .as_str()
            .unwrap_or(key)
            .into()
    }
    pub fn field(&self, id: &str) -> &Field {
        &self.fields[id]
    }
    pub fn profiles(&self) -> &[Value] {
        self.settings["profiles"]
            .as_array()
            .map(Vec::as_slice)
            .unwrap_or(&[])
    }
    pub fn active(&self) -> usize {
        self.settings["active"].as_u64().unwrap_or(0) as usize
    }
    pub fn profile(&self) -> Option<&Value> {
        self.profiles().get(self.active())
    }
    pub fn connected(&self) -> bool {
        self.status == "connected"
    }
    pub fn connecting(&self) -> bool {
        self.status == "connecting"
            || self.command.as_deref() == Some("start") && self.status == "stopped"
    }
    pub fn open(&self, overlay: Overlay) -> bool {
        self.overlays.contains(&overlay)
    }
    pub fn show(&mut self, overlay: Overlay) {
        self.overlays.insert(overlay);
        self.focus.clear();
        self.select = None;
        self.tooltip.clear();
        if overlay == Overlay::Settings {
            self.load_fields();
            self.tunneling = false;
            self.scroll.insert("settings-main-page".into(), 0.0);
        }
        if overlay == Overlay::Profiles {
            self.group = None;
            self.scroll.insert("profile-list".into(), 0.0);
        }
        if overlay == Overlay::Add {
            self.overlays.remove(&Overlay::Profiles);
        }
    }
    pub fn hide(&mut self, overlay: Overlay) {
        self.overlays.remove(&overlay);
        self.focus.clear();
        self.select = None;
        self.tooltip.clear();
        if overlay == Overlay::Profiles {
            self.group = None;
        }
    }
    pub fn message(&mut self, value: String) {
        self.message = value;
        self.show(Overlay::Message);
    }
    pub fn load_fields(&mut self) {
        for (key, id) in [
            ("quic", "s-quic"),
            ("lan", "s-lan"),
            ("killSwitch", "s-kill-switch"),
            ("resumeOnBoot", "s-resume-on-boot"),
        ] {
            self.fields.get_mut(id).unwrap().checked = self.settings[key] == true;
        }
        for (key, id) in [
            ("mtu", "s-mtu"),
            ("appsMode", "s-apps-mode"),
            ("sitesMode", "s-sites-mode"),
            ("lang", "s-lang"),
        ] {
            self.fields.get_mut(id).unwrap().value = if key == "mtu" {
                self.settings[key].as_u64().unwrap_or(0).to_string()
            } else {
                text(&self.settings, key).into()
            };
        }
        for (key, id) in [("appsList", "s-apps-list"), ("sitesList", "s-sites-list")] {
            self.fields.get_mut(id).unwrap().value = self.settings[key]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
                .collect::<Vec<_>>()
                .join(", ");
        }
    }
    pub fn draft(&self) -> Value {
        let mut settings = self.settings.clone();
        for (key, id) in [
            ("quic", "s-quic"),
            ("lan", "s-lan"),
            ("killSwitch", "s-kill-switch"),
            ("resumeOnBoot", "s-resume-on-boot"),
        ] {
            settings[key] = self.field(id).checked.into();
        }
        for (key, id) in [
            ("appsMode", "s-apps-mode"),
            ("sitesMode", "s-sites-mode"),
            ("lang", "s-lang"),
        ] {
            settings[key] = self.field(id).value.clone().into();
        }
        let mtu = self.field("s-mtu").value.parse::<u64>().unwrap_or(0);
        settings["mtu"] = if mtu == 0 { 0 } else { mtu.clamp(576, 1500) }.into();
        settings["appsList"] = json!(self
            .field("s-apps-list")
            .value
            .split(',')
            .map(str::trim)
            .filter(|v| !v.is_empty())
            .collect::<Vec<_>>());
        settings["sitesList"] = json!(config::clean_domains(&json!(self
            .field("s-sites-list")
            .value
            .split(',')
            .collect::<Vec<_>>())));
        settings
    }
    pub fn dirty_settings(&self) -> bool {
        self.draft() != self.settings
    }
    pub fn load_settings(&mut self, value: Value) -> Result<(), String> {
        if !value.is_object() {
            return Err("Invalid settings".into());
        }
        let mut settings = self.settings.clone();
        for (key, value) in value.as_object().unwrap() {
            settings[key] = value.clone();
        }
        settings["profiles"] = json!(value["profiles"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|p| p.is_object())
            .cloned()
            .collect::<Vec<_>>());
        let mut ids = HashSet::new();
        let mut urls = HashSet::new();
        let mut subscriptions = Vec::new();
        for entry in value["subscriptions"].as_array().into_iter().flatten() {
            if !entry.is_object() {
                continue;
            }
            let id = text(entry, "id").trim();
            let Some(url) = super::imports::subscription_url(text(entry, "url")) else {
                continue;
            };
            if id.is_empty() || !ids.insert(id.to_string()) || !urls.insert(url.to_string()) {
                continue;
            }
            let mut entry = entry.clone();
            entry["id"] = id.into();
            entry["url"] = url.as_str().into();
            if text(&entry, "name").trim().is_empty() {
                entry["name"] = super::imports::display_name(url.as_str())
                    .unwrap_or_default()
                    .into();
            }
            subscriptions.push(entry);
        }
        settings["subscriptions"] = json!(subscriptions);
        for key in ["adblock", "quic", "lan", "killSwitch", "resumeOnBoot"] {
            settings[key] = (settings[key] == true).into();
        }
        for key in ["appsMode", "sitesMode"] {
            if !matches!(text(&settings, key), "off" | "only" | "bypass") {
                settings[key] = "off".into();
            }
        }
        if !matches!(text(&settings, "lang"), "ru" | "en") {
            settings["lang"] = system_language().into();
        }
        let mtu = settings["mtu"]
            .as_u64()
            .or_else(|| settings["mtu"].as_str()?.parse().ok())
            .unwrap_or(0);
        settings["mtu"] = if mtu == 0 { 0 } else { mtu.clamp(576, 1500) }.into();
        settings["appsList"] = json!(settings["appsList"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(Value::as_str)
            .map(str::trim)
            .filter(|v| !v.is_empty())
            .collect::<Vec<_>>());
        settings["sitesList"] = json!(config::clean_domains(&settings["sitesList"]));
        for p in settings["profiles"].as_array_mut().unwrap() {
            p.as_object_mut().unwrap().remove("_index");
            if text(p, "name").is_empty() || text(p, "name").contains('?') {
                if let Some(parsed) = config::parse_link(text(p, "raw")) {
                    p["name"] = parsed["name"].clone();
                }
            }
        }
        let profiles = settings["profiles"].as_array().unwrap();
        let active = value["active"]
            .as_u64()
            .or_else(|| value["active"].as_str()?.parse().ok())
            .unwrap_or(0) as usize;
        let active = profiles
            .iter()
            .position(|p| config::profile_key(p) == text(&settings, "preferredProfileKey"))
            .unwrap_or(active.min(profiles.len().saturating_sub(1)));
        settings["preferredProfileKey"] = profiles
            .get(active)
            .map(config::profile_key)
            .unwrap_or_default()
            .into();
        settings["active"] = active.into();
        self.settings = settings;
        self.loaded = true;
        self.load_fields();
        Ok(())
    }
    pub fn service_snapshot(&mut self, snapshot: &Value, now: f64, unix: u64) {
        let status = text(snapshot, "status");
        let next = if status.starts_with("Connected") {
            "connected"
        } else if matches!(status, "Starting" | "Validating" | "Recovering") {
            "connecting"
        } else if status == "Stopped" && snapshot["desiredRunning"] != true {
            "stopped"
        } else {
            "error"
        };
        let was_active = self.connected() || self.connecting();
        let active = matches!(next, "connected" | "connecting");
        if active && !was_active {
            self.animation_start = now;
            self.connected_at = None;
        }
        if next == "connected" && self.connected_at.is_none() {
            self.connected_at = Some((now - self.animation_start) / 1000.0);
            self.connected_unix = unix;
        }
        if !active {
            self.connected_at = None;
            self.connected_unix = 0;
            self.last_stats = None;
            self.speed = "—".into();
            self.ping = None;
        }
        if !self.command_error.is_empty() {
            self.error = self.command_error.clone();
        } else if next == "error" {
            self.error = format!("{} ({status})", self.t("failedToConnect"));
        } else if next != "connecting" {
            self.error.clear();
        }
        self.status = next.into();
    }
    pub fn uptime(&self, unix: u64) -> String {
        let secs = unix.saturating_sub(self.connected_unix) / 1000;
        if secs >= 3600 {
            format!("{:02}:{:02}:{:02}", secs / 3600, secs / 60 % 60, secs % 60)
        } else {
            format!("{:02}:{:02}", secs / 60 % 60, secs % 60)
        }
    }
    pub fn profile_display(&self, p: &Value) -> (String, String) {
        let value = text(p, "name");
        let mut name = if value.is_empty() {
            text(p, "protocol").to_string()
        } else {
            value.to_string()
        };
        let mut country = String::new();
        let chars: Vec<_> = name.chars().collect();
        if chars.len() >= 2
            && chars[..2]
                .iter()
                .all(|c| (0x1f1e6..=0x1f1ff).contains(&(*c as u32)))
        {
            country = chars[..2]
                .iter()
                .map(|c| {
                    char::from_u32(*c as u32 - 127397)
                        .unwrap()
                        .to_ascii_lowercase()
                })
                .collect();
            name = chars[2..].iter().collect::<String>().trim().into();
        } else if chars.len() >= 2
            && chars[..2].iter().all(char::is_ascii_uppercase)
            && chars
                .get(2)
                .is_none_or(|c| !c.is_alphanumeric() && *c != '_')
        {
            country = chars[..2].iter().collect::<String>().to_lowercase();
            name = chars[2..].iter().collect::<String>().trim().into();
        } else {
            let lower = name.to_lowercase();
            for entry in assets()["countries"].as_array().unwrap() {
                if lower.contains(entry[0].as_str().unwrap()) {
                    country = entry[1].as_str().unwrap().into();
                    break;
                }
            }
        }
        if let Some(first) = name.chars().next() {
            name = format!("{}{}", first.to_uppercase(), &name[first.len_utf8()..]);
        }
        (name, country)
    }
    pub fn count_label(&self, count: usize) -> String {
        if text(&self.settings, "lang") == "ru" {
            let noun = if count % 10 == 1 && count % 100 != 11 {
                "сервер"
            } else if (2..=4).contains(&(count % 10)) && !(12..=14).contains(&(count % 100)) {
                "сервера"
            } else {
                "серверов"
            };
            format!("{count} {noun}")
        } else {
            format!("{count} {}", if count == 1 { "server" } else { "servers" })
        }
    }
    pub fn visible_processes(&self) -> Vec<String> {
        let query = self.field("running-apps-search").value.to_lowercase();
        self.processes
            .iter()
            .filter(|p| p.to_lowercase().contains(&query))
            .cloned()
            .collect()
    }
}

fn system_language() -> &'static str {
    if unsafe { windows::Win32::Globalization::GetUserDefaultUILanguage() } & 0x3ff == 0x19 {
        "ru"
    } else {
        "en"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn drafts_preserve_unrelated_settings_and_do_not_mutate_saved_values() {
        let mut ui = Ui::new();
        ui.load_settings(json!({"profiles":[],"customFutureSetting":17}))
            .unwrap();
        ui.field("s-mtu");
        ui.fields.get_mut("s-mtu").unwrap().value = "9999".into();
        assert!(ui.dirty_settings());
        assert_eq!(ui.draft()["mtu"], 1500);
        assert_eq!(ui.draft()["customFutureSetting"], 17);
        assert_eq!(ui.settings["mtu"], 0);
        ui.load_fields();
        assert!(!ui.dirty_settings());
    }
    #[test]
    fn saved_profile_keys_and_flags_survive_the_port() {
        let p = json!({"name":"🇳🇱 Amsterdam","protocol":"vless","host":"192.0.2.1","port":443,"uuid":"id"});
        let key = config::profile_key(&p);
        let mut ui = Ui::new();
        ui.load_settings(json!({"profiles":[p.clone()],"preferredProfileKey":key,"lang":"en"}))
            .unwrap();
        assert_eq!(ui.profile_display(&p), ("Amsterdam".into(), "nl".into()));
        assert_eq!(text(&ui.settings, "preferredProfileKey"), key);
        assert_eq!(ui.active(), 0);
    }
    #[test]
    fn service_recovery_preserves_animation_and_authoritative_state() {
        let mut ui = Ui::new();
        ui.service_snapshot(&json!({"status":"Starting"}), 1000.0, 0);
        ui.service_snapshot(&json!({"status":"Connected"}), 2000.0, 6000);
        assert_eq!(ui.animation_start, 1000.0);
        assert_eq!(ui.connected_at, Some(1.0));
        ui.service_snapshot(
            &json!({"status":"Recovering","desiredRunning":true}),
            3000.0,
            7000,
        );
        assert!(ui.connecting());
        ui.service_snapshot(
            &json!({"status":"Stopped","desiredRunning":true}),
            4000.0,
            0,
        );
        assert_eq!(ui.status, "error");
    }
}
