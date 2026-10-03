use super::{
    config::{self, text},
    imports, measurements,
    model::{Overlay, Probe, Speed, Ui},
    painter::{Hit, Scene},
    view, window, Message, Sender,
};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    sync::mpsc,
    time::{Instant, SystemTime, UNIX_EPOCH},
};
use tauri::Manager;

pub(super) fn unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

enum Backend {
    Native(tauri::AppHandle),
    #[cfg(test)]
    Test(Vec<(u32, String, Value)>),
}

enum Purpose {
    Load,
    Bootstrap,
    Snapshot(u64),
    StartedAt(u64),
    Metrics(u64),
    KillSwitch,
    Ignore,
    Health,
    Start(u64),
    VerifyStart(u64),
    Cancel(u64, bool),
    Stop(u64, bool),
    VerifyStop(u64, bool),
    Save(Value, AfterSave),
    Clipboard,
    Copied,
    Browse,
    Processes(u64),
    Subscription {
        url: String,
        name: String,
        id: String,
        automatic: bool,
    },
    Probes(Vec<String>),
    Update(bool),
    Install,
    Diagnostics,
}
enum AfterSave {
    Settings(bool),
    Profiles(bool),
    Import(usize, bool),
    Duplicate(bool),
    None,
}

#[derive(Default)]
struct Attempts(u64);
impl Attempts {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(1);
        self.0
    }
    fn current(&self, value: u64) -> bool {
        self.0 == value
    }
}

pub(super) struct Controller {
    pub ui: Ui,
    backend: Backend,
    sender: Sender,
    receiver: mpsc::Receiver<Message>,
    clock: Instant,
    preview: bool,
    dirty: bool,
    request_id: u32,
    pending: HashMap<u32, Purpose>,
    attempts: Attempts,
    next_status: f64,
    next_metrics: f64,
    next_ping: f64,
    next_update: f64,
    next_subscription: f64,
    next_uptime: f64,
    status_pending: bool,
    metrics_pending: bool,
    status_failures: u32,
    bootstrap_pending: bool,
    runtime_keys: Vec<String>,
    tray_signature: String,
    tray_dirty: bool,
    recovery_pending: bool,
    ping_generation: u64,
    ping_task: Option<tauri::async_runtime::JoinHandle<()>>,
    speed_task: Option<tauri::async_runtime::JoinHandle<()>>,
    speed_generation: u64,
    process_generation: u64,
    boot_retry: Option<(f64, usize, u64)>,
}

impl Controller {
    pub(super) fn new(
        app: tauri::AppHandle,
        sender: Sender,
        receiver: mpsc::Receiver<Message>,
        preview: bool,
    ) -> Result<Self, String> {
        Ok(Self::with_backend(
            Backend::Native(app),
            sender,
            receiver,
            preview,
        ))
    }
    fn with_backend(
        backend: Backend,
        sender: Sender,
        receiver: mpsc::Receiver<Message>,
        preview: bool,
    ) -> Self {
        let mut value = Self {
            ui: Ui::new(),
            backend,
            sender,
            receiver,
            clock: Instant::now(),
            preview,
            dirty: true,
            request_id: 0,
            pending: HashMap::new(),
            attempts: Attempts::default(),
            next_status: 2000.,
            next_metrics: 0.,
            next_ping: 0.,
            next_update: 12000.,
            next_subscription: 30000.,
            next_uptime: 0.,
            status_pending: false,
            metrics_pending: false,
            status_failures: 0,
            bootstrap_pending: true,
            runtime_keys: Vec::new(),
            tray_signature: String::new(),
            tray_dirty: true,
            recovery_pending: false,
            ping_generation: 0,
            ping_task: None,
            speed_task: None,
            speed_generation: 0,
            process_generation: 0,
            boot_retry: None,
        };
        value.request("load_settings", json!({}), Purpose::Load);
        value
    }
    fn request(&mut self, command: &str, args: Value, purpose: Purpose) {
        self.request_id = self.request_id.wrapping_add(1);
        let id = self.request_id;
        self.pending.insert(id, purpose);
        match &mut self.backend {
            Backend::Native(app) => {
                let app = app.clone();
                let sender = self.sender.clone();
                let command = command.to_string();
                let preview = self.preview;
                let owner = sender.hwnd.load(std::sync::atomic::Ordering::Acquire);
                tauri::async_runtime::spawn(async move {
                    let result = super::invoke(app, &command, args, preview, owner).await;
                    sender.send(Message::Reply(id, result));
                });
            }
            #[cfg(test)]
            Backend::Test(requests) => requests.push((id, command.into(), args)),
        }
    }
    pub(super) fn now(&self) -> f64 {
        self.clock.elapsed().as_secs_f64() * 1000.0
    }
    pub(super) fn mark_dirty(&mut self) {
        self.dirty = true;
    }
    pub(super) fn scene(&mut self) -> Result<Option<Scene>, String> {
        if !self.dirty {
            return Ok(None);
        }
        self.dirty = false;
        Ok(Some(view::build(&self.ui, self.now(), unix())))
    }
    pub(super) fn next_wake(&self) -> u32 {
        let mut at = self
            .next_status
            .min(self.next_update)
            .min(self.next_subscription);
        if self.ui.visible && self.ui.connected() {
            at = at
                .min(self.next_metrics)
                .min(self.next_ping)
                .min(self.next_uptime);
        }
        if let Some((retry, _, _)) = self.boot_retry {
            at = at.min(retry);
        }
        if self.ui.alert_until > self.now() {
            at = at.min(self.ui.alert_until);
        }
        (at - self.now()).clamp(10., 2000.) as u32
    }
    pub(super) fn set_visible(&mut self, visible: bool) {
        self.ui.visible = visible;
        self.ui.hover.clear();
        self.ui.tooltip.clear();
        self.ui.select = None;
        if visible {
            self.next_status = 0.;
            self.next_metrics = 0.;
            self.next_ping = 0.;
            self.mark_dirty();
        } else {
            self.abort_ping();
            self.close_speed();
            self.ui.last_stats = None;
        }
    }
    pub(super) fn drain(&mut self) -> Vec<String> {
        let mut commands = Vec::new();
        while let Ok(message) = self.receiver.try_recv() {
            match message {
                Message::Reply(id, result) => {
                    if let Some(purpose) = self.pending.remove(&id) {
                        self.reply(purpose, result);
                    }
                }
                Message::Event(name, payload) => self.event(&name, payload),
                Message::Window(command) => commands.push(command),
            }
        }
        self.tick();
        self.sync_tray();
        commands
    }
    fn log(&self, message: String) {
        match &self.backend {
            Backend::Native(app) => super::super::log_message(app.clone(), message),
            #[cfg(test)]
            Backend::Test(_) => {}
        }
    }
    fn show_error(&mut self, key: &str, error: &str) {
        self.ui.message(format!("{}{error}", self.ui.t(key)));
        self.dirty = true;
    }
    fn snapshot(&mut self, purpose: Purpose) {
        self.status_pending = true;
        self.request("get_vpn_runtime_snapshot", json!({}), purpose);
    }
    fn apply_snapshot(&mut self, snapshot: &Value, alert: bool) {
        let old = self.ui.status.clone();
        self.tray_dirty = true;
        let now = self.now();
        if text(snapshot, "status").starts_with("Connected") {
            self.ui.command_error.clear();
        }
        self.ui.service_snapshot(snapshot, now, unix());
        if self.ui.connected() {
            if old != "connected" {
                self.request(
                    "get_vpn_started_at",
                    json!({}),
                    Purpose::StartedAt(self.attempts.0),
                );
                self.next_metrics = 0.;
                self.next_ping = 0.;
            }
            if alert {
                self.ui.alert_until = now + 1250.;
            }
            if self.recovery_pending {
                self.notification(
                    "notificationVpnRestoredTitle",
                    "notificationVpnRestoredBody",
                );
            }
            self.recovery_pending = false;
        } else {
            self.abort_ping();
            if old == "connected" && self.ui.connecting() {
                self.recovery_pending = true;
            }
            if self.ui.status == "error" && matches!(old.as_str(), "connected" | "connecting") {
                self.notification("notificationVpnFailedTitle", "notificationVpnFailedBody");
                self.recovery_pending = false;
            }
        }
        self.request("get_kill_switch_status", json!({}), Purpose::KillSwitch);
        self.dirty = true;
    }
    fn notification(&mut self, title: &str, body: &str) {
        if !self.preview {
            self.request(
                "native_notification",
                json!({"title":self.ui.t(title),"body":self.ui.t(body)}),
                Purpose::Ignore,
            );
        }
    }
    fn reply(&mut self, purpose: Purpose, result: Result<Value, String>) {
        match purpose {
            Purpose::Load => {
                self.tray_dirty = true;
                let result = result
                    .and_then(|value| {
                        serde_json::from_str(value.as_str().ok_or("Invalid settings")?)
                            .map_err(|e| e.to_string())
                    })
                    .and_then(|value| self.ui.load_settings(value));
                if let Err(error) = result {
                    self.log(format!("Settings load failed: {error}"));
                    self.ui.message(self.ui.t("settingsLoadError"));
                }
                self.snapshot(Purpose::Bootstrap);
            }
            Purpose::Bootstrap => {
                self.status_pending = false;
                self.bootstrap_pending = false;
                match result {
                    Ok(value) => {
                        self.apply_snapshot(&value, false);
                        if self.ui.connected() {
                            if let Some(p) = self.ui.profile() {
                                self.runtime_keys = vec![config::profile_key(p)];
                            }
                        }
                        let flags = match &self.backend {
                            Backend::Native(app) => {
                                let state = app.state::<super::super::AppState>();
                                Some((state.autostart_launch, state.post_update_launch))
                            }
                            #[cfg(test)]
                            Backend::Test(_) => None,
                        };
                        if let Some((autostart, post_update)) =
                            flags.filter(|_| !self.preview && self.ui.loaded)
                        {
                            self.request(
                                "set_resume_on_boot",
                                json!({"enabled":self.ui.settings["resumeOnBoot"]==true}),
                                Purpose::Ignore,
                            );
                            if autostart
                                && self.ui.settings["resumeOnBoot"] == true
                                && self.ui.status == "stopped"
                                && !self.ui.profiles().is_empty()
                            {
                                self.ui.command = Some("start".into());
                                self.boot_retry = Some((self.now() + 5000., 0, self.attempts.0));
                            }
                            if post_update {
                                self.request("confirm_launch_health", json!({}), Purpose::Health);
                            }
                        }
                    }
                    Err(error) => {
                        self.ui.error = self.ui.t("serviceUnavailable");
                        self.log(format!("Backend restore failed: {error}"));
                    }
                }
            }
            Purpose::Snapshot(generation) => {
                self.status_pending = false;
                if self.attempts.current(generation) && self.ui.command.is_none() {
                    match result {
                        Ok(value) => {
                            self.status_failures = 0;
                            self.apply_snapshot(&value, false);
                        }
                        Err(error) => {
                            self.status_failures += 1;
                            if self.status_failures >= 3 {
                                self.ui.error = self.ui.t("serviceUnavailable");
                            }
                            self.log(format!("Status check failed: {error}"));
                        }
                    }
                }
            }
            Purpose::StartedAt(generation) => {
                if self.attempts.current(generation) && self.ui.connected() {
                    if let Ok(value) = result {
                        if let Some(at) = value.as_u64().filter(|at| *at > 0 && *at <= unix()) {
                            self.ui.connected_unix = at;
                        }
                    }
                }
            }
            Purpose::Metrics(generation) => {
                self.metrics_pending = false;
                if self.attempts.current(generation) && self.ui.connected() && self.ui.visible {
                    match result {
                        Ok(value) if value["available"] == true => {
                            let rx = value["received"].as_u64().unwrap_or(0);
                            let tx = value["transmitted"].as_u64().unwrap_or(0);
                            let now = self.now();
                            self.ui.speed = self
                                .ui
                                .last_stats
                                .map(|(old_rx, old_tx, at)| {
                                    format!(
                                        "{:.0}",
                                        rx.saturating_sub(old_rx).max(tx.saturating_sub(old_tx))
                                            as f64
                                            / ((now - at) / 1000.).max(0.1)
                                            / 1024.
                                    )
                                })
                                .unwrap_or_else(|| "0".into());
                            self.ui.last_stats = Some((rx, tx, now));
                        }
                        _ => {
                            self.ui.last_stats = None;
                            self.ui.speed = "—".into();
                        }
                    }
                }
            }
            Purpose::KillSwitch => {
                self.ui.kill_switch_status = result
                    .ok()
                    .and_then(|v| v.as_str().map(str::to_string))
                    .unwrap_or_else(|| "Off".into())
            }
            Purpose::Start(generation) => {
                if self.attempts.current(generation) {
                    match result {
                        Ok(_) => self.snapshot(Purpose::VerifyStart(generation)),
                        Err(error) => self.connection_error(error),
                    }
                }
            }
            Purpose::VerifyStart(generation) => {
                self.status_pending = false;
                if self.attempts.current(generation) {
                    self.ui.command = None;
                    match result {
                        Ok(value) if text(&value, "status").starts_with("Connected") => {
                            self.apply_snapshot(&value, true);
                            self.boot_retry = None;
                        }
                        Ok(value) => {
                            self.apply_snapshot(&value, false);
                            if !self.ui.connecting() {
                                self.connection_error(format!(
                                    "{} ({})",
                                    self.ui.t("failedToConnect"),
                                    text(&value, "status")
                                ));
                            }
                        }
                        Err(error) => self.connection_error(error),
                    }
                }
            }
            Purpose::Cancel(generation, restart) => {
                if self.attempts.current(generation) {
                    match result {
                        Ok(_) => {
                            self.request("stop_vpn", json!({}), Purpose::Stop(generation, restart))
                        }
                        Err(error) => self.connection_error(error),
                    }
                }
            }
            Purpose::Stop(generation, restart) => {
                if self.attempts.current(generation) {
                    match result {
                        Ok(_) => self.snapshot(Purpose::VerifyStop(generation, restart)),
                        Err(error) => self.connection_error(error),
                    }
                }
            }
            Purpose::VerifyStop(generation, restart) => {
                self.status_pending = false;
                if self.attempts.current(generation) {
                    self.ui.command = None;
                    match result {
                        Ok(value) => {
                            self.apply_snapshot(&value, false);
                            if text(&value, "status") == "Stopped" {
                                self.runtime_keys.clear();
                                if restart && !self.ui.profiles().is_empty() {
                                    self.start();
                                }
                            } else {
                                self.connection_error(format!(
                                    "{} ({})",
                                    self.ui.t("failedToDisconnect"),
                                    text(&value, "status")
                                ));
                            }
                        }
                        Err(error) => self.connection_error(error),
                    }
                }
            }
            Purpose::Save(next, after) => {
                self.ui.busy.remove("save");
                match result {
                    Ok(_) => {
                        let previous = self.ui.settings.clone();
                        self.ui.settings = next;
                        self.ui.load_fields();
                        self.after_save(previous, after);
                    }
                    Err(error) => {
                        self.ui.busy.remove("import");
                        self.show_error("settingsSaveError", &error);
                    }
                }
            }
            Purpose::Clipboard => match result {
                Ok(value) => self.import_text(value.as_str().unwrap_or("")),
                Err(error) => {
                    self.ui.busy.remove("import");
                    self.show_error("clipboardError", &error);
                }
            },
            Purpose::Copied => match result {
                Ok(_) => self.ui.message(self.ui.t("copySuccess")),
                Err(error) => self.show_error("clipboardError", &error),
            },
            Purpose::Browse => match result {
                Ok(value) => {
                    if let Some(path) = value.as_str() {
                        if let Some(name) = path.rsplit(['\\', '/']).next() {
                            let mut list = self
                                .ui
                                .field("s-apps-list")
                                .value
                                .split(',')
                                .map(str::trim)
                                .filter(|v| !v.is_empty())
                                .map(str::to_string)
                                .collect::<Vec<_>>();
                            if !list.iter().any(|v| v.eq_ignore_ascii_case(name)) {
                                list.push(name.into());
                            }
                            self.ui.fields.get_mut("s-apps-list").unwrap().value = list.join(", ");
                        }
                    }
                }
                Err(error) => self.show_error("fileSelectError", &error),
            },
            Purpose::Processes(generation) => {
                if generation == self.process_generation {
                    match result {
                        Ok(value) => {
                            let mut list = value
                                .as_array()
                                .into_iter()
                                .flatten()
                                .filter_map(|p| p.as_str().or_else(|| p["name"].as_str()))
                                .map(str::to_string)
                                .collect::<Vec<_>>();
                            list.extend(
                                self.ui
                                    .field("s-apps-list")
                                    .value
                                    .split(',')
                                    .map(str::trim)
                                    .filter(|v| !v.is_empty())
                                    .map(str::to_string),
                            );
                            list.sort_by_key(|v| v.to_lowercase());
                            list.dedup_by(|a, b| a.eq_ignore_ascii_case(b));
                            list.sort_by_key(|name| {
                                (
                                    !self
                                        .ui
                                        .selected_processes
                                        .iter()
                                        .any(|selected| selected.eq_ignore_ascii_case(name)),
                                    name.to_lowercase(),
                                )
                            });
                            self.ui.processes = list;
                            self.ui.processes_message.clear();
                        }
                        Err(error) => {
                            self.ui.processes_message =
                                format!("{}{error}", self.ui.t("errorLabel"))
                        }
                    }
                }
            }
            Purpose::Subscription {
                url,
                name,
                id,
                automatic,
            } => {
                self.ui.busy.remove(&format!("subscription:{id}"));
                if automatic
                    && !self.ui.settings["subscriptions"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .any(|s| text(s, "id") == id)
                {
                    return;
                }
                let parsed = result.and_then(|value| imports::parse_payload(text(&value, "body")));
                match parsed {
                    Ok(parsed) => {
                        self.subscription_result(url, name, id, parsed.profiles, automatic)
                    }
                    Err(error) => {
                        self.ui.busy.remove("import");
                        if !automatic {
                            self.show_error("subscriptionError", &error);
                        } else {
                            self.log(format!("Subscription refresh failed: {error}"));
                        }
                        if let Some(index) = self.ui.settings["subscriptions"]
                            .as_array()
                            .unwrap()
                            .iter()
                            .position(|s| text(s, "id") == id)
                        {
                            let mut next = self.ui.settings.clone();
                            next["subscriptions"][index]["lastStatus"] = "error".into();
                            next["subscriptions"][index]["lastCheckedAt"] = unix().into();
                            self.save(next, AfterSave::None);
                        }
                    }
                }
            }
            Purpose::Probes(keys) => {
                self.ui.busy.remove("probes");
                let at = unix();
                for key in &keys {
                    self.ui.probes.insert(
                        key.clone(),
                        Probe {
                            checked_at: at,
                            ..Default::default()
                        },
                    );
                }
                if let Ok(value) = result {
                    for result in value.as_array().into_iter().flatten() {
                        let index = result["index"].as_u64().unwrap_or(u64::MAX) as usize;
                        if let Some(key) = keys.get(index) {
                            self.ui.probes.insert(
                                key.clone(),
                                Probe {
                                    delay: result["delayMs"].as_u64(),
                                    checked_at: at,
                                    checking: false,
                                },
                            );
                        }
                    }
                }
            }
            Purpose::Update(silent) => {
                self.ui.busy.remove("update");
                match result {
                    Ok(Value::Null) => {
                        self.ui.update = None;
                        if !silent {
                            self.ui.update_status = self.ui.t("updateLatest");
                        }
                    }
                    Ok(value) => {
                        self.ui.update_status = format!(
                            "{} {}",
                            self.ui.t(if value["rollback"] == true {
                                "updateRollbackAvailable"
                            } else {
                                "updateAvailable"
                            }),
                            text(&value, "version")
                        );
                        self.ui.update = Some(value);
                    }
                    Err(error) => {
                        if !silent {
                            self.ui.update_status = self.ui.t("updateFailed");
                        }
                        self.log(format!("Update check failed: {error}"));
                    }
                }
            }
            Purpose::Install => {
                if let Err(error) = result {
                    self.ui.update_installing = false;
                    self.ui.update_status = self.ui.t("updateInstallFailed");
                    self.log(format!("Update installation failed: {error}"));
                }
            }
            Purpose::Diagnostics => match result {
                Ok(path) => self.ui.message(format!(
                    "{}: {}",
                    self.ui.t("diagnosticsSaved"),
                    path.as_str().unwrap_or("")
                )),
                Err(error) => self.show_error("diagnosticsError", &error),
            },
            Purpose::Health => {
                if let Err(error) = result {
                    self.log(format!("Launch health confirmation failed: {error}"));
                }
            }
            Purpose::Ignore => {
                if let Err(error) = result {
                    self.log(error);
                }
            }
        }
        self.dirty = true;
    }
    fn tick(&mut self) {
        let now = self.now();
        if now >= self.next_status {
            self.next_status = now + 2000.;
            if !self.status_pending && !self.bootstrap_pending && self.ui.command.is_none() {
                self.snapshot(Purpose::Snapshot(self.attempts.0));
            }
        }
        if self.ui.visible && self.ui.connected() {
            if now >= self.next_metrics {
                self.next_metrics = now + 1500.;
                if !self.metrics_pending {
                    self.metrics_pending = true;
                    self.request(
                        "get_vpn_network_stats",
                        json!({}),
                        Purpose::Metrics(self.attempts.0),
                    );
                }
            }
            if now >= self.next_ping {
                self.next_ping = now + 10000.;
                if self.ping_task.is_none() && !self.preview {
                    let sender = self.sender.clone();
                    self.ping_generation = self.ping_generation.wrapping_add(1);
                    let generation = self.ping_generation;
                    self.ping_task = Some(tauri::async_runtime::spawn(async move {
                        let result = measurements::ping().await;
                        sender.send(Message::Event(
                            "native://ping".into(),
                            json!({"generation":generation,"result":result}),
                        ));
                    }));
                }
            }
            if now >= self.next_uptime {
                self.next_uptime = now + 1000.;
                self.dirty = true;
            }
        }
        if self.ui.alert_until > 0. && now >= self.ui.alert_until {
            self.ui.alert_until = 0.;
            self.dirty = true;
        }
        if now >= self.next_update {
            self.next_update = now + 15. * 60. * 1000.;
            if !self.preview {
                self.check_update(true);
            }
        }
        if now >= self.next_subscription {
            self.next_subscription = now + 5. * 60. * 1000.;
            if !self.preview
                && self.ui.status == "stopped"
                && self.ui.command.is_none()
                && !self.ui.busy.contains("save")
                && !self.ui.busy.contains("import")
                && !self
                    .pending
                    .values()
                    .any(|purpose| matches!(purpose, Purpose::Subscription { .. }))
            {
                if let Some(subscription) = self.ui.settings["subscriptions"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .find(|s| {
                        imports::refresh_due(s, unix())
                            && !self
                                .ui
                                .busy
                                .contains(&format!("subscription:{}", text(s, "id")))
                    })
                    .cloned()
                {
                    self.refresh_subscription(subscription, true);
                    self.next_subscription = now + 1000.;
                }
            }
        }
        if let Some((at, index, generation)) = self.boot_retry {
            if !self.attempts.current(generation)
                || self.ui.settings["resumeOnBoot"] != true
                || self.ui.connected()
            {
                self.boot_retry = None;
            } else if now >= at
                && self.ui.command.as_deref() != Some("stop")
                && (!self
                    .pending
                    .values()
                    .any(|p| matches!(p, Purpose::Start(_) | Purpose::VerifyStart(_))))
            {
                self.start();
                let delays = [5000., 10000., 15000.];
                self.boot_retry = delays
                    .get(index)
                    .map(|delay| (now + delay, index + 1, self.attempts.0));
            }
        }
    }
    fn sync_tray(&mut self) {
        if !self.tray_dirty {
            return;
        }
        self.tray_dirty = false;
        let profiles=self.ui.profiles().iter().map(|p|{let(name,country)=self.ui.profile_display(p);json!({"name":name,"countryCode":if country.is_empty(){None}else{Some(country)},"group":if text(p,"group").is_empty(){None}else{Some(text(p,"group"))}})}).collect::<Vec<_>>();
        let snapshot = json!({"status":if self.ui.command.as_deref()==Some("stop"){"connecting"}else{&self.ui.status},"language":text(&self.ui.settings,"lang"),"active":self.ui.active(),"profiles":profiles});
        let signature = snapshot.to_string();
        if signature != self.tray_signature {
            self.tray_signature = signature;
            self.request(
                "update_tray_menu",
                json!({"snapshot":snapshot}),
                Purpose::Ignore,
            );
        }
    }
    fn event(&mut self, name: &str, payload: Value) {
        match name {
            "warpy://tray-command" => match text(&payload, "type") {
                "toggle" => self.toggle(),
                "selectProfile" => {
                    if let Some(index) = payload["index"].as_u64() {
                        self.select_profile(index as usize);
                    }
                }
                _ => {}
            },
            "warpy://update-progress" if self.ui.update_installing => {
                self.ui.update_percent = payload["percent"]
                    .as_f64()
                    .filter(|v| v.is_finite())
                    .map(|v| v.clamp(0., 100.).round() as u64);
                self.ui.update_status = format!(
                    "{}{}",
                    self.ui.t("updateInstalling"),
                    self.ui
                        .update_percent
                        .map(|v| format!(" {v}%"))
                        .unwrap_or_default()
                );
            }
            "native://ping"
                if payload["generation"].as_u64() == Some(self.ping_generation)
                    && self.ui.visible
                    && self.ui.connected() =>
            {
                self.ping_task = None;
                self.ui.ping = payload["result"]["Ok"]
                    .as_f64()
                    .map(|v| v.round().max(1.) as u64);
            }
            "native://speed-progress"
                if payload["generation"].as_u64() == Some(self.speed_generation)
                    && self.ui.speedtest.running =>
            {
                self.ui.speedtest.kind = text(&payload, "stage").to_lowercase();
                self.ui.speedtest.stage = match text(&payload, "stage") {
                    "DOWNLOAD" => "▼ ▼ ▼",
                    "UPLOAD" => "▲ ▲ ▲",
                    _ => "...",
                }
                .into();
                let value = payload["value"].as_f64().unwrap_or(0.);
                self.ui.speedtest.value = format!("{value:.0}");
                self.ui.speedtest.progress = (value / 300.).clamp(0., 1.);
                self.ui.speedtest.unit = self.ui.t(if self.ui.speedtest.kind == "ping" {
                    "ms"
                } else {
                    "mbps"
                });
            }
            "native://speed-result"
                if payload["generation"].as_u64() == Some(self.speed_generation) =>
            {
                self.speed_task = None;
                self.ui.speedtest.running = false;
                if let Some(values) = payload["result"]["Ok"].as_array().filter(|v| v.len() == 3) {
                    self.ui.speedtest.results = Some([
                        values[0].as_f64().unwrap_or(0.),
                        values[1].as_f64().unwrap_or(0.),
                        values[2].as_f64().unwrap_or(0.),
                    ]);
                } else {
                    self.ui.speedtest.kind = "failed".into();
                    self.ui.speedtest.stage = self.ui.t("speedtestFailed");
                    self.ui.speedtest.value = "—".into();
                    self.ui.speedtest.unit = String::new();
                    self.log(format!(
                        "Speed measurement failed: {}",
                        payload["result"]["Err"]
                    ));
                }
            }
            _ => {}
        }
        self.dirty = true;
    }
    fn connection_error(&mut self, error: String) {
        self.tray_dirty = true;
        self.ui.command = None;
        self.ui.error = if error.contains("ANOTHER_VPN_ACTIVE") {
            self.ui.t("otherVpnActive")
        } else {
            error
        };
        self.ui.command_error = self.ui.error.clone();
        self.next_status = 0.;
        self.dirty = true;
    }
    fn toggle(&mut self) {
        if self.ui.command.as_deref() == Some("stop") {
            return;
        }
        if self.ui.command.is_some()
            || matches!(
                self.ui.status.as_str(),
                "connected" | "connecting" | "error"
            )
        {
            self.stop(false);
        } else {
            self.start();
        }
    }
    fn start(&mut self) {
        self.tray_dirty = true;
        if !self.ui.loaded {
            self.ui.message(self.ui.t("settingsLoadError"));
            return;
        }
        if self.ui.profiles().is_empty() {
            self.ui.show(Overlay::Add);
            return;
        }
        let runtime = config::runtime(
            self.ui.profiles(),
            self.ui.active(),
            &self.ui.settings,
            false,
        );
        match runtime {
            Ok((config, indexes)) => {
                let generation = self.attempts.next();
                self.abort_ping();
                self.ui.command = Some("start".into());
                self.ui.error.clear();
                self.ui.command_error.clear();
                self.ui.alert_until = 0.;
                self.ui.animation_start = self.now();
                self.ui.connected_at = None;
                self.runtime_keys = indexes
                    .iter()
                    .map(|i| config::profile_key(&self.ui.profiles()[*i]))
                    .collect();
                self.request("start_vpn",json!({"config":config.to_string(),"killSwitch":self.ui.settings["killSwitch"]==true}),Purpose::Start(generation));
            }
            Err(error) => self.connection_error(error),
        }
        self.dirty = true;
    }
    fn stop(&mut self, restart: bool) {
        self.tray_dirty = true;
        let connecting = self.ui.connecting() || self.ui.command.as_deref() == Some("start");
        let generation = self.attempts.next();
        self.boot_retry = None;
        self.abort_ping();
        self.close_speed();
        self.ui.command = Some("stop".into());
        self.ui.error.clear();
        self.ui.command_error.clear();
        // Cancellation is dispatched immediately, independently of a pending start reply.
        if connecting {
            self.request(
                "cancel_vpn_start",
                json!({}),
                Purpose::Cancel(generation, restart),
            );
        } else {
            self.request("stop_vpn", json!({}), Purpose::Stop(generation, restart));
        }
        self.dirty = true;
    }
    fn save(&mut self, next: Value, after: AfterSave) {
        if !self.ui.loaded {
            self.ui.message(self.ui.t("settingsLoadError"));
            return;
        }
        if self.ui.busy.contains("save") {
            return;
        }
        self.ui.busy.insert("save".into());
        self.request(
            "save_settings",
            json!({"settings":next.to_string()}),
            Purpose::Save(next, after),
        );
        self.dirty = true;
    }
    fn after_save(&mut self, previous: Value, after: AfterSave) {
        self.tray_dirty = true;
        match after {
            AfterSave::Settings(changed) => {
                self.ui.hide(Overlay::Settings);
                self.ui.hide(Overlay::Unsaved);
                self.request(
                    "set_resume_on_boot",
                    json!({"enabled":self.ui.settings["resumeOnBoot"]==true}),
                    Purpose::Ignore,
                );
                if changed && (self.ui.connected() || self.ui.connecting()) {
                    self.confirm("restart", self.ui.t("restartConfirm"));
                }
            }
            AfterSave::Profiles(restart) => {
                if restart
                    && self.ui.command.as_deref() != Some("stop")
                    && (self.ui.connected() || self.ui.connecting())
                {
                    self.stop(!self.ui.profiles().is_empty());
                }
            }
            AfterSave::Import(index, running) => {
                self.ui.busy.remove("import");
                self.ui.hide(Overlay::Add);
                if running {
                    self.confirm(
                        &format!("connect-import:{index}"),
                        self.ui.t("connectImportedProfile"),
                    );
                } else {
                    self.start();
                }
            }
            AfterSave::Duplicate(restart) => {
                self.ui.busy.remove("import");
                self.ui.hide(Overlay::Add);
                if restart && self.ui.command.as_deref() != Some("stop") {
                    self.stop(true);
                }
                self.ui.message(self.ui.t("duplicateProfile"));
            }
            AfterSave::None => {}
        }
        if previous["profiles"] != self.ui.settings["profiles"] {
            self.ui.scroll.insert("profile-list".into(), 0.);
        }
        self.dirty = true;
    }
    fn select_profile(&mut self, index: usize) {
        if index >= self.ui.profiles().len() || self.ui.busy.contains("save") {
            return;
        }
        self.ui.hide(Overlay::Profiles);
        if index == self.ui.active() {
            return;
        }
        let mut next = self.ui.settings.clone();
        next["active"] = index.into();
        next["preferredProfileKey"] = config::profile_key(&self.ui.profiles()[index]).into();
        self.save(next, AfterSave::Profiles(true));
    }
    fn confirm(&mut self, action: &str, message: String) {
        self.ui.confirming = Some(action.into());
        self.ui.confirm = message;
        self.ui.show(Overlay::Confirm);
    }
    fn save_settings(&mut self) {
        let next = self.ui.draft();
        let changed = tunnel_changed(&self.ui.settings, &next);
        self.save(next, AfterSave::Settings(changed));
    }
    fn settings_close(&mut self) {
        if self.ui.tunneling {
            self.ui.tunneling = false;
        } else if self.ui.dirty_settings() {
            self.ui.show(Overlay::Unsaved);
        } else {
            self.ui.hide(Overlay::Settings);
        }
    }
    fn import_text(&mut self, value: &str) {
        let value = value.trim();
        if value.is_empty() {
            self.ui.busy.remove("import");
            self.ui.message(self.ui.t("clipboardEmpty"));
            return;
        }
        if let Some(url) = imports::subscription_url(value) {
            let existing = self.ui.settings["subscriptions"]
                .as_array()
                .unwrap()
                .iter()
                .find(|s| text(s, "url") == url.as_str())
                .cloned();
            let name = existing
                .as_ref()
                .map(|s| text(s, "name").to_string())
                .unwrap_or_else(|| {
                    imports::display_name(value).unwrap_or_else(|_| "SUBSCRIPTION".into())
                });
            let id = existing
                .as_ref()
                .map(|s| text(s, "id").to_string())
                .unwrap_or_else(new_id);
            self.ui.busy.insert(format!("subscription:{id}"));
            self.request(
                "fetch_subscription",
                json!({"url":url.as_str()}),
                Purpose::Subscription {
                    url: url.to_string(),
                    name,
                    id,
                    automatic: false,
                },
            );
            return;
        }
        let Some(profile) = config::parse_link(value) else {
            self.ui.busy.remove("import");
            self.ui.message(self.ui.t("invalidFormat"));
            return;
        };
        let key = config::profile_key(&profile);
        if let Some(index) = self
            .ui
            .profiles()
            .iter()
            .position(|p| config::profile_key(p) == key)
        {
            let restart =
                index != self.ui.active() && (self.ui.connected() || self.ui.connecting());
            let mut next = self.ui.settings.clone();
            next["active"] = index.into();
            next["preferredProfileKey"] = key.into();
            self.save(next, AfterSave::Duplicate(restart));
            return;
        }
        let index = self.ui.profiles().len();
        let running = self.ui.connected() || self.ui.connecting();
        let mut next = self.ui.settings.clone();
        next["profiles"].as_array_mut().unwrap().push(profile);
        if !running {
            next["active"] = index.into();
            next["preferredProfileKey"] = key.into();
        }
        self.save(next, AfterSave::Import(index, running));
    }
    fn refresh_subscription(&mut self, subscription: Value, automatic: bool) {
        let id = text(&subscription, "id").to_string();
        if self.ui.busy.contains(&format!("subscription:{id}")) {
            return;
        }
        self.ui.busy.insert(format!("subscription:{id}"));
        self.request(
            "fetch_subscription",
            json!({"url":subscription["url"]}),
            Purpose::Subscription {
                url: text(&subscription, "url").into(),
                name: text(&subscription, "name").into(),
                id,
                automatic,
            },
        );
    }
    fn subscription_result(
        &mut self,
        url: String,
        name: String,
        id: String,
        profiles: Vec<Value>,
        automatic: bool,
    ) {
        let legacy = if self.ui.settings["subscriptions"]
            .as_array()
            .unwrap()
            .iter()
            .any(|s| text(s, "id") == id)
        {
            String::new()
        } else {
            view::profile_groups(&self.ui)
                .into_iter()
                .find(|(group, _)| group.to_lowercase() == name.to_lowercase())
                .map(|(group, _)| group)
                .unwrap_or_default()
        };
        let next_profiles =
            imports::replace_profiles(self.ui.profiles(), &id, &profiles, &name, &legacy);
        let first = next_profiles
            .iter()
            .position(|p| text(p, "subscriptionId") == id)
            .unwrap_or(0);
        let active = self
            .ui
            .profile()
            .and_then(|p| imports::find_profile(&next_profiles, p, &id))
            .unwrap_or(first);
        let preferred = self
            .ui
            .profiles()
            .iter()
            .find(|p| config::profile_key(p) == text(&self.ui.settings, "preferredProfileKey"))
            .and_then(|p| imports::find_profile(&next_profiles, p, &id))
            .unwrap_or(first);
        let restart = self.ui.profile().map(config::profile_key)
            != next_profiles.get(active).map(config::profile_key);
        let mut next = self.ui.settings.clone();
        next["profiles"] = json!(next_profiles);
        next["active"] = active.into();
        next["preferredProfileKey"] = next_profiles
            .get(preferred)
            .map(config::profile_key)
            .unwrap_or_default()
            .into();
        let at = unix();
        let current = self
            .ui
            .profiles()
            .iter()
            .filter(|profile| text(profile, "subscriptionId") == id)
            .cloned()
            .collect::<Vec<_>>();
        let unchanged = imports::profiles_equal(
            &current,
            &next_profiles
                .iter()
                .filter(|profile| text(profile, "subscriptionId") == id)
                .cloned()
                .collect::<Vec<_>>(),
        );
        let previous = self.ui.settings["subscriptions"]
            .as_array()
            .unwrap()
            .iter()
            .find(|subscription| text(subscription, "id") == id);
        let subscription = json!({"id":id,"url":url,"name":name,"updatedAt":if unchanged{previous.and_then(|subscription|subscription["updatedAt"].as_u64()).unwrap_or(at)}else{at},"lastCheckedAt":at,"lastStatus":if unchanged{"unchanged"}else{"updated"}});
        let subscriptions = next["subscriptions"].as_array_mut().unwrap();
        if let Some(index) = subscriptions.iter().position(|s| text(s, "id") == id) {
            subscriptions[index] = subscription;
        } else {
            subscriptions.push(subscription);
        }
        self.ui.busy.remove("import");
        if !automatic {
            self.ui.hide(Overlay::Add);
        }
        self.save(next, AfterSave::Profiles(restart));
    }
    fn probe(&mut self, indexes: Vec<usize>) {
        if self.ui.busy.contains("probes") || self.preview {
            return;
        }
        let at = unix();
        let indexes = indexes
            .into_iter()
            .filter(|i| {
                self.ui.profiles().get(*i).is_some_and(|p| {
                    self.ui
                        .probes
                        .get(&config::profile_key(p))
                        .is_none_or(|probe| at.saturating_sub(probe.checked_at) >= 30000)
                })
            })
            .collect::<Vec<_>>();
        if indexes.is_empty() {
            return;
        }
        let profiles = indexes
            .iter()
            .map(|i| self.ui.profiles()[*i].clone())
            .collect::<Vec<_>>();
        let keys = profiles.iter().map(config::profile_key).collect::<Vec<_>>();
        if let Ok(config) = config::selectable(
            &profiles,
            indexes
                .iter()
                .position(|i| *i == self.ui.active())
                .unwrap_or(0),
            &self.ui.settings,
        ) {
            for key in &keys {
                self.ui.probes.insert(
                    key.clone(),
                    Probe {
                        checking: true,
                        ..Default::default()
                    },
                );
            }
            self.ui.busy.insert("probes".into());
            self.request(
                "probe_profiles",
                json!({"config":config.to_string()}),
                Purpose::Probes(keys),
            );
        }
    }
    fn delete_profiles(&mut self, indexes: &[usize], group: Option<&str>) {
        let old = self.ui.profile().cloned();
        let remaining = self
            .ui
            .profiles()
            .iter()
            .enumerate()
            .filter(|(i, _)| !indexes.contains(i))
            .map(|(_, p)| p.clone())
            .collect::<Vec<_>>();
        let active = old
            .as_ref()
            .and_then(|p| {
                remaining
                    .iter()
                    .position(|v| config::profile_key(v) == config::profile_key(p))
            })
            .unwrap_or(0);
        let mut next = self.ui.settings.clone();
        let restart = indexes.contains(&self.ui.active());
        next["profiles"] = json!(remaining);
        next["active"] = active.into();
        next["preferredProfileKey"] = remaining
            .get(active)
            .map(config::profile_key)
            .unwrap_or_default()
            .into();
        if let Some(group) = group {
            next["subscriptions"] = json!(next["subscriptions"]
                .as_array()
                .unwrap()
                .iter()
                .filter(|s| text(s, "name") != group)
                .cloned()
                .collect::<Vec<_>>());
            self.ui.group = None;
        }
        self.save(next, AfterSave::Profiles(restart));
    }
    fn load_processes(&mut self) {
        self.process_generation += 1;
        self.ui.processes_message = self.ui.t("loadingProcesses");
        self.request(
            "get_running_processes",
            json!({"excludeSystem":self.ui.field("running-apps-exclude-system").checked}),
            Purpose::Processes(self.process_generation),
        );
    }
    fn check_update(&mut self, silent: bool) {
        if self.ui.busy.contains("update") || self.ui.update_installing {
            return;
        }
        self.ui.busy.insert("update".into());
        if !silent {
            self.ui.update_status = self.ui.t("updateChecking");
        }
        self.request("check_for_update", json!({}), Purpose::Update(silent));
    }
    fn update_action(&mut self) {
        if self.ui.update_installing || self.ui.busy.contains("update") {
            return;
        }
        if let Some(update) = &self.ui.update {
            if self.ui.open(Overlay::Settings) && self.ui.dirty_settings() {
                self.ui.update_status = self.ui.t("updateSaveSettings");
                return;
            }
            self.confirm(
                "install-update",
                format!(
                    "{} {}? {}",
                    self.ui.t(if update["rollback"] == true {
                        "updateRollbackConfirm"
                    } else {
                        "updateInstallConfirm"
                    }),
                    text(update, "version"),
                    self.ui.t("updateRestartNotice")
                ),
            );
        } else {
            self.check_update(false);
        }
    }
    fn share(&mut self, value: String) {
        match super::qr::svg(&value) {
            Ok(qr) => {
                self.ui.qr = qr;
                self.ui.share = value.clone();
                self.ui.fields.get_mut("share-link").unwrap().value = value;
                self.ui.show(Overlay::Share);
            }
            Err(error) => self.ui.message(error),
        }
    }
    fn abort_ping(&mut self) {
        self.ping_generation = self.ping_generation.wrapping_add(1);
        if let Some(task) = self.ping_task.take() {
            task.abort();
        }
    }
    fn close_speed(&mut self) {
        self.speed_generation = self.speed_generation.wrapping_add(1);
        if let Some(task) = self.speed_task.take() {
            task.abort();
        }
        self.ui.speedtest.running = false;
        self.ui.hide(Overlay::Speed);
    }
    fn run_speed(&mut self) {
        if self.ui.speedtest.running {
            return;
        }
        if self.preview {
            return;
        }
        self.abort_ping();
        self.speed_generation = self.speed_generation.wrapping_add(1);
        self.ui.speedtest = Speed {
            running: true,
            kind: "ping".into(),
            started: self.now(),
            stage: "...".into(),
            value: "0".into(),
            unit: self.ui.t("ms"),
            ..Default::default()
        };
        let sender = self.sender.clone();
        let generation = self.speed_generation;
        self.speed_task = Some(tauri::async_runtime::spawn(measurements::speedtest(
            sender, generation,
        )));
    }
    pub(super) fn pointer(&mut self, kind: &str, hit: Option<Hit>) {
        let id = hit.as_ref().map(|h| h.id.as_str()).unwrap_or("");
        if kind == "move" || kind == "leave" {
            let hover = if kind == "leave" { "" } else { id };
            if self.ui.hover != hover {
                self.ui.hover = hover.into();
                self.ui.tooltip = hit
                    .as_ref()
                    .filter(|h| h.kind == "help")
                    .map(|h| self.ui.t(&h.id))
                    .unwrap_or_default();
                self.dirty = true;
            }
            return;
        }
        let Some(hit) = hit else {
            self.ui.select = None;
            self.ui.focus.clear();
            self.dirty = true;
            return;
        };
        self.ui.focus = hit.id.clone();
        match hit.kind.as_str() {
            "input" => {
                self.ui.focus = hit.id.clone();
                self.ui.caret = self.ui.field(&hit.id).value.chars().count();
                self.ui.anchor = self.ui.caret;
                self.ui.select = None;
            }
            "toggle" => {
                if let Some(field) = self.ui.fields.get_mut(&hit.id) {
                    field.checked = !field.checked;
                }
                if hit.id == "running-apps-exclude-system" {
                    self.load_processes();
                }
            }
            "select" => {
                if self.ui.select.as_ref().is_some_and(|(id, _)| id == &hit.id) {
                    self.ui.select = None;
                } else {
                    self.ui.select = Some((hit.id.clone(), [hit.x, hit.y + hit.h, hit.w, 96.]));
                }
            }
            "help" => self.ui.tooltip = self.ui.t(&hit.id),
            "block" => {
                self.ui.select = None;
            }
            _ => self.action(&hit.id),
        }
        self.dirty = true;
    }
    pub(super) fn scroll(&mut self, hit: &Hit, delta: f32) {
        let value = self.ui.scroll.entry(hit.id.clone()).or_default();
        *value = (*value - delta / 120. * 44.).clamp(0., hit.max);
        self.ui.tooltip.clear();
        self.ui.select = None;
        self.dirty = true;
    }
    fn action(&mut self, id: &str) {
        if let Some(index) = id
            .strip_prefix("select-profile:")
            .and_then(|v| v.parse::<usize>().ok())
        {
            self.select_profile(index);
            return;
        }
        if let Some(index) = id
            .strip_prefix("share-profile:")
            .and_then(|v| v.parse::<usize>().ok())
        {
            if let Some(profile) = self.ui.profiles().get(index) {
                match config::share_link(profile) {
                    Ok(value) => self.share(value),
                    Err(error) => self.ui.message(error),
                }
            }
            return;
        }
        if let Some(index) = id
            .strip_prefix("delete-profile:")
            .and_then(|v| v.parse::<usize>().ok())
        {
            if let Some(profile) = self.ui.profiles().get(index) {
                self.confirm(
                    id,
                    format!(
                        "{} \"{}\"?",
                        self.ui.t("deleteConfirm"),
                        text(profile, "name")
                    ),
                );
            }
            return;
        }
        if let Some(name) = id.strip_prefix("open-group:") {
            self.ui.group = Some(name.into());
            self.ui.scroll.insert("profile-list".into(), 0.);
            if let Some((_, indexes)) = view::profile_groups(&self.ui)
                .into_iter()
                .find(|(group, _)| group == name)
            {
                self.probe(indexes);
            }
            return;
        }
        if let Some(name) = id.strip_prefix("delete-group:") {
            self.confirm(
                id,
                format!("{} \"{name}\"?", self.ui.t("deleteGroupConfirm")),
            );
            return;
        }
        if let Some(name) = id.strip_prefix("share-group:") {
            if let Some(s) = self.ui.settings["subscriptions"]
                .as_array()
                .unwrap()
                .iter()
                .find(|s| text(s, "name") == name)
            {
                self.share(format!(
                    "{}#{}",
                    text(s, "url"),
                    config::encode_component(name)
                ));
            }
            return;
        }
        if let Some(lang) = id.strip_prefix("language:") {
            if matches!(lang, "ru" | "en") {
                self.ui.fields.get_mut("s-lang").unwrap().value = lang.into();
            }
            self.ui.hide(Overlay::Language);
            return;
        }
        if let Some(value) = id.strip_prefix("option:") {
            if let Some((field, value)) = value.rsplit_once(':') {
                if matches!(value, "off" | "only" | "bypass") {
                    self.ui.fields.get_mut(field).unwrap().value = value.into();
                }
            }
            self.ui.select = None;
            return;
        }
        if let Some(process) = id.strip_prefix("process:") {
            if let Some(selected) = self
                .ui
                .selected_processes
                .iter()
                .find(|selected| selected.eq_ignore_ascii_case(process))
                .cloned()
            {
                self.ui.selected_processes.remove(&selected);
            } else {
                self.ui.selected_processes.insert(process.into());
            }
            return;
        }
        match id {
            "power-btn" => self.toggle(),
            "win-close" => self.sender.send(Message::Window("hide".into())),
            "win-min" => self.sender.send(Message::Window("minimize".into())),
            "btn-add" | "profiles-add-btn" => self.ui.show(Overlay::Add),
            "cancel-add" | "overlay-add" => self.ui.hide(Overlay::Add),
            "btn-profiles" => {
                self.ui.show(Overlay::Profiles);
                self.probe((0..self.ui.profiles().len()).collect());
            }
            "close-profiles" | "overlay-profiles" => self.ui.hide(Overlay::Profiles),
            "profiles-back-btn" => {
                self.ui.group = None;
                self.ui.scroll.insert("profile-list".into(), 0.);
            }
            "btn-settings" => {
                self.ui.show(Overlay::Settings);
                self.request("get_kill_switch_status", json!({}), Purpose::KillSwitch);
            }
            "close-settings" | "overlay-settings" => self.settings_close(),
            "btn-open-tunneling" => {
                self.ui.tunneling = true;
                self.ui.scroll.insert("settings-tunneling-page".into(), 0.);
            }
            "close-tunneling" => self.ui.tunneling = false,
            "btn-save-settings" | "settings-unsaved-save" => self.save_settings(),
            "settings-unsaved-discard" => {
                self.ui.load_fields();
                self.ui.hide(Overlay::Unsaved);
                self.ui.hide(Overlay::Settings);
            }
            "settings-unsaved-cancel" | "overlay-settings-unsaved" => {
                self.ui.hide(Overlay::Unsaved)
            }
            "btn-language" => self.ui.show(Overlay::Language),
            "close-language" | "overlay-language" => self.ui.hide(Overlay::Language),
            "btn-clipboard-import" if !self.ui.busy.contains("import") => {
                self.ui.busy.insert("import".into());
                self.request("native_clipboard_read", json!({}), Purpose::Clipboard);
            }
            "btn-copy-share" => self.request(
                "native_clipboard_write",
                json!({"text":self.ui.share}),
                Purpose::Copied,
            ),
            "close-share" | "overlay-share" => self.ui.hide(Overlay::Share),
            "btn-confirm-cancel" => {
                self.ui.confirming = None;
                self.ui.hide(Overlay::Confirm);
            }
            "btn-confirm-ok" => self.confirm_action(),
            "btn-message-ok" | "overlay-message" => self.ui.hide(Overlay::Message),
            "btn-app-browse" => self.request("select_executable", json!({}), Purpose::Browse),
            "btn-app-running" => {
                self.ui.selected_processes = self
                    .ui
                    .field("s-apps-list")
                    .value
                    .split(',')
                    .map(str::trim)
                    .filter(|v| !v.is_empty())
                    .map(str::to_string)
                    .collect();
                self.ui
                    .fields
                    .get_mut("running-apps-search")
                    .unwrap()
                    .value
                    .clear();
                self.ui.show(Overlay::Running);
                self.load_processes();
            }
            "close-running-apps" | "cancel-running-apps" | "overlay-running-apps" => {
                self.ui.hide(Overlay::Running)
            }
            "confirm-running-apps" => {
                let mut list = self
                    .ui
                    .selected_processes
                    .iter()
                    .cloned()
                    .collect::<Vec<_>>();
                list.sort_by_key(|v| v.to_lowercase());
                self.ui.fields.get_mut("s-apps-list").unwrap().value = list.join(", ");
                self.ui.hide(Overlay::Running);
            }
            "btn-speed" => {
                self.ui.speedtest = Speed {
                    value: "0".into(),
                    unit: self.ui.t("mbps"),
                    ..Default::default()
                };
                self.ui.show(Overlay::Speed);
                self.run_speed();
            }
            "speedtest-btn-action" => self.run_speed(),
            "speedtest-btn-close" | "overlay-speedtest" => self.close_speed(),
            "btn-check-update" | "update-banner-install" => self.update_action(),
            "update-banner-later" => {
                if let Some(update) = &self
                    .ui
                    .update
                    .as_ref()
                    .filter(|_| !self.ui.update_installing)
                {
                    self.ui.dismissed_update = text(update, "version").into();
                }
            }
            "btn-export-diagnostics" => {
                let summary = json!({"schemaVersion":1,"language":self.ui.settings["lang"],"profileCount":self.ui.profiles().len(),"subscriptionCount":self.ui.settings["subscriptions"].as_array().unwrap().len(),"adblock":self.ui.settings["adblock"],"quic":self.ui.settings["quic"],"lan":self.ui.settings["lan"],"killSwitch":self.ui.settings["killSwitch"],"resumeOnBoot":self.ui.settings["resumeOnBoot"],"mtu":self.ui.settings["mtu"],"appsMode":self.ui.settings["appsMode"],"appRuleCount":self.ui.settings["appsList"].as_array().unwrap().len(),"sitesMode":self.ui.settings["sitesMode"],"siteRuleCount":self.ui.settings["sitesList"].as_array().unwrap().len()});
                self.request(
                    "export_diagnostics",
                    json!({"settingsSummary":summary}),
                    Purpose::Diagnostics,
                );
            }
            _ => {}
        }
    }
    fn confirm_action(&mut self) {
        let Some(action) = self.ui.confirming.take() else {
            return;
        };
        self.ui.hide(Overlay::Confirm);
        if let Some(index) = action
            .strip_prefix("delete-profile:")
            .and_then(|v| v.parse::<usize>().ok())
        {
            self.delete_profiles(&[index], None);
        } else if let Some(group) = action.strip_prefix("delete-group:") {
            if let Some((_, indexes)) = view::profile_groups(&self.ui)
                .into_iter()
                .find(|(name, _)| name == group)
            {
                self.delete_profiles(&indexes, Some(group));
            }
        } else if let Some(index) = action
            .strip_prefix("connect-import:")
            .and_then(|v| v.parse::<usize>().ok())
        {
            if index == self.ui.active() {
                if self.ui.connected() || self.ui.connecting() {
                    self.stop(true);
                } else {
                    self.start();
                }
            } else {
                self.select_profile(index);
            }
        } else if action == "restart" {
            self.stop(true);
        } else if action == "install-update" {
            if let Some(update) = &self.ui.update {
                self.ui.update_installing = true;
                self.ui.update_percent = None;
                self.ui.update_status = self.ui.t("updateInstalling");
                self.request(
                    "install_update",
                    json!({"expectedVersion":update["version"]}),
                    Purpose::Install,
                );
            }
        }
    }
    pub(super) fn key(&mut self, key: &str, ctrl: bool, shift: bool, hits: &[Hit]) {
        if key == "Escape" {
            if self.ui.select.take().is_some() {
                self.dirty = true;
                return;
            }
            if self.ui.open(Overlay::Message) {
                self.ui.hide(Overlay::Message);
            } else if self.ui.open(Overlay::Unsaved) {
                self.ui.hide(Overlay::Unsaved);
            } else if self.ui.open(Overlay::Settings) {
                self.settings_close();
            }
            self.dirty = true;
            return;
        }
        if key == "Tab" {
            let mut controls = Vec::new();
            for hit in hits {
                if !hit.id.is_empty()
                    && !matches!(hit.kind.as_str(), "outside" | "block" | "help")
                    && !controls.contains(&hit.id)
                {
                    controls.push(hit.id.clone());
                }
            }
            if !controls.is_empty() {
                let current = controls.iter().position(|id| id == &self.ui.focus);
                let next = if shift {
                    current.unwrap_or(0).wrapping_add(controls.len() - 1) % controls.len()
                } else {
                    current.map(|i| (i + 1) % controls.len()).unwrap_or(0)
                };
                self.ui.focus = controls[next].clone();
                if let Some(field) = self.ui.fields.get(&self.ui.focus) {
                    self.ui.caret = field.value.chars().count();
                    self.ui.anchor = self.ui.caret;
                }
            }
            self.dirty = true;
            return;
        }
        let focus = self.ui.focus.clone();
        if let Some(hit) = hits.iter().find(|hit| hit.id == focus).cloned() {
            if hit.kind == "toggle" {
                if matches!(key, "Enter" | " ") {
                    self.pointer("click", Some(hit));
                }
                return;
            }
            if hit.kind == "select" {
                if matches!(key, "ArrowUp" | "ArrowDown") {
                    let field = self.ui.fields.get_mut(&focus).unwrap();
                    let values = ["off", "only", "bypass"];
                    let index = values
                        .iter()
                        .position(|value| *value == field.value)
                        .unwrap_or(0);
                    let next = if key == "ArrowUp" {
                        index.saturating_sub(1)
                    } else {
                        (index + 1).min(2)
                    };
                    field.value = values[next].into();
                    self.dirty = true;
                } else if matches!(key, "Enter" | " ") {
                    self.pointer("click", Some(hit));
                }
                return;
            }
        }
        if !self.ui.fields.contains_key(&focus) {
            if (key == "Enter" || key == " ") && !focus.is_empty() {
                if let Some(hit) = hits.iter().find(|h| h.id == focus) {
                    self.pointer("down", Some(hit.clone()));
                }
            } else if ctrl && key.eq_ignore_ascii_case("v") && self.ui.open(Overlay::Add) {
                self.action("btn-clipboard-import");
            }
            return;
        }
        if ctrl && key.eq_ignore_ascii_case("a") {
            self.ui.anchor = 0;
            self.ui.caret = self.ui.field(&focus).value.chars().count();
            self.dirty = true;
            return;
        }
        if ctrl && (key.eq_ignore_ascii_case("c") || key.eq_ignore_ascii_case("x")) {
            let chars = self.ui.field(&focus).value.chars().collect::<Vec<_>>();
            let lo = self.ui.anchor.min(self.ui.caret).min(chars.len());
            let hi = self.ui.anchor.max(self.ui.caret).min(chars.len());
            if lo < hi {
                let selected = chars[lo..hi].iter().collect::<String>();
                match window::write_clipboard(
                    &selected,
                    windows::Win32::Foundation::HWND(
                        self.sender.hwnd.load(std::sync::atomic::Ordering::Acquire) as _,
                    ),
                ) {
                    Ok(()) => {
                        if key.eq_ignore_ascii_case("x") {
                            self.edit(&focus, "Delete", false, None);
                        }
                    }
                    Err(error) => self.show_error("clipboardError", &error),
                }
            }
            return;
        }
        if ctrl && key.eq_ignore_ascii_case("v") {
            match window::read_clipboard() {
                Ok(value) => self.edit(&focus, "", false, Some(&value)),
                Err(error) => self.show_error("clipboardError", &error),
            }
            return;
        }
        if !ctrl {
            self.edit(&focus, key, shift, None);
        }
    }
    fn edit(&mut self, id: &str, key: &str, shift: bool, paste: Option<&str>) {
        let field = self.ui.fields.get_mut(id).unwrap();
        edit_field(
            field,
            &mut self.ui.caret,
            &mut self.ui.anchor,
            key,
            shift,
            paste,
        );
        self.dirty = true;
    }
}

impl Drop for Controller {
    fn drop(&mut self) {
        self.abort_ping();
        if let Some(task) = self.speed_task.take() {
            task.abort();
        }
    }
}
fn new_id() -> String {
    unsafe { windows::Win32::System::Com::CoCreateGuid() }
        .map(|id| format!("{id:?}"))
        .unwrap_or_else(|_| format!("subscription-{}", unix()))
}
fn tunnel_changed(a: &Value, b: &Value) -> bool {
    [
        "adblock",
        "quic",
        "lan",
        "killSwitch",
        "mtu",
        "appsMode",
        "appsList",
        "sitesMode",
        "sitesList",
    ]
    .iter()
    .any(|key| a[*key] != b[*key])
}

fn edit_field(
    field: &mut super::model::Field,
    caret: &mut usize,
    anchor: &mut usize,
    key: &str,
    shift: bool,
    paste: Option<&str>,
) {
    let mut chars = field.value.chars().collect::<Vec<_>>();
    *caret = (*caret).min(chars.len());
    *anchor = (*anchor).min(chars.len());
    let lo = (*caret).min(*anchor);
    let hi = (*caret).max(*anchor);
    let movement = match key {
        "ArrowLeft" => Some(if !shift && lo < hi {
            lo
        } else {
            caret.saturating_sub(1)
        }),
        "ArrowRight" => Some(if !shift && lo < hi {
            hi
        } else {
            (*caret + 1).min(chars.len())
        }),
        "Home" | "ArrowUp" => Some(0),
        "End" | "ArrowDown" => Some(chars.len()),
        _ => None,
    };
    if let Some(next) = movement {
        *caret = next;
        if !shift {
            *anchor = next;
        }
        return;
    }
    if field.read_only {
        return;
    }
    let insertion = paste.map(str::to_string).or_else(|| {
        if key == "Enter" && field.multiline {
            Some("\n".into())
        } else if key.chars().count() == 1 {
            Some(key.into())
        } else {
            None
        }
    });
    if let Some(value) = insertion {
        let value = value
            .chars()
            .filter(|c| {
                if field.numeric {
                    c.is_ascii_digit()
                } else {
                    !c.is_control() || field.multiline && matches!(c, '\n' | '\t')
                }
            })
            .take(super::super::MAX_SETTINGS_BYTES.saturating_sub(field.value.len()) / 4)
            .collect::<Vec<_>>();
        let count = value.len();
        chars.splice(lo..hi, value);
        *caret = lo + count;
        *anchor = *caret;
    } else if key == "Backspace" || key == "Delete" {
        if lo < hi {
            chars.drain(lo..hi);
            *caret = lo;
        } else if key == "Backspace" && *caret > 0 {
            chars.remove(*caret - 1);
            *caret -= 1;
        } else if key == "Delete" && *caret < chars.len() {
            chars.remove(*caret);
        }
        *anchor = *caret;
    }
    field.value = chars.into_iter().collect();
}

#[cfg(test)]
mod tests {
    use super::super::model::Field;
    use super::*;
    fn controller() -> Controller {
        let (queue, receiver) = mpsc::channel();
        let sender = Sender {
            queue,
            hwnd: std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0)),
        };
        let mut controller =
            Controller::with_backend(Backend::Test(Vec::new()), sender, receiver, false);
        complete(&mut controller,"load_settings",Ok(json!(json!({"lang":"ru","profiles":[{"protocol":"vless","name":"TEST","host":"192.0.2.1","port":443,"uuid":"00000000-0000-4000-8000-000000000001"}],"futureSetting":17}).to_string())));
        complete(
            &mut controller,
            "get_vpn_runtime_snapshot",
            Ok(json!({"status":"Stopped","desiredRunning":false})),
        );
        controller
    }
    fn requests(controller: &Controller) -> &Vec<(u32, String, Value)> {
        match &controller.backend {
            Backend::Test(requests) => requests,
            _ => panic!("Expected test backend"),
        }
    }
    fn complete(controller: &mut Controller, command: &str, result: Result<Value, String>) {
        let id = requests(controller)
            .iter()
            .find(|(id, name, _)| name == command && controller.pending.contains_key(id))
            .unwrap()
            .0;
        let purpose = controller.pending.remove(&id).unwrap();
        controller.reply(purpose, result);
    }
    #[test]
    fn cancellation_does_not_wait_for_the_start_request_and_ignores_its_reply() {
        let mut c = controller();
        c.start();
        assert!(c.ui.connecting());
        c.toggle();
        assert!(requests(&c)
            .iter()
            .any(|(_, name, _)| name == "cancel_vpn_start"));
        assert_eq!(c.ui.command.as_deref(), Some("stop"));
        let before = requests(&c).len();
        complete(&mut c, "start_vpn", Ok(Value::Null));
        assert_eq!(requests(&c).len(), before);
        assert_eq!(c.ui.command.as_deref(), Some("stop"));
        complete(&mut c, "cancel_vpn_start", Ok(Value::Null));
        complete(&mut c, "stop_vpn", Ok(Value::Null));
        complete(
            &mut c,
            "get_vpn_runtime_snapshot",
            Ok(json!({"status":"Stopped","desiredRunning":false})),
        );
        assert_eq!(c.ui.status, "stopped");
        assert!(c.ui.command.is_none());
    }
    #[test]
    fn failed_saves_keep_the_previous_settings_and_keep_the_draft_reviewable() {
        let mut c = controller();
        let previous = c.ui.settings.clone();
        c.ui.show(Overlay::Settings);
        c.ui.fields.get_mut("s-mtu").unwrap().value = "1280".into();
        c.save_settings();
        assert_eq!(c.ui.settings, previous);
        complete(&mut c, "save_settings", Err("disk failure".into()));
        assert_eq!(c.ui.settings, previous);
        assert_eq!(c.ui.field("s-mtu").value, "1280");
        assert!(c.ui.open(Overlay::Settings));
        assert!(c.ui.open(Overlay::Message));
        assert!(!c.ui.busy.contains("save"));
    }
    #[test]
    fn saving_a_tunnel_change_does_not_restart_without_the_users_confirmation() {
        let mut c = controller();
        c.apply_snapshot(&json!({"status":"Connected","desiredRunning":true}), false);
        c.ui.show(Overlay::Settings);
        c.ui.fields.get_mut("s-mtu").unwrap().value = "1280".into();
        c.save_settings();
        complete(&mut c, "save_settings", Ok(Value::Null));
        assert_eq!(c.ui.settings["mtu"], 1280);
        assert_eq!(c.ui.settings["futureSetting"], 17);
        assert!(c.ui.open(Overlay::Confirm));
        assert!(!requests(&c).iter().any(|(_, name, _)| name == "stop_vpn"));
        c.action("btn-confirm-cancel");
        assert!(c.ui.connected());
    }
    #[test]
    fn completing_a_profile_save_cannot_override_a_more_recent_disconnect() {
        let mut c = controller();
        let second = json!({"protocol":"vless","host":"192.0.2.2","port":443,"uuid":"00000000-0000-4000-8000-000000000002"});
        c.ui.settings["profiles"]
            .as_array_mut()
            .unwrap()
            .push(second);
        c.apply_snapshot(&json!({"status":"Connected","desiredRunning":true}), false);
        c.select_profile(1);
        c.stop(false);
        complete(&mut c, "save_settings", Ok(Value::Null));
        assert_eq!(c.ui.active(), 1);
        assert_eq!(c.ui.command.as_deref(), Some("stop"));
        assert_eq!(
            requests(&c)
                .iter()
                .filter(|(_, name, _)| name == "stop_vpn")
                .count(),
            1
        );
        assert!(!requests(&c).iter().any(|(_, name, _)| name == "start_vpn"));
    }
    #[test]
    fn stale_status_and_process_replies_cannot_overwrite_newer_operations() {
        let mut c = controller();
        let generation = c.attempts.0;
        c.start();
        c.reply(
            Purpose::Snapshot(generation),
            Ok(json!({"status":"Stopped","desiredRunning":false})),
        );
        assert!(c.ui.connecting());
        c.process_generation = 2;
        c.reply(Purpose::Processes(1), Ok(json!(["old.exe"])));
        assert!(c.ui.processes.is_empty());
        c.reply(Purpose::Processes(2), Ok(json!(["current.exe"])));
        assert_eq!(c.ui.processes, ["current.exe"]);
    }
    #[test]
    fn keyboard_controls_and_unsaved_settings_remain_usable() {
        let mut c = controller();
        c.ui.show(Overlay::Settings);
        let scene = c.scene().unwrap().unwrap();
        c.ui.focus = "s-quic".into();
        c.key(" ", false, false, &scene.hits);
        assert!(c.ui.field("s-quic").checked);
        c.settings_close();
        assert!(c.ui.open(Overlay::Unsaved));
        c.action("settings-unsaved-discard");
        assert!(!c.ui.open(Overlay::Settings));
        assert!(!c.ui.field("s-quic").checked);
        c.ui.show(Overlay::Settings);
        c.ui.tunneling = true;
        c.ui.focus = "s-apps-mode".into();
        let scene = c.scene().unwrap().unwrap();
        c.key("ArrowDown", false, false, &scene.hits);
        assert_eq!(c.ui.field("s-apps-mode").value, "only");
        c.key("Enter", false, false, &scene.hits);
        assert!(c.ui.select.is_some());
        c.key("Escape", false, false, &scene.hits);
        assert!(c.ui.select.is_none());
        assert!(c.ui.tunneling);
    }
    #[test]
    fn hiding_the_window_cancels_measurements_and_stale_results_are_ignored() {
        let mut c = controller();
        c.ui.show(Overlay::Speed);
        c.ui.speedtest.running = true;
        c.speed_generation = 7;
        c.set_visible(false);
        assert!(!c.ui.speedtest.running);
        assert!(!c.ui.open(Overlay::Speed));
        c.event(
            "native://speed-result",
            json!({"generation":7,"result":{"Ok":[123,456,31]}}),
        );
        assert!(c.ui.speedtest.results.is_none());
    }
    #[test]
    fn repeated_visibility_changes_resume_the_clock_without_restarting_the_vpn() {
        let mut c = controller();
        c.preview = true;
        c.apply_snapshot(&json!({"status":"Connected","desiredRunning":true}), false);
        let started = c.ui.connected_unix;
        for cycle in 1..=5 {
            c.set_visible(false);
            c.clock = Instant::now() - std::time::Duration::from_secs(cycle * 5);
            c.tick();
            c.dirty = false;
            c.set_visible(true);
            assert!(c.scene().unwrap().unwrap().animated());
            c.tick();
            assert!(c.ui.connected());
            assert_eq!(c.ui.connected_unix, started);
            assert!(c.next_uptime > c.now());
        }
        assert!(!requests(&c).iter().any(|(_, name, _)| {
            matches!(name.as_str(), "start_vpn" | "stop_vpn" | "cancel_vpn_start")
        }));
    }
    #[test]
    fn service_timeouts_preserve_a_connected_tunnel_and_show_a_recoverable_error() {
        let mut c = controller();
        c.apply_snapshot(&json!({"status":"Connected","desiredRunning":true}), false);
        for _ in 0..3 {
            c.reply(Purpose::Snapshot(c.attempts.0), Err("IPC timeout".into()));
        }
        assert!(c.ui.connected());
        assert_eq!(c.ui.error, c.ui.t("serviceUnavailable"));
        c.reply(
            Purpose::Snapshot(c.attempts.0),
            Ok(json!({"status":"Connected","desiredRunning":true})),
        );
        assert!(c.ui.error.is_empty());
        assert!(c.ui.connected());
    }
    #[test]
    fn a_failed_connection_keeps_its_error_visible_until_a_retry() {
        let mut c = controller();
        c.start();
        complete(&mut c, "start_vpn", Err("connection refused".into()));
        c.reply(
            Purpose::Snapshot(c.attempts.0),
            Ok(json!({"status":"Stopped","desiredRunning":false})),
        );
        assert_eq!(c.ui.error, "connection refused");
        assert!(!c.ui.connecting());
        c.start();
        assert!(c.ui.error.is_empty());
        assert!(c.ui.command_error.is_empty());
    }
    #[test]
    fn a_ping_completed_before_hiding_cannot_overwrite_the_resumed_measurement() {
        let mut c = controller();
        c.apply_snapshot(&json!({"status":"Connected","desiredRunning":true}), false);
        c.ping_generation = 7;
        c.set_visible(false);
        c.set_visible(true);
        c.event("native://ping", json!({"generation":7,"result":{"Ok":999}}));
        assert!(c.ui.ping.is_none());
    }
    #[test]
    fn cancellation_invalidates_start_and_status_replies_immediately() {
        let mut attempts = Attempts::default();
        let start = attempts.next();
        let cancel = attempts.next();
        assert!(!attempts.current(start));
        assert!(attempts.current(cancel));
        let restart = attempts.next();
        assert!(!attempts.current(cancel));
        assert!(attempts.current(restart));
    }
    #[test]
    fn text_editing_preserves_unicode_and_selection() {
        let mut field = Field {
            value: "А🇳🇱🙂Z".into(),
            ..Default::default()
        };
        let (mut caret, mut anchor) = (4, 4);
        edit_field(
            &mut field,
            &mut caret,
            &mut anchor,
            "Backspace",
            false,
            None,
        );
        assert_eq!(field.value, "А🇳🇱Z");
        anchor = 0;
        edit_field(&mut field, &mut caret, &mut anchor, "", false, Some("你好"));
        assert_eq!(field.value, "你好Z");
        assert_eq!(caret, 2);
    }
    #[test]
    fn readonly_and_numeric_inputs_are_enforced() {
        let mut field = Field {
            value: "443".into(),
            numeric: true,
            ..Default::default()
        };
        let (mut caret, mut anchor) = (3, 0);
        edit_field(
            &mut field,
            &mut caret,
            &mut anchor,
            "",
            false,
            Some(" 1x500🙂"),
        );
        assert_eq!(field.value, "1500");
        field.read_only = true;
        edit_field(
            &mut field,
            &mut caret,
            &mut anchor,
            "Backspace",
            false,
            None,
        );
        assert_eq!(field.value, "1500");
    }
    #[test]
    fn language_and_resume_changes_do_not_restart_the_tunnel() {
        let a = json!({"lang":"ru","resumeOnBoot":false,"mtu":0});
        let b = json!({"lang":"en","resumeOnBoot":true,"mtu":0});
        assert!(!tunnel_changed(&a, &b));
        let mut c = b;
        c["mtu"] = 1280.into();
        assert!(tunnel_changed(&a, &c));
    }
}
