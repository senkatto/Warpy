mod config;
mod controller;
mod imports;
mod measurements;
mod model;
mod painter;
mod particles;
mod qr;
mod view;
mod warp;
mod window;
use controller::Controller;

use super::*;
#[cfg(test)]
use rquickjs::{Context, Function, Runtime};
use serde_json::{json, Value};
use std::{
    sync::{mpsc, Arc},
    thread,
    time::Instant,
};
use tauri::Listener;
use windows::Win32::{
    Foundation::{HWND, LPARAM, WPARAM},
    UI::WindowsAndMessaging::PostMessageW,
};

const WAKE: u32 = 0x8001;
#[cfg(test)]
const SOURCE: &str = include_str!("../native-generated/ui.js");
static PREVIEW_STARTED_AT: std::sync::LazyLock<u64> = std::sync::LazyLock::new(|| {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
        - 6 * 60 * 60 * 1000
});
fn assets() -> &'static Value {
    static ASSETS: std::sync::LazyLock<Value> = std::sync::LazyLock::new(|| {
        serde_json::from_str(include_str!("../native-generated/client-assets.json"))
            .expect("valid client assets")
    });
    &ASSETS
}

pub(super) enum Message {
    Reply(u32, Result<Value, String>),
    Event(String, Value),
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
                unsafe {
                    let _ = PostMessageW(Some(HWND(hwnd as _)), WAKE, WPARAM(0), LPARAM(0));
                }
            }
        }
    }
}

fn encode<T: serde::Serialize>(value: T) -> Result<Value, String> {
    serde_json::to_value(value).map_err(|e| e.to_string())
}
fn string_arg(args: &Value, key: &str) -> Result<String, String> {
    args[key]
        .as_str()
        .map(str::to_string)
        .ok_or_else(|| format!("Missing {key}"))
}

fn preview_blocks(command: &str) -> bool {
    matches!(
        command,
        "start_vpn"
            | "stop_vpn"
            | "cancel_vpn_start"
            | "switch_vpn_outbound"
            | "forget_vpn_outbound"
            | "save_settings"
            | "install_update"
            | "confirm_launch_health"
    )
}

async fn invoke(
    app: tauri::AppHandle,
    command: &str,
    args: Value,
    preview: bool,
    clipboard_owner: usize,
) -> Result<Value, String> {
    if preview && preview_blocks(command) {
        return Err("Режим просмотра: VPN и рабочие настройки защищены от изменений".to_string());
    }
    if preview {
        match command {
            "get_vpn_status" => return Ok(json!("Connected")),
            "get_vpn_runtime_snapshot" => {
                return Ok(json!({"status":"Connected","desiredRunning":true,"competingVpn":false}))
            }
            "get_vpn_started_at" => return Ok(json!(*PREVIEW_STARTED_AT)),
            "get_vpn_network_stats" => {
                return Ok(json!({"available":false,"received":0,"transmitted":0}))
            }
            "get_kill_switch_status" => return Ok(json!("Off")),
            "set_resume_on_boot" => return Ok(Value::Null),
            "probe_profiles" => return Ok(json!([])),
            "check_for_update" => return Ok(Value::Null),
            "load_settings" => {
                let path = app_data_dir(&app)?.join("settings.dat");
                return encode(if path.exists() {
                    read_protected_settings(&path)?
                } else {
                    "{}".to_string()
                });
            }
            _ => {}
        }
    }
    match command {
        "get_vpn_status" => encode(get_vpn_status().await),
        "get_vpn_runtime_snapshot" => get_vpn_runtime_snapshot().await,
        "get_vpn_started_at" => encode(get_vpn_started_at().await),
        "get_vpn_network_stats" => encode(get_vpn_network_stats().await?),
        "get_kill_switch_status" => encode(get_kill_switch_status().await),
        "start_vpn" => {
            start_vpn(
                string_arg(&args, "config")?,
                args["killSwitch"].as_bool().unwrap_or(false),
            )
            .await?;
            Ok(Value::Null)
        }
        "stop_vpn" => {
            stop_vpn().await?;
            Ok(Value::Null)
        }
        "cancel_vpn_start" => {
            cancel_vpn_start().await?;
            Ok(Value::Null)
        }
        "switch_vpn_outbound" => {
            switch_vpn_outbound(string_arg(&args, "outbound")?).await?;
            Ok(Value::Null)
        }
        "forget_vpn_outbound" => {
            forget_vpn_outbound(string_arg(&args, "outbound")?).await?;
            Ok(Value::Null)
        }
        "set_resume_on_boot" => {
            set_resume_on_boot(args["enabled"].as_bool().unwrap_or(false)).await?;
            Ok(Value::Null)
        }
        "load_settings" => encode(load_settings(app.clone(), app.state::<AppState>())?),
        "save_settings" => {
            save_settings(
                app.clone(),
                app.state::<AppState>(),
                string_arg(&args, "settings")?,
            )?;
            Ok(Value::Null)
        }
        "get_app_version" => encode(get_app_version(app)),
        "is_autostart_launch" => encode(is_autostart_launch(app.state::<AppState>())),
        "is_post_update_launch" => encode(is_post_update_launch(app.state::<AppState>())),
        "confirm_launch_health" => {
            confirm_launch_health(app.clone(), app.state::<AppState>())?;
            Ok(Value::Null)
        }
        "update_tray_menu" => {
            let snapshot =
                serde_json::from_value(args["snapshot"].clone()).map_err(|e| e.to_string())?;
            update_tray_menu(app.clone(), app.state::<AppState>(), snapshot)?;
            Ok(Value::Null)
        }
        "fetch_subscription" => encode(fetch_subscription(string_arg(&args, "url")?).await?),
        "probe_profiles" => {
            let config = string_arg(&args, "config")?;
            encode(
                tauri::async_runtime::spawn_blocking(move || probe_profiles(app, config))
                    .await
                    .map_err(|e| e.to_string())??,
            )
        }
        "export_diagnostics" => {
            let summary = serde_json::from_value(args["settingsSummary"].clone())
                .map_err(|e| e.to_string())?;
            encode(export_diagnostics(app, summary).await?)
        }
        "check_for_update" => encode(updates::check_for_update(app).await?),
        "install_update" => {
            updates::install_update(app, string_arg(&args, "expectedVersion")?).await?;
            Ok(Value::Null)
        }
        "log_message" => {
            log_message(app, string_arg(&args, "message")?);
            Ok(Value::Null)
        }
        "select_executable" => encode(
            tauri::async_runtime::spawn_blocking(select_executable)
                .await
                .map_err(|e| e.to_string())??,
        ),
        "get_running_processes" => encode(get_running_processes(
            args["excludeSystem"].as_bool().unwrap_or(true),
        )?),
        "native_clipboard_read" => encode(window::read_clipboard()?),
        "native_clipboard_write" => {
            window::write_clipboard(&string_arg(&args, "text")?, HWND(clipboard_owner as _))?;
            Ok(Value::Null)
        }
        "native_notification" => {
            use tauri_plugin_notification::NotificationExt;
            app.notification()
                .builder()
                .title(string_arg(&args, "title")?)
                .body(string_arg(&args, "body")?)
                .show()
                .map_err(|e| e.to_string())?;
            Ok(Value::Null)
        }
        _ => Err(format!("Unknown native command: {command}")),
    }
}

fn measurement_client() -> Result<reqwest::Client, reqwest::Error> {
    let _ = rustls::crypto::ring::default_provider().install_default();
    reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(20))
        .build()
}

pub(crate) fn run(
    autostart_launch: bool,
    post_update_launch: bool,
    rollback_shutdown: bool,
) -> Result<(), String> {
    let preview = std::env::args().any(|arg| arg == "--native-preview");
    let (queue, receiver) = mpsc::channel();
    let sender = Sender {
        queue,
        hwnd: Arc::new(std::sync::atomic::AtomicUsize::new(0)),
    };
    let mut context = tauri::generate_context!();
    // Even a direct cargo run of the native feature must never create a WebView.
    context.config_mut().app.windows.clear();
    let mut builder = tauri::Builder::default()
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_notification::init());
    if !preview {
        let show = sender.clone();
        builder = builder.plugin(tauri_plugin_single_instance::init(move |app, args, _| {
            if args.iter().any(|arg| arg == "--rollback-shutdown") {
                app.exit(0);
            } else if !args.iter().any(|arg| arg == "--autostart") {
                show.send(Message::Window("show".to_string()));
            }
        }));
    }
    builder
        .manage(AppState {
            settings_io: Mutex::new(()),
            tray_menu_io: Mutex::new(()),
            autostart_launch,
            post_update_launch,
        })
        .setup(move |app| {
            if rollback_shutdown {
                app.handle().exit(0);
                return Ok(());
            }
            let initial = tray_menu::build(app.handle(), &tray_menu::TrayMenuSnapshot::default())
                .map_err(std::io::Error::other)?;
            let menu_sender = sender.clone();
            let icon_sender = sender.clone();
            tauri::tray::TrayIconBuilder::with_id(tray_menu::TRAY_ID)
                .icon(app.default_window_icon().cloned().unwrap())
                .tooltip(if preview {
                    "Warpy — просмотр без изменения VPN"
                } else {
                    &initial.tooltip
                })
                .menu(&initial.menu)
                .show_menu_on_left_click(false)
                .on_menu_event(move |app, event| match event.id.as_ref() {
                    "show" => menu_sender.send(Message::Window("show".to_string())),
                    "quit" => {
                        if preview {
                            app.exit(0);
                        } else {
                            let app = app.clone();
                            tauri::async_runtime::spawn(async move {
                                match stop_vpn().await {
                                    Ok(()) => app.exit(0),
                                    Err(error) => log_message(app, error),
                                }
                            });
                        }
                    }
                    id => {
                        if let Some(command) = tray_menu::command_from_menu_id(id) {
                            menu_sender.send(Message::Event(
                                tray_menu::TRAY_COMMAND_EVENT.to_string(),
                                serde_json::to_value(command).unwrap_or_default(),
                            ));
                        }
                    }
                })
                .on_tray_icon_event(move |_, event| {
                    if matches!(
                        event,
                        tauri::tray::TrayIconEvent::Click {
                            button: tauri::tray::MouseButton::Left,
                            button_state: tauri::tray::MouseButtonState::Up,
                            ..
                        }
                    ) {
                        icon_sender.send(Message::Window("toggle".to_string()));
                    }
                })
                .build(app)?;
            let progress = sender.clone();
            app.listen("warpy://update-progress", move |event| {
                if let Ok(payload) = serde_json::from_str(event.payload()) {
                    progress.send(Message::Event(
                        "warpy://update-progress".to_string(),
                        payload,
                    ));
                }
            });
            if !preview {
                if let Err(error) = service_call(vpn_ipc::VpnRequest::AttachUi {
                    process_id: std::process::id(),
                }) {
                    log_message(app.handle().clone(), error);
                }
            }
            let handle = app.handle().clone();
            thread::Builder::new()
                .name("warpy-native-window".to_string())
                .stack_size(8 * 1024 * 1024)
                .spawn(move || {
                    if let Err(error) =
                        window::run(handle.clone(), sender, receiver, preview, autostart_launch)
                    {
                        log_message(handle.clone(), format!("Native window failed: {error}"));
                        handle.exit(1);
                    }
                })?;
            Ok(())
        })
        .run(context)
        .map_err(|e| e.to_string())
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
        for command in [
            "start_vpn",
            "stop_vpn",
            "cancel_vpn_start",
            "switch_vpn_outbound",
            "forget_vpn_outbound",
            "save_settings",
            "install_update",
        ] {
            assert!(preview_blocks(command), "{command}");
        }
        assert!(!preview_blocks("load_settings"));
    }

    #[test]
    #[ignore = "manual native frame performance measurement"]
    fn native_connected_frame_timings() {
        use windows::{
            core::w,
            Win32::{System::Com::*, UI::WindowsAndMessaging::*},
        };
        let (_runtime, context) = native_test_runtime("Connected");
        unsafe {
            let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
            let hwnd = CreateWindowExW(
                WINDOW_EX_STYLE::default(),
                w!("STATIC"),
                w!("Warpy frame benchmark"),
                WS_POPUP,
                0,
                0,
                420,
                720,
                None,
                None,
                None,
                None,
            )
            .unwrap();
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
                    painter.paint(&parsed,(5000 + frame * 34) as f64).unwrap();
                    let paint = start.elapsed().as_secs_f64()*1000.0;
                    let stages: String = ctx.eval("JSON.stringify(__nativeBenchStages)").unwrap();
                    timings.push(json!({"tickMs":tick,"sceneMs":scene,"decodeMs":decode,"paintMs":paint,"stages":serde_json::from_str::<Value>(&stages).unwrap()}));
                }
            });
            drop(painter);
            let _ = DestroyWindow(hwnd);
            let output = std::env::var_os("WARPY_NATIVE_BENCH_OUTPUT")
                .map(PathBuf::from)
                .unwrap_or_else(|| {
                    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
                        .join("../../.artifacts/native-ui-preview/frame-timings.json")
                });
            fs::create_dir_all(output.parent().unwrap()).unwrap();
            fs::write(&output, serde_json::to_vec_pretty(&timings).unwrap()).unwrap();
            println!("Native frame timings: {}", output.display());
        }
    }

    fn test_settings() -> Value {
        json!({"lang":"ru","active":0,"profiles":[{"protocol":"vless","name":"SNKT","host":"192.0.2.1","port":2053,"uuid":"00000000-0000-4000-8000-000000000001","security":"reality","sni":"example.com","pbk":"test"}]})
    }
    fn native_test_runtime(status: &str) -> (Runtime, Context) {
        native_test_runtime_with_settings(status, test_settings())
    }
    fn native_test_runtime_with_settings(status: &str, settings: Value) -> (Runtime, Context) {
        let runtime = Runtime::new().unwrap();
        runtime.set_max_stack_size(2 * 1024 * 1024);
        let context = Context::full(&runtime).unwrap();
        context.with(|ctx| {
            ctx.globals().set("__testStatus", status).unwrap();
            ctx.globals().set("__testSettings",settings.to_string()).unwrap();
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

                globalThis.__nativeUuid = () => 'test';
            "#).unwrap();
            ctx.globals().set("__nativeUrl",Function::new(ctx.clone(),|value:String|->String{
                match url::Url::parse(&value){Ok(url)=>{let mut base=url.clone();base.set_fragment(None);json!({"base":base.as_str(),"hash":url.fragment().map(|value|format!("#{value}")).unwrap_or_default(),"protocol":format!("{}:",url.scheme()),"hostname":url.host_str().unwrap_or(""),"username":url.username(),"password":url.password().unwrap_or(""),"port":url.port().map(|v|v.to_string()).unwrap_or_default(),"search":url.query().map(|v|format!("?{v}")).unwrap_or_default(),"pathname":url.path(),"origin":url.origin().ascii_serialization()}).to_string()},Err(_)=>"null".into()}
            }).unwrap()).unwrap();
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
                    if (request.name === 'load_settings') value = __testSettings;
                    if (request.name === 'get_vpn_runtime_snapshot') value = {status:__testStatus,desiredRunning:__testStatus!=='Stopped'};
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
    fn native_particles_match_the_original_javascript_animation() {
        for status in ["Connected", "Starting"] {
            let (_runtime, context) = native_test_runtime(status);
            context.with(|ctx| {
                ctx.eval::<(), _>("delete NativeCanvas.prototype.nativeParticleFrame;")
                    .unwrap();
                for time in [1000, 1180, 1500, 2500, 4000, 12000] {
                    ctx.eval::<(), _>(format!("__clock={time}; __nativeTick();"))
                        .unwrap();
                    let json: String = ctx
                        .eval(
                            "JSON.stringify(document.getElementById('particles').nativeCanvas.ops)",
                        )
                        .unwrap();
                    let reference: Vec<Value> = serde_json::from_str(&json).unwrap();
                    let connected = status == "Connected";
                    let dots: Vec<_> = particles::frame(
                        (time - 1000) as f64 / 1000.0,
                        if connected { Some(0.0) } else { None },
                        connected,
                    )
                    .collect();
                    assert_eq!(
                        reference.len(),
                        dots.len(),
                        "{status} {time}: particle count differs"
                    );
                    for (op, dot) in reference.iter().zip(dots) {
                        let radius = op["w"].as_f64().unwrap() / 2.0;
                        let x = op["x"].as_f64().unwrap() + radius;
                        let y = op["y"].as_f64().unwrap() + radius;
                        assert!(
                            (x - dot.x).abs() < 1e-7
                                && (y - dot.y).abs() < 1e-7
                                && (radius - dot.radius).abs() < 1e-7,
                            "{status} {time}: geometry differs"
                        );
                        let color: Vec<f64> = op["color"]
                            .as_str()
                            .unwrap()
                            .trim_start_matches("rgba(")
                            .trim_end_matches(')')
                            .split(',')
                            .map(|value| value.parse().unwrap())
                            .collect();
                        for (channel, value) in color.iter().enumerate() {
                            let expected = if channel < 3 { *value / 255.0 } else { *value };
                            assert!(
                                (expected - dot.color[channel]).abs() < 1e-7,
                                "{status} {time}: color differs"
                            );
                        }
                    }
                }
            });
        }
    }

    #[test]
    fn actual_quickjs_engine_runs_the_controller_and_draws_settings() {
        let (_runtime, context) = native_test_runtime("Connected");
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
                let time: f64 = ctx.eval("__clock").unwrap();
                painter::Painter::snapshot(&scene,&directory.join(format!("{name}.png")),time).unwrap();
            }
        });
    }

    #[test]
    fn rust_views_match_legacy_window_drawing() {
        let (_runtime, context) = native_test_runtime("Connected");
        context.with(|ctx|{
            let mut ui=model::Ui::new();ui.load_settings(json!({"lang":"ru","active":0,"profiles":[{"protocol":"vless","name":"SNKT","host":"192.0.2.1","port":2053,"uuid":"00000000-0000-4000-8000-000000000001","security":"reality","sni":"example.com","pbk":"test"}]})).unwrap();ui.status="connected".into();
            let directory=PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../.artifacts/rust-client-migration");fs::create_dir_all(&directory).unwrap();
            let cases=[("main","__clock=1500;__nativeTick();__clock=3000;__nativeTick();",None),("settings","document.getElementById('btn-settings').dispatchEvent(new Event('click'));",Some(model::Overlay::Settings)),("tunneling","document.getElementById('btn-open-tunneling').dispatchEvent(new Event('click'));",Some(model::Overlay::Settings)),("profiles","document.getElementById('overlay-settings').classList.add('hidden');document.getElementById('btn-profiles').dispatchEvent(new Event('click'));",Some(model::Overlay::Profiles)),("add","document.getElementById('overlay-profiles').classList.add('hidden');document.getElementById('btn-add').dispatchEvent(new Event('click'));",Some(model::Overlay::Add)),("language","document.getElementById('overlay-add').classList.add('hidden');document.getElementById('btn-settings').dispatchEvent(new Event('click'));document.getElementById('btn-language').dispatchEvent(new Event('click'));",Some(model::Overlay::Language))];
            let mut failures=Vec::new();
            for(name,navigation,overlay)in cases{
                ctx.eval::<(),_>(navigation).unwrap();
                ui.overlays.clear();
                if let Some(overlay)=overlay{ui.show(overlay);}
                if name=="tunneling"{ui.tunneling=true;}
                if name=="language"{ui.overlays.insert(model::Overlay::Settings);ui.tunneling=false;}
                let reference:String=ctx.eval("__nativeBuildScene()").unwrap();let value:Value=serde_json::from_str(&reference).unwrap();let expected:painter::Scene=serde_json::from_str(&reference).unwrap();let now:f64=ctx.eval("__clock").unwrap();
                if let Some(particle)=value["ops"].as_array().unwrap().iter().find(|op|op["kind"]=="particles"){ui.animation_start=particle["started"].as_f64().unwrap();ui.connected_at=particle["connected_at"].as_f64();}
                let uptime:String=ctx.eval("document.getElementById('uptime').textContent").unwrap();let parts:Vec<u64>=uptime.split(':').filter_map(|p|p.parse().ok()).collect();let unix=100_000_000;if parts.len()==3{ui.connected_unix=unix-(parts[0]*3600+parts[1]*60+parts[2])*1000;}
                ui.speed=ctx.eval("document.getElementById('m-speed').textContent").unwrap();let ping:String=ctx.eval("document.getElementById('m-ping').textContent").unwrap();ui.ping=ping.parse().ok();
                let actual=view::build(&ui,now,unix);painter::Painter::snapshot(&expected,&directory.join(format!("legacy-{name}.png")),now).unwrap();painter::Painter::snapshot(&actual,&directory.join(format!("rust-{name}.png")),now).unwrap();
                if expected.ops!=actual.ops{let mut differences=Vec::new();for(i,(a,b))in expected.ops.iter().zip(&actual.ops).enumerate(){if a!=b{differences.push(format!("{i}: expected {a:?}\nactual {b:?}"));}}if expected.ops.len()!=actual.ops.len(){differences.push(format!("Count: {} != {}",expected.ops.len(),actual.ops.len()));}fs::write(directory.join(format!("view-diff-{name}.txt")),differences.join("\n")).unwrap();if fs::read(directory.join(format!("legacy-{name}.png"))).unwrap()!=fs::read(directory.join(format!("rust-{name}.png"))).unwrap(){failures.push(name);}}
            }assert!(failures.is_empty(),"Views differ: {failures:?}; inspect .artifacts/rust-client-migration/view-diff-*.txt");
        });
    }
    #[test]
    fn rust_dialogs_and_connection_states_match_legacy_pixels() {
        use model::Overlay::*;
        let directory = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../.artifacts/rust-client-migration");
        let mut failures = Vec::new();
        for lang in ["ru", "en"] {
            for name in [
                "stopped",
                "connecting",
                "empty",
                "error",
                "share",
                "running",
                "speed-results",
                "confirm",
                "message",
                "unsaved",
                "update",
                "update-progress",
                "dropdown",
                "focused",
                "group",
                "unavailable",
            ] {
                let status = if name == "connecting" {
                    "Starting"
                } else if matches!(name, "stopped" | "empty") {
                    "Stopped"
                } else {
                    "Connected"
                };
                let mut settings = test_settings();
                settings["lang"] = lang.into();
                if name == "empty" {
                    settings["profiles"] = json!([]);
                }
                if name == "group" {
                    settings["profiles"][0]["group"] = "TEST".into();
                    settings["profiles"][0]["subscriptionId"] = "sub".into();
                    let mut second = settings["profiles"][0].clone();
                    second["host"] = "192.0.2.2".into();
                    second["name"] = "NL Amsterdam".into();
                    settings["profiles"].as_array_mut().unwrap().push(second);
                    settings["subscriptions"] =
                        json!([{"id":"sub","name":"TEST","url":"https://example.com/sub"}]);
                }
                let (_runtime, context) =
                    native_test_runtime_with_settings(status, settings.clone());
                context.with(|ctx|{
                    let mut ui=model::Ui::new();ui.load_settings(settings).unwrap();ui.service_snapshot(&json!({"status":status,"desiredRunning":status!="Stopped"}),1000.,100_000_000);
                    ctx.eval::<(),_>("__clock=1500;__nativeTick();__clock=3000;__nativeTick();").unwrap();
                    let navigation=match name {
                        "share"=>{let value=config::share_link(ui.profile().unwrap()).unwrap();let svg=qr::svg(&value).unwrap();ui.share=value.clone();ui.qr=svg.clone();ui.fields.get_mut("share-link").unwrap().value=value.clone();ui.show(Share);ctx.globals().set("__share",value).unwrap();ctx.globals().set("__qr",format!("data:image/svg+xml,{}",config::encode_component(&svg))).unwrap();"document.getElementById('share-link').value=__share;document.getElementById('share-qr').src=__qr;document.getElementById('overlay-share').classList.remove('hidden');"},
                        "running"=>{ui.show(Settings);ui.show(Running);ui.processes=vec!["chrome.exe".into(),"telegram.exe".into()];ui.selected_processes.insert("chrome.exe".into());"document.getElementById('btn-settings').dispatchEvent(new Event('click'));document.getElementById('overlay-running-apps').classList.remove('hidden');const list=document.getElementById('running-apps-list');list.replaceChildren();for(const name of ['chrome.exe','telegram.exe']){const row=document.createElement('label');row.className='running-app-item';const input=document.createElement('input');input.type='checkbox';input.checked=name==='chrome.exe';const text=document.createElement('span');text.textContent=name;row.append(input,text);list.appendChild(row);}"},
                        "speed-results"=>{ui.show(Speed);ui.speedtest.results=Some([123.,45.,31.]);"document.getElementById('overlay-speedtest').classList.remove('hidden');document.getElementById('speedtest-results-area').classList.remove('hidden');for(const [kind,value] of [['down','123'],['up','45'],['ping','31']])document.getElementById(`speedtest-res-${kind}`).textContent=value;"},
                        "confirm"=>{ui.confirm=ui.t("restartConfirm");ui.show(Confirm);ctx.globals().set("__msg",ui.confirm.clone()).unwrap();"document.getElementById('confirm-msg').textContent=__msg;document.getElementById('overlay-confirm').classList.remove('hidden');"},
                        "message"=>{ui.message(ui.t("copySuccess"));ctx.globals().set("__msg",ui.message.clone()).unwrap();"document.getElementById('message-msg').textContent=__msg;document.getElementById('overlay-message').classList.remove('hidden');"},
                        "unsaved"=>{ui.show(Settings);ui.show(Unsaved);"document.getElementById('btn-settings').dispatchEvent(new Event('click'));document.getElementById('overlay-settings-unsaved').classList.remove('hidden');"},
                        "error"=>{ui.status="stopped".into();ui.error="TEST ERROR".into();ui.connected_at=None;"document.getElementById('power-btn').className='power-btn error';document.getElementById('error-msg').textContent='TEST ERROR';document.getElementById('particles').nativeCanvas.ops=[{kind:'ellipse',x:84,y:84,w:172,h:172,color:'#1c1c1e'}];"},
                        "dropdown"=>{ui.show(Settings);ui.tunneling=true;ui.select=Some(("s-apps-mode".into(),[230.,141.,144.,96.]));"document.getElementById('btn-settings').dispatchEvent(new Event('click'));document.getElementById('btn-open-tunneling').dispatchEvent(new Event('click'));__nativeBuildScene();__nativePointer('click',250,120);"},
                        "focused"=>{ui.show(Settings);ui.tunneling=true;ui.focus="s-apps-list".into();ui.fields.get_mut("s-apps-list").unwrap().value="chrome.exe, telegram.exe".into();ui.caret=10;ui.anchor=10;"document.getElementById('btn-settings').dispatchEvent(new Event('click'));document.getElementById('btn-open-tunneling').dispatchEvent(new Event('click'));document.getElementById('s-apps-list').value='chrome.exe, telegram.exe';__nativeBuildScene();__nativePointer('click',120,220);nativeCaret=10;document.getElementById('s-apps-list').dispatchEvent(new Event('input'));"},
                        "group"=>{ui.show(Profiles);"document.getElementById('btn-profiles').dispatchEvent(new Event('click'));"},
                        "unavailable"=>{ui.show(Profiles);ui.probes.insert(config::profile_key(ui.profile().unwrap()),model::Probe::default());"document.getElementById('btn-profiles').dispatchEvent(new Event('click'));document.getElementById('profile-list').children[0].querySelector('.p-item-proto').textContent='vless · 192.0.2.1:2053 · '+(__testSettings.includes('en')?'unavailable':'недоступен');document.getElementById('profile-list').children[0].querySelector('.p-item-proto').classList.add('unavailable');"},
                        "update"|"update-progress"=>{ui.update=Some(json!({"version":"1.0.8","rollback":false}));ui.update_installing=name=="update-progress";ui.update_percent=if ui.update_installing{Some(47)}else{None};let title=if ui.update_installing{ui.t("updateInstalling")}else{format!("{} 1.0.8",ui.t("updateAvailable"))};let detail=if ui.update_installing{"47%".into()}else{ui.t("updateRestartNotice")};ctx.globals().set("__title",title).unwrap();ctx.globals().set("__detail",detail).unwrap();"document.getElementById('update-banner').classList.remove('hidden');document.getElementById('update-banner-title').textContent=__title;document.getElementById('update-banner-detail').textContent=__detail;"},
                        _=>"",
                    };
                    if let Err(error)=ctx.eval::<(),_>(navigation){let caught=ctx.catch();ctx.globals().set("__caught",caught).unwrap();let detail:String=ctx.eval("String(__caught.stack || __caught)").unwrap();panic!("{lang}-{name}: {error}: {detail}");}
                    let reference:String=ctx.eval("__nativeBuildScene()").unwrap();let expected:painter::Scene=serde_json::from_str(&reference).unwrap();
                    if let Some(particle)=expected.ops.iter().find(|op|op.kind=="particles"){ui.animation_start=particle.started;ui.connected_at=particle.connected_at;}
                    let uptime:String=ctx.eval("document.getElementById('uptime').textContent").unwrap();let secs=uptime.split(':').filter_map(|s|s.parse::<u64>().ok()).fold(0,|total,part|total*60+part);ui.connected_unix=100_000_000-secs*1000;
                    ui.speed=ctx.eval("document.getElementById('m-speed').textContent").unwrap();let ping:String=ctx.eval("document.getElementById('m-ping').textContent").unwrap();ui.ping=ping.parse().ok();
                    let actual=view::build(&ui,3000.,100_000_000);let label=format!("{lang}-{name}");
                    painter::Painter::snapshot(&expected,&directory.join(format!("legacy-{label}.png")),3000.).unwrap();painter::Painter::snapshot(&actual,&directory.join(format!("rust-{label}.png")),3000.).unwrap();
                    if fs::read(directory.join(format!("legacy-{label}.png"))).unwrap()!=fs::read(directory.join(format!("rust-{label}.png"))).unwrap(){let differences=expected.ops.iter().zip(&actual.ops).enumerate().filter(|(_, (a,b))|a!=b).map(|(i,(a,b))|format!("{i}: expected {a:?}\nactual {b:?}")).collect::<Vec<_>>();fs::write(directory.join(format!("view-diff-{label}.txt")),format!("Count: {} != {}\n{}",expected.ops.len(),actual.ops.len(),differences.join("\n"))).unwrap();failures.push(label);}
                });
            }
        }
        assert!(failures.is_empty(), "Views differ: {failures:?}");
    }
    #[test]
    fn rust_qr_matches_the_original_modules_and_svg() {
        let (_runtime, context) = native_test_runtime("Connected");
        context.with(|ctx|{
            for value in ["https://example.com/sub#WARPY","vless://00000000-0000-4000-8000-000000000001@192.0.2.1:2053?security=reality&sni=example.com&pbk=test#SNKT"]{
                ctx.globals().set("testQrValue",value).unwrap();let expected:String=ctx.eval("(() => {const qr=qrcode(0,'L');qr.addData(testQrValue,'Byte');qr.make();return qr.createSvgTag({cellSize:5,margin:10});})()").unwrap();assert_eq!(qr::svg(value).unwrap(),expected);
            }
        });
    }
}
