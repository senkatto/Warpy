use reqwest::header::{ACCEPT, USER_AGENT};
use semver::Version;
use serde::{Deserialize, Serialize};
use std::{fs, path::Path, time::Duration};
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_updater::{Update, Updater, UpdaterExt};
use url::Url;

const RELEASES_API: &str = "https://api.github.com/repos/senkatto/Warpy/releases?per_page=30";
const RELEASE_DOWNLOAD_PREFIX: &str = "/senkatto/Warpy/releases/download/";
const UPDATE_PROGRESS_EVENT: &str = "warpy://update-progress";
const LAUNCH_HEALTH_MARKER_PREFIX: &str = "launch-health-";
const ROLLBACK_MARKER: &str = "[warpy-rollback:stable]";
const MAX_RELEASE_FEED_BYTES: usize = 2 * 1024 * 1024;
const MAX_EXPECTED_VERSION_CHARS: usize = 64;

fn ensure_crypto_provider() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

#[derive(Clone, Debug, Deserialize)]
struct GitHubAsset {
    name: String,
    browser_download_url: Url,
}

#[derive(Clone, Debug, Deserialize)]
struct GitHubRelease {
    tag_name: String,
    draft: bool,
    prerelease: bool,
    body: Option<String>,
    assets: Vec<GitHubAsset>,
}

#[derive(Clone, Debug)]
struct ReleaseTarget {
    version: Version,
    endpoint: Url,
    rollback: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    version: String,
    notes: String,
    rollback: bool,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdateProgress {
    downloaded: u64,
    total: Option<u64>,
    percent: Option<u8>,
}

fn release_version(tag: &str) -> Option<Version> {
    Version::parse(tag.strip_prefix('v')?).ok()
}

fn is_trusted_feed_url(url: &Url) -> bool {
    url.scheme() == "https"
        && url.host_str() == Some("github.com")
        && url.path().starts_with(RELEASE_DOWNLOAD_PREFIX)
}

fn select_release(releases: &[GitHubRelease]) -> Result<Option<ReleaseTarget>, String> {
    let mut rollback_targets = Vec::new();
    let mut normal_targets = Vec::new();

    for release in releases {
        if release.draft || release.prerelease {
            continue;
        }
        let Some(version) = release_version(&release.tag_name) else {
            continue;
        };
        let Some(asset) = release
            .assets
            .iter()
            .find(|asset| asset.name == "latest.json")
        else {
            continue;
        };
        if !is_trusted_feed_url(&asset.browser_download_url) {
            return Err("release feed URL is not trusted".to_string());
        }

        let target = ReleaseTarget {
            version,
            endpoint: asset.browser_download_url.clone(),
            rollback: release
                .body
                .as_deref()
                .is_some_and(|body| body.contains(ROLLBACK_MARKER)),
        };
        if target.rollback {
            rollback_targets.push(target);
        } else {
            normal_targets.push(target);
        }
    }

    if rollback_targets.len() > 1 {
        return Err("multiple rollback releases are active".to_string());
    }
    if let Some(target) = rollback_targets.pop() {
        return Ok(Some(target));
    }

    normal_targets.sort_by(|left, right| right.version.cmp(&left.version));
    Ok(normal_targets.into_iter().next())
}

async fn discover_release() -> Result<Option<ReleaseTarget>, String> {
    ensure_crypto_provider();
    let mut response = reqwest::Client::builder()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|error| error.to_string())?
        .get(RELEASES_API)
        .header(USER_AGENT, "Warpy updater")
        .header(ACCEPT, "application/vnd.github+json")
        .send()
        .await
        .map_err(|error| error.to_string())?;

    if !response.status().is_success() {
        return Err(format!("release discovery returned {}", response.status()));
    }
    if response
        .content_length()
        .is_some_and(|length| length > MAX_RELEASE_FEED_BYTES as u64)
    {
        return Err("release feed is too large".to_string());
    }
    let mut body = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|error| error.to_string())? {
        if body.len().saturating_add(chunk.len()) > MAX_RELEASE_FEED_BYTES {
            return Err("release feed is too large".to_string());
        }
        body.extend_from_slice(&chunk);
    }
    let releases =
        serde_json::from_slice::<Vec<GitHubRelease>>(&body).map_err(|error| error.to_string())?;
    select_release(&releases)
}

fn build_updater(
    app: &AppHandle,
    target: &ReleaseTarget,
    timeout: Duration,
) -> Result<Updater, String> {
    let expected = target.version.clone();
    let allow_rollback = target.rollback;
    app.updater_builder()
        .endpoints(vec![target.endpoint.clone()])
        .map_err(|error| error.to_string())?
        .version_comparator(move |current, release| {
            release.version == expected
                && (release.version > current || (allow_rollback && release.version < current))
        })
        .timeout(timeout)
        .build()
        .map_err(|error| error.to_string())
}

fn validate_update(update: &Update, target: &ReleaseTarget) -> Result<(), String> {
    let current = Version::parse(&update.current_version).map_err(|error| error.to_string())?;
    let remote = Version::parse(&update.version).map_err(|error| error.to_string())?;
    if remote != target.version {
        return Err("release version changed during update check".to_string());
    }
    if remote == current {
        return Err("release is already installed".to_string());
    }
    if remote < current && !target.rollback {
        return Err("unsigned rollback policy rejected the release".to_string());
    }
    Ok(())
}

fn update_notes(body: Option<&str>) -> String {
    body.unwrap_or_default().chars().take(2_000).collect()
}

fn launch_health_marker_path(app_dir: &Path, version: &Version) -> std::path::PathBuf {
    app_dir.join(format!("{LAUNCH_HEALTH_MARKER_PREFIX}{version}.ok"))
}

fn write_launch_health_marker(app_dir: &Path, version: &Version) -> Result<(), String> {
    fs::create_dir_all(app_dir).map_err(|error| error.to_string())?;
    let marker = launch_health_marker_path(app_dir, version);
    let temporary = marker.with_extension("tmp");
    fs::write(&temporary, version.to_string()).map_err(|error| error.to_string())?;
    let _ = fs::remove_file(&marker);
    fs::rename(temporary, marker).map_err(|error| error.to_string())
}

pub fn confirm_launch_health(app: &AppHandle) -> Result<(), String> {
    let app_dir = app
        .path()
        .app_data_dir()
        .map_err(|error| error.to_string())?;
    write_launch_health_marker(&app_dir, &app.package_info().version)
}

async fn checked_update(
    app: &AppHandle,
    timeout: Duration,
) -> Result<Option<(Update, ReleaseTarget)>, String> {
    let Some(target) = discover_release().await? else {
        return Ok(None);
    };
    let current = app.package_info().version.clone();
    if target.version == current || (target.version < current && !target.rollback) {
        return Ok(None);
    }
    let update = build_updater(app, &target, timeout)?
        .check()
        .await
        .map_err(|error| error.to_string())?;
    if let Some(ref update) = update {
        validate_update(update, &target)?;
    }
    Ok(update.map(|update| (update, target)))
}

#[tauri::command]
pub async fn check_for_update(app: AppHandle) -> Result<Option<UpdateInfo>, String> {
    let Some((update, target)) = checked_update(&app, Duration::from_secs(20)).await? else {
        return Ok(None);
    };
    Ok(Some(UpdateInfo {
        version: update.version,
        notes: update_notes(update.body.as_deref()),
        rollback: target.rollback,
    }))
}

#[tauri::command]
pub async fn install_update(app: AppHandle, expected_version: String) -> Result<(), String> {
    if expected_version.chars().count() > MAX_EXPECTED_VERSION_CHARS {
        return Err("invalid expected version".to_string());
    }
    let expected_version = Version::parse(&expected_version).map_err(|error| error.to_string())?;
    let Some((update, target)) = checked_update(&app, Duration::from_secs(180)).await? else {
        return Err("update is no longer available".to_string());
    };
    if target.version != expected_version || update.version != expected_version.to_string() {
        return Err("release changed; check for updates again".to_string());
    }

    let progress_app = app.clone();
    let finished_app = app.clone();
    let mut downloaded = 0_u64;
    update
        .download_and_install(
            move |chunk, total| {
                downloaded = downloaded.saturating_add(chunk as u64);
                let percent = total
                    .filter(|total| *total > 0)
                    .map(|total| ((downloaded.saturating_mul(100) / total).min(99)) as u8);
                let _ = progress_app.emit(
                    UPDATE_PROGRESS_EVENT,
                    UpdateProgress {
                        downloaded,
                        total,
                        percent,
                    },
                );
            },
            move || {
                let _ = finished_app.emit(
                    UPDATE_PROGRESS_EVENT,
                    UpdateProgress {
                        downloaded: 0,
                        total: None,
                        percent: Some(100),
                    },
                );
            },
        )
        .await
        .map_err(|error| error.to_string())?;

    app.restart();
}

#[cfg(test)]
mod tests {
    use super::{
        ensure_crypto_provider, launch_health_marker_path, select_release,
        write_launch_health_marker, GitHubRelease,
    };
    use semver::Version;

    #[test]
    fn updater_installs_a_crypto_provider() {
        ensure_crypto_provider();
        assert!(rustls::crypto::CryptoProvider::get_default().is_some());
    }

    fn releases(json: &str) -> Vec<GitHubRelease> {
        serde_json::from_str(json).expect("valid release fixture")
    }

    #[test]
    fn stable_release_ignores_prereleases() {
        let fixture = releases(
            r#"[
              {"tag_name":"v1.2.0","draft":false,"prerelease":false,"body":"","assets":[{"name":"latest.json","browser_download_url":"https://github.com/senkatto/Warpy/releases/download/v1.2.0/latest.json"}]},
              {"tag_name":"v1.3.0-beta.2","draft":false,"prerelease":true,"body":"","assets":[{"name":"latest.json","browser_download_url":"https://github.com/senkatto/Warpy/releases/download/v1.3.0-beta.2/latest.json"}]}
            ]"#,
        );
        assert_eq!(
            select_release(&fixture)
                .unwrap()
                .unwrap()
                .version
                .to_string(),
            "1.2.0"
        );
    }

    #[test]
    fn explicit_rollback_takes_priority_and_untrusted_urls_fail_closed() {
        let rollback = releases(
            r#"[
              {"tag_name":"v1.1.0","draft":false,"prerelease":false,"body":"[warpy-rollback:stable]","assets":[{"name":"latest.json","browser_download_url":"https://github.com/senkatto/Warpy/releases/download/v1.1.0/latest.json"}]},
              {"tag_name":"v1.2.0","draft":false,"prerelease":false,"body":"","assets":[{"name":"latest.json","browser_download_url":"https://github.com/senkatto/Warpy/releases/download/v1.2.0/latest.json"}]}
            ]"#,
        );
        let target = select_release(&rollback).unwrap().unwrap();
        assert!(target.rollback);
        assert_eq!(target.version.to_string(), "1.1.0");

        let untrusted = releases(
            r#"[{"tag_name":"v1.2.0","draft":false,"prerelease":false,"body":"","assets":[{"name":"latest.json","browser_download_url":"https://example.com/latest.json"}]}]"#,
        );
        assert!(select_release(&untrusted).is_err());
    }

    #[test]
    fn ambiguous_rollbacks_are_rejected() {
        let fixture = releases(
            r#"[
              {"tag_name":"v1.0.0","draft":false,"prerelease":false,"body":"[warpy-rollback:stable]","assets":[{"name":"latest.json","browser_download_url":"https://github.com/senkatto/Warpy/releases/download/v1.0.0/latest.json"}]},
              {"tag_name":"v1.1.0","draft":false,"prerelease":false,"body":"[warpy-rollback:stable]","assets":[{"name":"latest.json","browser_download_url":"https://github.com/senkatto/Warpy/releases/download/v1.1.0/latest.json"}]}
            ]"#,
        );
        assert!(select_release(&fixture).is_err());
    }

    #[test]
    fn launch_health_marker_is_versioned_and_written_atomically() {
        let version = Version::parse("1.2.3-beta.4").unwrap();
        let directory = std::env::temp_dir().join(format!(
            "warpy-launch-health-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));

        write_launch_health_marker(&directory, &version).unwrap();

        let marker = launch_health_marker_path(&directory, &version);
        assert_eq!(marker.file_name().unwrap(), "launch-health-1.2.3-beta.4.ok");
        assert_eq!(
            std::fs::read_to_string(marker).unwrap(),
            version.to_string()
        );
        assert!(!directory.join("launch-health-1.2.3-beta.4.tmp").exists());
        std::fs::remove_dir_all(directory).unwrap();
    }
}
