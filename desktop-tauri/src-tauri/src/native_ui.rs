mod painter;
mod window;

use super::*;
use base64::{engine::general_purpose::STANDARD, Engine};
use rquickjs::{Context, Function, Runtime};
use serde_json::{json, Value};
use std::{collections::HashMap, sync::{mpsc, Arc}, thread, time::Instant};
use tauri::Listener;
use windows::Win32::{Foundation::{HWND, LPARAM, WPARAM}, UI::WindowsAndMessaging::PostMessageW};

const WAKE: u32 = 0x8001;
const SOURCE: &str = include_str!("../native-generated/ui.js");
static PREVIEW_STARTED_AT: std::sync::LazyLock<u64> = std::sync::LazyLock::new(||
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64 - 6 * 60 * 60 * 1000);

pub(super) enum Message {
    Reply(u32, Result<Value, String>),
    Event(String, Value),
    Fetch(u32, Value),
    Window(String),
}

#[derive(Clone)]
pub(super) struct Sender {
    queue: mpsc::Sender<Message>,
    hwnd: Arc<std::sync::atomic::AtomicUsize>,
}

impl Sender {
    pub(super) fn send(&self, message: Message) {
        if self.queue.send(message).is_ok() {
            let hwnd = self.hwnd.load(std::sync::atomic::Ordering::Acquire);
            if hwnd != 0 {
                unsafe { let _ = PostMessageW(Some(HWND(hwnd as _)), WAKE, WPARAM(0), LPARAM(0)); }
            }
        }
    }
}

pub(super) struct Controller {
    runtime: Runtime,
    context: Context,
    receiver: mpsc::Receiver<Message>,
    app: tauri::AppHandle,
}

impl Controller {
    fn new(app: tauri::AppHandle, sender: Sender, receiver: mpsc::Receiver<Message>, preview: bool) -> Result<Self, String> {
        let runtime = Runtime::new().map_err(|e| e.to_string())?;
        runtime.set_memory_limit(96 * 1024 * 1024);
        runtime.set_max_stack_size(2 * 1024 * 1024);
        let context = Context::full(&runtime).map_err(|e| e.to_string())?;
        let requests: Arc<Mutex<HashMap<u32, tokio::sync::oneshot::Sender<()>>>> = Arc::new(Mutex::new(HashMap::new()));
        context.with(|ctx| -> Result<(), rquickjs::Error> {
            let globals = ctx.globals();
            globals.set("__nativeMeasureText", Function::new(ctx.clone(),painter::Painter::measure_text))?;
            let clock = Instant::now();
            globals.set("__nativeNow", Function::new(ctx.clone(), move || clock.elapsed().as_secs_f64() * 1000.0))?;
            let log_app = app.clone();
            globals.set("__nativeLog", Function::new(ctx.clone(), move |message: String| log_message(log_app.clone(), message)))?;
            let invoke_app = app.clone(); let invoke_sender = sender.clone();
            globals.set("__nativeInvoke", Function::new(ctx.clone(), move |id: u32, command: String, args: String| {
                let app = invoke_app.clone(); let sender = invoke_sender.clone();
                let clipboard_owner = sender.hwnd.load(std::sync::atomic::Ordering::Acquire);
                tauri::async_runtime::spawn(async move {
                    let result = match serde_json::from_str::<Value>(&args) {
                        Ok(args) => invoke(app, &command, args, preview, clipboard_owner).await,
                        Err(error) => Err(error.to_string()),
                    };
                    sender.send(Message::Reply(id, result));
                });
            }))?;
            let window_sender = sender.clone();
            globals.set("__nativeWindow", Function::new(ctx.clone(), move |command: String| window_sender.send(Message::Window(command))))?;
            globals.set("__nativeBase64", Function::new(ctx.clone(), |value: String, encode: bool| -> String {
                if encode { STANDARD.encode(value.chars().map(|ch| ch as u8).collect::<Vec<_>>()) }
                else { STANDARD.decode(value).unwrap_or_default().into_iter().map(char::from).collect() }
            }))?;
            globals.set("__nativeUuid", Function::new(ctx.clone(), || {
                let guid = unsafe { windows::Win32::System::Com::CoCreateGuid() }.unwrap_or_default();
                format!("{:?}", guid)
            }))?;
            globals.set("__nativeUrl", Function::new(ctx.clone(), |value: String| -> String {
                match url::Url::parse(&value) {
                    Ok(url) => {
                        let mut base = url.clone(); base.set_fragment(None);
                        json!({"base":base.as_str(), "hash":url.fragment().map(|value|format!("#{value}")).unwrap_or_default(),
                            "protocol":format!("{}:",url.scheme()), "hostname":url.host_str().unwrap_or(""),
                            "username":url.username(), "password":url.password().unwrap_or(""),
                            "port":url.port().map(|value|value.to_string()).unwrap_or_default(),
                            "search":url.query().map(|value|format!("?{value}")).unwrap_or_default(),
                            "pathname":url.path(), "origin":url.origin().ascii_serialization()}).to_string()
                    }
                    Err(_) => "null".to_string(),
                }
            }))?;
            let fetch_sender = sender.clone(); let fetch_requests = requests.clone();
            globals.set("__nativeFetch", Function::new(ctx.clone(), move |id: u32, url: String, upload: u32| {
                let sender = fetch_sender.clone(); let requests = fetch_requests.clone();
                let (cancel, cancelled) = tokio::sync::oneshot::channel();
                if let Ok(mut requests) = requests.lock() { requests.insert(id, cancel); }
                tauri::async_runtime::spawn(async move {
                    let result = tokio::select! {
                        result = fetch(&sender, id, &url, upload) => result,
                        _ = cancelled => return,
                    };
                    if let Ok(mut requests) = requests.lock() { requests.remove(&id); }
                    match result {
                        Ok(()) => sender.send(Message::Fetch(id, json!({"done":true}))),
                        Err(error) => sender.send(Message::Fetch(id, json!({"error":error}))),
                    }
                });
            }))?;
            globals.set("__nativeFetchCancel", Function::new(ctx.clone(), move |id: u32| {
                if let Ok(mut requests) = requests.lock() {
                    if let Some(cancel) = requests.remove(&id) { let _ = cancel.send(()); }
                }
            }))?;
            if let Err(error) = ctx.eval::<(), _>(SOURCE) {
                let detail = ctx.catch();
                let _ = ctx.globals().set("__nativeInitError", detail);
                let text: String = ctx.eval("String(__nativeInitError?.stack || __nativeInitError)").unwrap_or_default();
                log_message(app.clone(), format!("Native UI initialization: {error}: {text}"));
                return Err(error);
            }
            Ok(())
        }).map_err(|e| e.to_string())?;
        Ok(Self { runtime, context, receiver, app })
    }

    pub(super) fn evaluate(&self, script: &str) {
        self.context.with(|ctx| {
            if let Err(error) = ctx.eval::<(), _>(script) {
                let caught = ctx.catch(); let _ = ctx.globals().set("__nativeLastError", caught);
                let detail: String = ctx.eval("String(__nativeLastError?.stack || __nativeLastError)").unwrap_or_default();
                log_message(self.app.clone(), format!("Native UI: {error}: {detail}"));
            }
        });
        self.jobs();
    }

    fn jobs(&self) {
        for _ in 0..1024 {
            match self.runtime.execute_pending_job() {
                Ok(true) => {},
                Ok(false) => break,
                Err(error) => { log_message(self.app.clone(), format!("Native UI job: {error:?}")); break; }
            }
        }
    }

    pub(super) fn drain(&self) -> Vec<String> {
        let mut windows = Vec::new();
        for message in self.receiver.try_iter() {
            match message {
                Message::Reply(id, result) => {
                    let (success, value) = match result { Ok(value) => (true, value), Err(error) => (false, json!(error)) };
                    self.evaluate(&format!("__nativeResolve({id},{success},{value});"));
                }
                Message::Event(name, payload) => self.evaluate(&format!("__nativeEvent({},{});", json!(name), payload)),
                Message::Fetch(id, event) => self.evaluate(&format!("__nativeFetchEvent({id},{event});")),
                Message::Window(command) => windows.push(command),
            }
        }
        self.evaluate("__nativeTick();");
        windows
    }

    fn scene(&self) -> Result<Option<painter::Scene>, String> {
        self.context.with(|ctx| {
            let dirty: bool = ctx.eval("__nativeDirty()").map_err(|e| e.to_string())?;
            if !dirty { return Ok(None); }
            let scene: String = ctx.eval("__nativeBuildScene()").map_err(|e| e.to_string())?;
            serde_json::from_str(&scene).map(Some).map_err(|e| e.to_string())
        })
    }

    pub(super) fn next_wake(&self) -> u32 {
        self.context.with(|ctx| ctx.eval::<f64, _>("__nativeNextWake()").unwrap_or(1000.0)) as u32
    }
}

fn encode<T: serde::Serialize>(value: T) -> Result<Value, String> { serde_json::to_value(value).map_err(|e| e.to_string()) }
fn string_arg(args: &Value, key: &str) -> Result<String, String> {
    args[key].as_str().map(str::to_string).ok_or_else(|| format!("Missing {key}"))
}

fn preview_blocks(command: &str) -> bool {
    matches!(command, "start_vpn" | "stop_vpn" | "cancel_vpn_start" | "switch_vpn_outbound" | "forget_vpn_outbound" | "save_settings" | "install_update" | "confirm_launch_health")
}

async fn invoke(app: tauri::AppHandle, command: &str, args: Value, preview: bool, clipboard_owner: usize) -> Result<Value, String> {
    if preview && preview_blocks(command) { return Err("Режим просмотра: VPN и рабочие настройки защищены от изменений".to_string()); }
    if preview {
        match command {
            "get_vpn_status" => return Ok(json!("Connected")),
            "get_vpn_runtime_snapshot" => return Ok(json!({"status":"Connected","desiredRunning":true,"competingVpn":false})),
            "get_vpn_started_at" => return Ok(json!(*PREVIEW_STARTED_AT)),
            "get_vpn_network_stats" => return Ok(json!({"available":false,"received":0,"transmitted":0})),
            "get_kill_switch_status" => return Ok(json!("Off")),
            "set_resume_on_boot" => return Ok(Value::Null),
            "probe_profiles" => return Ok(json!([])),
            "check_for_update" => return Ok(Value::Null),
            "load_settings" => {
                let path = app_data_dir(&app)?.join("settings.dat");
                return encode(if path.exists() { read_protected_settings(&path)? } else { "{}".to_string() });
            }
            _ => {},
        }
    }
    match command {
        "get_vpn_status" => encode(get_vpn_status().await),
        "get_vpn_runtime_snapshot" => get_vpn_runtime_snapshot().await,
        "get_vpn_started_at" => encode(get_vpn_started_at().await),
        "get_vpn_network_stats" => encode(get_vpn_network_stats().await?),
        "get_kill_switch_status" => encode(get_kill_switch_status().await),
        "start_vpn" => { start_vpn(string_arg(&args, "config")?, args["killSwitch"].as_bool().unwrap_or(false)).await?; Ok(Value::Null) }
        "stop_vpn" => { stop_vpn().await?; Ok(Value::Null) }
        "cancel_vpn_start" => { cancel_vpn_start().await?; Ok(Value::Null) }
        "switch_vpn_outbound" => { switch_vpn_outbound(string_arg(&args, "outbound")?).await?; Ok(Value::Null) }
        "forget_vpn_outbound" => { forget_vpn_outbound(string_arg(&args, "outbound")?).await?; Ok(Value::Null) }
        "set_resume_on_boot" => { set_resume_on_boot(args["enabled"].as_bool().unwrap_or(false)).await?; Ok(Value::Null) }
        "load_settings" => encode(load_settings(app.clone(), app.state::<AppState>())?),
        "save_settings" => { save_settings(app.clone(), app.state::<AppState>(), string_arg(&args, "settings")?)?; Ok(Value::Null) }
        "get_app_version" => encode(get_app_version(app)),
        "is_autostart_launch" => encode(is_autostart_launch(app.state::<AppState>())),
        "is_post_update_launch" => encode(is_post_update_launch(app.state::<AppState>())),
        "confirm_launch_health" => { confirm_launch_health(app.clone(), app.state::<AppState>())?; Ok(Value::Null) }
        "update_tray_menu" => {
            let snapshot = serde_json::from_value(args["snapshot"].clone()).map_err(|e| e.to_string())?;
            update_tray_menu(app.clone(), app.state::<AppState>(), snapshot)?; Ok(Value::Null)
        }
        "fetch_subscription" => encode(fetch_subscription(string_arg(&args, "url")?).await?),
        "probe_profiles" => {
            let config = string_arg(&args, "config")?;
            encode(tauri::async_runtime::spawn_blocking(move || probe_profiles(app, config)).await.map_err(|e|e.to_string())??)
        }
        "export_diagnostics" => {
            let summary = serde_json::from_value(args["settingsSummary"].clone()).map_err(|e|e.to_string())?;
            encode(export_diagnostics(app, summary).await?)
        }
        "check_for_update" => encode(updates::check_for_update(app).await?),
        "install_update" => { updates::install_update(app, string_arg(&args, "expectedVersion")?).await?; Ok(Value::Null) }
        "log_message" => { log_message(app, string_arg(&args,"message")?); Ok(Value::Null) }
        "select_executable" => encode(tauri::async_runtime::spawn_blocking(select_executable).await.map_err(|e|e.to_string())??),
        "get_running_processes" => encode(get_running_processes(args["excludeSystem"].as_bool().unwrap_or(true))?),
        "native_clipboard_read" => encode(window::read_clipboard()?),
        "native_clipboard_write" => { window::write_clipboard(&string_arg(&args,"text")?,HWND(clipboard_owner as _))?; Ok(Value::Null) }
        "native_notification" => {
            use tauri_plugin_notification::NotificationExt;
            app.notification().builder().title(string_arg(&args,"title")?).body(string_arg(&args,"body")?).show().map_err(|e|e.to_string())?;
            Ok(Value::Null)
        }
        _ => Err(format!("Unknown native command: {command}")),
    }
}

fn measurement_client() -> Result<reqwest::Client, reqwest::Error> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    reqwest::Client::builder().timeout(std::time::Duration::from_secs(20)).build()
}

async fn fetch(sender: &Sender, id: u32, value: &str, upload: u32) -> Result<(), String> {
    let url = url::Url::parse(value).map_err(|e|e.to_string())?;
    let allowed = url.host_str() == Some("speed.cloudflare.com") && matches!(url.path(), "/__down" | "/__up")
        || upload == 0 && url.host_str() == Some("www.google.com") && url.path() == "/generate_204";
    if url.scheme() != "https" || !allowed {
        return Err("Unsupported native network measurement endpoint".to_string());
    }
    let client = measurement_client().map_err(|e|e.to_string())?;
    let response = if upload == 0 { client.get(url).send().await }
    else {
        if upload > 8 * 1024 * 1024 { return Err("Upload too large".to_string()); }
        let (tx, rx) = tokio::sync::mpsc::channel::<Result<Vec<u8>, std::io::Error>>(2);
        let progress = sender.clone();
        let producer = tauri::async_runtime::spawn(async move {
            let mut loaded = 0;
            while loaded < upload {
                let length = (upload - loaded).min(64 * 1024);
                if tx.send(Ok(vec![0; length as usize])).await.is_err() { break; }
                loaded += length; progress.send(Message::Fetch(id, json!({"loaded":loaded})));
            }
        });
        struct AbortOnDrop(tauri::async_runtime::JoinHandle<()>);
        impl Drop for AbortOnDrop { fn drop(&mut self) { self.0.abort(); } }
        let _producer = AbortOnDrop(producer);
        let stream = tokio_stream::wrappers::ReceiverStream::new(rx);
        client.post(url).header(reqwest::header::CONTENT_LENGTH, upload).body(reqwest::Body::wrap_stream(stream)).send().await
    }.map_err(|e|e.to_string())?;
    sender.send(Message::Fetch(id, json!({"status":response.status().as_u16()})));
    let mut response = response;
    while let Some(chunk) = response.chunk().await.map_err(|e|e.to_string())? {
        sender.send(Message::Fetch(id, json!({"bytes":chunk.len()})));
    }
    Ok(())
}

pub(crate) fn run(autostart_launch: bool, post_update_launch: bool, rollback_shutdown: bool) -> Result<(), String> {
    let preview = std::env::args().any(|arg|arg == "--native-preview");
    let (queue, receiver) = mpsc::channel();
    let sender = Sender { queue, hwnd: Arc::new(std::sync::atomic::AtomicUsize::new(0)) };
    let mut context = tauri::generate_context!();
    // Even a direct cargo run of the native feature must never create a WebView.
    context.config_mut().app.windows.clear();
    let mut builder = tauri::Builder::default()
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_notification::init());
    if !preview {
        let show = sender.clone();
        builder = builder.plugin(tauri_plugin_single_instance::init(move |app, args, _| {
            if args.iter().any(|arg|arg == "--rollback-shutdown") { app.exit(0); }
            else if !args.iter().any(|arg|arg == "--autostart") { show.send(Message::Window("show".to_string())); }
        }));
    }
    builder.manage(AppState { settings_io: Mutex::new(()), tray_menu_io: Mutex::new(()), autostart_launch, post_update_launch })
        .setup(move |app| {
            if rollback_shutdown { app.handle().exit(0); return Ok(()); }
            let initial = tray_menu::build(app.handle(), &tray_menu::TrayMenuSnapshot::default()).map_err(std::io::Error::other)?;
            let menu_sender = sender.clone(); let icon_sender = sender.clone();
            tauri::tray::TrayIconBuilder::with_id(tray_menu::TRAY_ID).icon(app.default_window_icon().cloned().unwrap())
                .tooltip(if preview { "Warpy — просмотр без изменения VPN" } else { &initial.tooltip })
                .menu(&initial.menu).show_menu_on_left_click(false)
                .on_menu_event(move |app, event| match event.id.as_ref() {
                    "show" => menu_sender.send(Message::Window("show".to_string())),
                    "quit" => {
                        if preview { app.exit(0); }
                        else { let app = app.clone(); tauri::async_runtime::spawn(async move {
                            match stop_vpn().await { Ok(()) => app.exit(0), Err(error) => log_message(app, error) }
                        }); }
                    }
                    id => if let Some(command) = tray_menu::command_from_menu_id(id) {
                        menu_sender.send(Message::Event(tray_menu::TRAY_COMMAND_EVENT.to_string(), serde_json::to_value(command).unwrap_or_default()));
                    },
                })
                .on_tray_icon_event(move |_, event| if matches!(event, tauri::tray::TrayIconEvent::Click { button:tauri::tray::MouseButton::Left, button_state:tauri::tray::MouseButtonState::Up,..}) {
                    icon_sender.send(Message::Window("toggle".to_string()));
                }).build(app)?;
            let progress = sender.clone();
            app.listen("warpy://update-progress", move |event| if let Ok(payload) = serde_json::from_str(event.payload()) {
                progress.send(Message::Event("warpy://update-progress".to_string(), payload));
            });
            if !preview {
                if let Err(error) = service_call(vpn_ipc::VpnRequest::AttachUi { process_id:std::process::id() }) { log_message(app.handle().clone(), error); }
            }
            let handle = app.handle().clone();
            thread::Builder::new().name("warpy-native-window".to_string()).stack_size(8 * 1024 * 1024).spawn(move || {
                if let Err(error) = window::run(handle.clone(), sender, receiver, preview, autostart_launch) {
                    log_message(handle.clone(), format!("Native window failed: {error}")); handle.exit(1);
                }
            })?;
            Ok(())
        }).run(context).map_err(|e|e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn native_measurement_client_initializes_tls_without_updater_startup() {
        let _client = measurement_client().unwrap();
        assert!(rustls::crypto::CryptoProvider::get_default().is_some());
    }
    #[test]
    fn preview_blocks_all_connection_and_settings_mutations() {
        for command in ["start_vpn", "stop_vpn", "cancel_vpn_start", "switch_vpn_outbound", "forget_vpn_outbound", "save_settings", "install_update"] { assert!(preview_blocks(command), "{command}"); }
        assert!(!preview_blocks("load_settings"));
    }

    #[test]
    #[ignore = "manual native frame performance measurement"]
    fn native_connected_frame_timings() {
        use windows::{core::w, Win32::{System::Com::*, UI::WindowsAndMessaging::*}};
        let (_runtime, context) = native_test_runtime();
        unsafe {
            let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
            let hwnd = CreateWindowExW(WINDOW_EX_STYLE::default(), w!("STATIC"), w!("Warpy frame benchmark"),
                WS_POPUP, 0, 0, 420, 720, None, None, None, None).unwrap();
            let flags = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("flags");
            let mut painter = painter::Painter::new(hwnd, 420, 720, 96, flags).unwrap();
            let mut timings = Vec::new();
            context.with(|ctx| {
                let clock = Instant::now();
                ctx.globals().set("__nativeBenchNow", Function::new(ctx.clone(), move || clock.elapsed().as_secs_f64()*1000.0).unwrap()).unwrap();
                ctx.eval::<(),_>(r#"
                    globalThis.__nativeBenchStages = {};
                    for (const name of ['nativeMain', 'nativeTopBar', 'nativeOtherDialogs']) {
                        const original = globalThis[name];
                        globalThis[name] = (...args) => {
                            const start = __nativeBenchNow();
                            const result = original(...args);
                            __nativeBenchStages[name] = __nativeBenchNow() - start;
                            return result;
                        };
                    }
                "#).unwrap();
                for frame in 0..60 {
                    let start = Instant::now();
                    ctx.eval::<(),_>(format!("__clock={}; __nativeTick();", 5000 + frame * 34)).unwrap();
                    let tick = start.elapsed().as_secs_f64() * 1000.0;
                    let start = Instant::now();
                    let json: String = ctx.eval("__nativeBuildScene()").unwrap();
                    let scene = start.elapsed().as_secs_f64() * 1000.0;
                    let start = Instant::now();
                    let parsed = serde_json::from_str(&json).unwrap();
                    let decode = start.elapsed().as_secs_f64() * 1000.0;
                    let start = Instant::now();
                    painter.paint(&parsed).unwrap();
                    let paint = start.elapsed().as_secs_f64()*1000.0;
                    let stages: String = ctx.eval("JSON.stringify(__nativeBenchStages)").unwrap();
                    timings.push(json!({"tickMs":tick,"sceneMs":scene,"decodeMs":decode,"paintMs":paint,"stages":serde_json::from_str::<Value>(&stages).unwrap()}));
                }
            });
            drop(painter);
            let _ = DestroyWindow(hwnd);
            let output = std::env::var_os("WARPY_NATIVE_BENCH_OUTPUT").map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../.artifacts/native-ui-preview/frame-timings.json"));
            fs::create_dir_all(output.parent().unwrap()).unwrap();
            fs::write(&output, serde_json::to_vec_pretty(&timings).unwrap()).unwrap();
            println!("Native frame timings: {}", output.display());
        }
    }

    fn native_test_runtime() -> (Runtime, Context) {
        let runtime = Runtime::new().unwrap();
        runtime.set_max_stack_size(2 * 1024 * 1024);
        let context = Context::full(&runtime).unwrap();
        context.with(|ctx| {
            ctx.globals().set("__nativeMeasureText",Function::new(ctx.clone(),painter::Painter::measure_text).unwrap()).unwrap();
            ctx.eval::<(),_>(r#"
                globalThis.__requests = []; globalThis.__errors = [];
                globalThis.__clock = 0;
                globalThis.__nativeNow = () => __clock;
                Date.now = () => 1700000000000;
                globalThis.__nativeLog = value => __errors.push(value);
                globalThis.__nativeInvoke = (id,name,args) => __requests.push({id,name,args});
                globalThis.__nativeWindow = () => {};
                globalThis.__nativeFetch = () => {};
                globalThis.__nativeFetchCancel = () => {};
                globalThis.__nativeBase64 = () => '';
                globalThis.__nativeUrl = () => '{}';
                globalThis.__nativeUuid = () => 'test';
            "#).unwrap();
            if let Err(error) = ctx.eval::<(),_>(SOURCE) {
                let exception = ctx.catch(); ctx.globals().set("caught",exception).unwrap();
                let detail: String = ctx.eval("String(caught.stack || caught)").unwrap();
                panic!("{error}: {detail}");
            }
        });
        for _ in 0..50 {
            while runtime.execute_pending_job().unwrap() {}
            context.with(|ctx|ctx.eval::<(),_>(r#"
                for (const request of __requests.splice(0)) {
                    let value = null;
                    if (request.name === 'load_settings') value = JSON.stringify({lang:'ru',active:0,profiles:[{protocol:'vless',name:'SNKT',host:'192.0.2.1',port:2053,uuid:'00000000-0000-4000-8000-000000000001',security:'reality',sni:'example.com',pbk:'test'}]});
                    if (request.name === 'get_vpn_runtime_snapshot') value = {status:'Connected',desiredRunning:true};
                    if (request.name === 'get_vpn_started_at') value = Date.now()-24342000;
                    if (request.name === 'get_vpn_network_stats') value = {available:false};
                    if (request.name === 'probe_profiles') value = [];
                    if (request.name === 'get_kill_switch_status') value = 'Off';
                    if (request.name === 'get_app_version') value = '1.0.7';
                    __nativeResolve(request.id,true,value);
                }
            "#).unwrap());
        }
        (runtime, context)
    }

    #[test]
    fn actual_quickjs_engine_runs_the_controller_and_draws_settings() {
        let (_runtime, context) = native_test_runtime();
        context.with(|ctx| {
            let errors: String = ctx.eval("__errors.filter(value => /TypeError|ReferenceError/.test(value)).join('\\n')").unwrap();
            assert!(errors.is_empty(),"{errors}");
            ctx.eval::<(),_>("document.getElementById('btn-settings').dispatchEvent(new Event('click')); __nativeBuildScene();").unwrap();
            let count: i32 = ctx.eval("new Set(nativeScene.hits.filter(hit => hit.kind === 'toggle').map(hit=>hit.uid)).size").unwrap();
            assert_eq!(count,4);
            let directory = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../.artifacts/native-ui-preview");
            fs::create_dir_all(&directory).unwrap();
            let snapshots = [
                ("settings", ""),
                ("tunneling", "document.getElementById('btn-open-tunneling').dispatchEvent(new Event('click'))"),
                ("main", "document.getElementById('overlay-settings').classList.add('hidden'); __clock=1500; __nativeTick(); __clock=3000; __nativeTick()"),
                ("profiles", "document.getElementById('btn-profiles').dispatchEvent(new Event('click'))"),
                ("share", "document.getElementById('profile-list').querySelector('.p-share').dispatchEvent(new Event('click'))"),
                ("add", "document.getElementById('overlay-share').classList.add('hidden'); document.getElementById('overlay-profiles').classList.add('hidden'); document.getElementById('btn-add').dispatchEvent(new Event('click'))"),
            ];
            for (name, navigation) in snapshots {
                ctx.eval::<(),_>(navigation).unwrap();
                let message: String = ctx.eval("document.getElementById('overlay-message').classList.contains('hidden') ? '' : document.getElementById('message-msg').textContent").unwrap();
                assert!(message.is_empty(),"Unexpected dialog in {name}: {message}");
                let json: String = ctx.eval("__nativeBuildScene()").unwrap();
                let scene = serde_json::from_str(&json).unwrap();
                painter::Painter::snapshot(&scene,&directory.join(format!("{name}.png"))).unwrap();
            }
        });
    }
}
