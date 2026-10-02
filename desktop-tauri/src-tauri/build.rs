fn main() {
    if std::env::var_os("CARGO_FEATURE_NATIVE_UI").is_some() {
        let mut config: serde_json::Value = std::env::var("TAURI_CONFIG")
            .ok()
            .and_then(|value| serde_json::from_str(&value).ok())
            .unwrap_or_else(|| serde_json::json!({}));
        config["build"]["frontendDist"] = serde_json::json!("../native-assets");
        config["app"]["windows"] = serde_json::json!([]);
        let config = config.to_string();
        std::env::set_var("TAURI_CONFIG", &config);
        println!("cargo:rustc-env=TAURI_CONFIG={config}");
    }
    let mut windows = tauri_build::WindowsAttributes::new();
    windows = windows.app_manifest(include_str!("app.manifest"));

    tauri_build::try_build(tauri_build::Attributes::new().windows_attributes(windows))
        .expect("failed to run tauri-build");
}
