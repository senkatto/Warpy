#![cfg(windows)]

use crate::vpn_selector::prepare_config;
use serde::Serialize;
use serde_json::Value;
use std::{
    fs,
    os::windows::process::CommandExt,
    path::Path,
    process::{Command, Stdio},
    sync::{Arc, Mutex},
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const CREATE_NO_WINDOW: u32 = 0x08000000;
const MAX_CONCURRENT_PROBES: usize = 4;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ProfileProbeResult {
    index: usize,
    delay_ms: Option<u64>,
}

pub(crate) fn run(core: &Path, config: &str) -> Result<Vec<ProfileProbeResult>, String> {
    let mut value: Value = serde_json::from_str(config)
        .map_err(|error| format!("Некорректная конфигурация: {error}"))?;
    if let Some(root) = value.as_object_mut() {
        root.remove("inbounds");
    }
    let (prepared, control) = prepare_config(&value.to_string())?;
    let control = control.ok_or_else(|| "В конфигурации нет профилей".to_string())?;
    let tags = control.outbounds();
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let config_path = std::env::temp_dir().join(format!("warpy-profile-probe-{stamp}.json"));
    fs::write(&config_path, prepared)
        .map_err(|error| format!("Не удалось подготовить проверку профилей: {error}"))?;

    let mut child = Command::new(core)
        .arg("run")
        .arg("-c")
        .arg(&config_path)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(|error| format!("Не удалось запустить проверку профилей: {error}"))?;

    if let Err(error) = control.wait_until_ready(Duration::from_secs(5)) {
        let _ = child.kill();
        let _ = child.wait();
        let _ = fs::remove_file(config_path);
        return Err(error);
    }
    let mut pending = tags
        .into_iter()
            .filter_map(|tag| {
                let index = tag
                    .strip_prefix("profile-")?
                    .parse::<usize>()
                    .ok()?
                    .checked_sub(1)?;
                Some((index, tag))
            })
            .collect::<Vec<_>>();
    pending.sort_by_key(|(index, _)| std::cmp::Reverse(*index));
    let queue = Arc::new(Mutex::new(pending));
    let results = Arc::new(Mutex::new(Vec::new()));
    thread::scope(|scope| {
        for _ in 0..MAX_CONCURRENT_PROBES {
            let queue = Arc::clone(&queue);
            let results = Arc::clone(&results);
            let control = control.clone();
            scope.spawn(move || loop {
                let Some((index, tag)) = queue.lock().ok().and_then(|mut values| values.pop())
                else {
                    break;
                };
                let delay_ms = control.probe_outbound(&tag).ok();
                if let Ok(mut values) = results.lock() {
                    values.push(ProfileProbeResult { index, delay_ms });
                }
            });
        }
    });
    let _ = child.kill();
    let _ = child.wait();
    let _ = fs::remove_file(config_path);

    let mut values = Arc::try_unwrap(results)
        .map_err(|_| "Не удалось завершить проверку профилей".to_string())?
        .into_inner()
        .map_err(|_| "Не удалось получить результаты проверки".to_string())?;
    values.sort_by_key(|item| item.index);
    Ok(values)
}
