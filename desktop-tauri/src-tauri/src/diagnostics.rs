use regex::Regex;
use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::{Read, Seek, SeekFrom, Write},
    path::Path,
    sync::OnceLock,
};
use zip::{write::SimpleFileOptions, CompressionMethod, ZipWriter};

const MAX_LOG_TAIL_BYTES: u64 = 64 * 1024;
const MAX_LOG_LINES: usize = 200;
const MAX_LOG_LINE_CHARS: usize = 512;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ServiceDiagnostics {
    pub(crate) status: String,
    pub(crate) uptime_seconds: Option<u64>,
    pub(crate) kill_switch: String,
    pub(crate) network_type: String,
    pub(crate) health_profile_count: usize,
    pub(crate) healthy_profile_count: usize,
    pub(crate) auto_enabled: bool,
    pub(crate) service_log: Vec<String>,
    pub(crate) core_errors: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DiagnosticsSettingsSummary {
    pub(crate) schema_version: u8,
    pub(crate) profile_count: usize,
    pub(crate) subscription_count: usize,
    pub(crate) adblock: bool,
    pub(crate) quic: bool,
    pub(crate) lan: bool,
    pub(crate) kill_switch: bool,
    pub(crate) resume_on_boot: bool,
    pub(crate) network_auto_protect: bool,
    pub(crate) warpy_auto: bool,
    pub(crate) mtu: u16,
    pub(crate) apps_mode: String,
    pub(crate) app_rule_count: usize,
    pub(crate) sites_mode: String,
    pub(crate) site_rule_count: usize,
    pub(crate) language: String,
}

impl DiagnosticsSettingsSummary {
    pub(crate) fn normalize(mut self) -> Self {
        self.schema_version = 1;
        self.profile_count = self.profile_count.min(10_000);
        self.subscription_count = self.subscription_count.min(1_000);
        self.app_rule_count = self.app_rule_count.min(10_000);
        self.site_rule_count = self.site_rule_count.min(10_000);
        self.mtu = match self.mtu {
            0 => 0,
            value => value.clamp(576, 1_500),
        };
        self.apps_mode = allowed_choice(&self.apps_mode, &["off", "only", "bypass"], "off");
        self.sites_mode = allowed_choice(&self.sites_mode, &["off", "only", "bypass"], "off");
        self.language = allowed_choice(&self.language, &["ru", "en"], "en");
        self
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DiagnosticsManifest {
    pub(crate) bundle_version: u8,
    pub(crate) created_at_unix_ms: u64,
    pub(crate) app_version: String,
    pub(crate) platform: &'static str,
    pub(crate) architecture: &'static str,
    pub(crate) settings: DiagnosticsSettingsSummary,
    pub(crate) service: ServiceSummary,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ServiceSummary {
    status: String,
    uptime_seconds: Option<u64>,
    kill_switch: String,
    network_type: String,
    health_profile_count: usize,
    healthy_profile_count: usize,
    auto_enabled: bool,
}

impl ServiceDiagnostics {
    pub(crate) fn summary(&self) -> ServiceSummary {
        ServiceSummary {
            status: safe_status(&self.status),
            uptime_seconds: self.uptime_seconds,
            kill_switch: safe_kill_switch(&self.kill_switch),
            network_type: safe_network_type(&self.network_type),
            health_profile_count: self.health_profile_count.min(10_000),
            healthy_profile_count: self
                .healthy_profile_count
                .min(self.health_profile_count.min(10_000)),
            auto_enabled: self.auto_enabled,
        }
    }
}

pub(crate) fn collect_sanitized_log(path: &Path, severity_only: bool) -> Vec<String> {
    let Ok(mut file) = fs::File::open(path) else {
        return Vec::new();
    };
    let length = file.metadata().map(|metadata| metadata.len()).unwrap_or(0);
    let offset = length.saturating_sub(MAX_LOG_TAIL_BYTES);
    if file.seek(SeekFrom::Start(offset)).is_err() {
        return Vec::new();
    }

    let mut bytes = Vec::with_capacity((length - offset).min(MAX_LOG_TAIL_BYTES) as usize);
    if file
        .take(MAX_LOG_TAIL_BYTES)
        .read_to_end(&mut bytes)
        .is_err()
    {
        return Vec::new();
    }
    let contents = String::from_utf8_lossy(&bytes);
    let mut lines = contents.lines();
    if offset > 0 {
        let _ = lines.next();
    }

    let mut sanitized: Vec<String> = lines
        .filter(|line| !severity_only || is_significant_log_line(line))
        .map(sanitize_log_line)
        .filter(|line| !line.is_empty())
        .collect();
    if sanitized.len() > MAX_LOG_LINES {
        sanitized.drain(..sanitized.len() - MAX_LOG_LINES);
    }
    sanitized
}

pub(crate) fn write_bundle(
    path: &Path,
    manifest: &DiagnosticsManifest,
    service: &ServiceDiagnostics,
    app_log: &[String],
) -> Result<(), String> {
    let file = fs::File::create(path).map_err(|error| error.to_string())?;
    write_bundle_to(file, manifest, service, app_log)
}

fn write_bundle_to<W: Write + Seek>(
    writer: W,
    manifest: &DiagnosticsManifest,
    service: &ServiceDiagnostics,
    app_log: &[String],
) -> Result<(), String> {
    let mut archive = ZipWriter::new(writer);
    let options = SimpleFileOptions::default()
        .compression_method(CompressionMethod::Stored)
        .unix_permissions(0o600);

    write_entry(
        &mut archive,
        options,
        "summary.json",
        &serde_json::to_string_pretty(manifest).map_err(|error| error.to_string())?,
    )?;
    write_entry(
        &mut archive,
        options,
        "service.log",
        &join_lines(&service.service_log),
    )?;
    write_entry(
        &mut archive,
        options,
        "core-errors.log",
        &join_lines(&service.core_errors),
    )?;
    write_entry(&mut archive, options, "app.log", &join_lines(app_log))?;
    write_entry(
        &mut archive,
        options,
        "README.txt",
        "Warpy diagnostics bundle\n\nThe bundle contains aggregate state and anonymized log excerpts. VPN profiles, subscription URLs, credentials, server addresses, visited domains, application names and user file paths are not included.\n",
    )?;
    archive.finish().map_err(|error| error.to_string())?;
    Ok(())
}

fn write_entry<W: Write + Seek>(
    archive: &mut ZipWriter<W>,
    options: SimpleFileOptions,
    name: &str,
    contents: &str,
) -> Result<(), String> {
    archive
        .start_file(name, options)
        .map_err(|error| error.to_string())?;
    archive
        .write_all(contents.as_bytes())
        .map_err(|error| error.to_string())
}

fn join_lines(lines: &[String]) -> String {
    if lines.is_empty() {
        return String::new();
    }
    let mut output = lines.join("\n");
    output.push('\n');
    output
}

fn sanitize_log_line(line: &str) -> String {
    let mut output = strip_control_sequences(line);
    for (regex, replacement) in redactors() {
        output = regex.replace_all(&output, *replacement).into_owned();
    }
    output = normalize_known_messages(&output);
    output.trim().chars().take(MAX_LOG_LINE_CHARS).collect()
}

fn normalize_known_messages(value: &str) -> String {
    let lower = value.to_ascii_lowercase();
    if let Some(index) = lower.find("selector switched to ") {
        return format!("{}selector switched", value[..index].trim_end());
    }
    value.to_string()
}

fn is_significant_log_line(line: &str) -> bool {
    let upper = line.to_ascii_uppercase();
    upper.contains("WARN") || upper.contains("ERROR") || upper.contains("FATAL")
}

fn strip_control_sequences(value: &str) -> String {
    let mut output = String::with_capacity(value.len());
    let mut characters = value.chars();
    while let Some(character) = characters.next() {
        if character == '\u{1b}' {
            if matches!(characters.next(), Some('[')) {
                for control in characters.by_ref() {
                    if ('@'..='~').contains(&control) {
                        break;
                    }
                }
            }
            continue;
        }
        if !character.is_control() || character == '\t' {
            output.push(character);
        }
    }
    output
}

fn redactors() -> &'static Vec<(Regex, &'static str)> {
    static REDACTORS: OnceLock<Vec<(Regex, &'static str)>> = OnceLock::new();
    REDACTORS.get_or_init(|| {
        [
            (r#"(?i)\b(?:vless|trojan|hysteria2?|hy2)://[^\s\"'<>]+"#, "[vpn-uri]"),
            (r#"(?i)\bhttps?://[^\s\"'<>]+"#, "[url]"),
            (r#"(?i)\b[a-z]:\\[^\r\n|"]+"#, "[path]"),
            (r"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b", "[uuid]"),
            (r"(?i)(?:\[[0-9a-f:]+\](?::\d{1,5})?|\b(?:[0-9a-f]{0,4}:){3,}[0-9a-f:]{0,39}\b|\b[0-9a-f]{1,4}::[0-9a-f:]*\b|::1\b)", "[ip]"),
            (r"\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?::\d{1,5})?\b", "[ip]"),
            (r"(?i)\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,63}\b", "[email]"),
            (r"(?i)\b(?:[a-z0-9-]+\.)+[a-z]{2,63}\b", "[domain]"),
            (r"(?i)\b[a-z0-9_.-]+\.exe\b", "[process]"),
            (r"\b[A-Za-z0-9_-]{24,}\b", "[secret]"),
        ]
        .into_iter()
        .map(|(pattern, replacement)| {
            (
                Regex::new(pattern).expect("diagnostics redaction pattern"),
                replacement,
            )
        })
        .collect()
    })
}

fn safe_status(value: &str) -> String {
    allowed_choice(
        value,
        &[
            "Stopped",
            "Starting",
            "Validating",
            "Connected",
            "Recovering",
            "Stopping",
            "Error",
        ],
        "Unknown",
    )
}

fn safe_kill_switch(value: &str) -> String {
    let lower = value.to_ascii_lowercase();
    if lower == "armed" {
        "Armed".to_string()
    } else if lower == "off" {
        "Off".to_string()
    } else if lower.starts_with("suppressed:") {
        "Suppressed".to_string()
    } else if lower.starts_with("error:") {
        "Error".to_string()
    } else {
        "Unknown".to_string()
    }
}

fn safe_network_type(value: &str) -> String {
    let lower = value.to_ascii_lowercase();
    allowed_choice(
        &lower,
        &["wifi", "ethernet", "cellular", "unknown"],
        "unknown",
    )
}

fn allowed_choice(value: &str, allowed: &[&str], fallback: &str) -> String {
    allowed
        .iter()
        .find(|candidate| **candidate == value)
        .copied()
        .unwrap_or(fallback)
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        io::{Cursor, Read},
        time::{SystemTime, UNIX_EPOCH},
    };

    fn sample_settings() -> DiagnosticsSettingsSummary {
        DiagnosticsSettingsSummary {
            schema_version: 99,
            profile_count: 2,
            subscription_count: 1,
            adblock: true,
            quic: false,
            lan: true,
            kill_switch: true,
            resume_on_boot: true,
            network_auto_protect: true,
            warpy_auto: true,
            mtu: 1_400,
            apps_mode: "bypass".to_string(),
            app_rule_count: 3,
            sites_mode: "off".to_string(),
            site_rule_count: 0,
            language: "ru".to_string(),
        }
        .normalize()
    }

    fn sample_service() -> ServiceDiagnostics {
        ServiceDiagnostics {
            status: "Connected".to_string(),
            uptime_seconds: Some(42),
            kill_switch: "Armed".to_string(),
            network_type: "wifi".to_string(),
            health_profile_count: 2,
            healthy_profile_count: 1,
            auto_enabled: true,
            service_log: vec!["service started".to_string()],
            core_errors: vec!["ERROR [domain]".to_string()],
        }
    }

    #[test]
    fn sanitizer_removes_connection_and_user_identifiers() {
        let input = r#"ERROR vless://00000000-0000-4000-8000-000000000002@203.0.113.40:443?pbk=secret https://private.example.com/s/token C:\Work\alice\secret.txt telegram.exe user@example.com [2001:db8::1]:443 verylongsecretvalue_1234567890"#;
        let sanitized = sanitize_log_line(input);

        for secret in [
            "00000000-0000-4000-8000-000000000002",
            "203.0.113.40",
            "private.example.com",
            "alice",
            "telegram.exe",
            "user@example.com",
            "2001:db8::1",
            "verylongsecretvalue_1234567890",
        ] {
            assert!(!sanitized.contains(secret), "leaked: {secret}");
        }
        assert!(sanitized.contains("[vpn-uri]"));
        assert!(sanitized.contains("[url]"));
        assert!(sanitized.contains("[path]"));
    }

    #[test]
    fn core_log_export_keeps_only_significant_lines() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system time")
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "warpy-diagnostics-{}-{unique}.log",
            std::process::id()
        ));
        fs::write(
            &path,
            "INFO connected to 203.0.113.40\nWARN retry private.example.com\nERROR failed chrome.exe\n",
        )
        .expect("write test log");

        let lines = collect_sanitized_log(&path, true);
        let _ = fs::remove_file(path);
        assert_eq!(lines.len(), 2);
        assert!(lines[0].contains("WARN"));
        assert!(lines[1].contains("ERROR"));
        assert!(!lines.join("\n").contains("203.0.113.40"));
        assert!(!lines.join("\n").contains("private.example.com"));
        assert!(!lines.join("\n").contains("chrome.exe"));
    }

    #[test]
    fn archive_contains_only_allowlisted_entries() {
        let service = sample_service();
        let manifest = DiagnosticsManifest {
            bundle_version: 1,
            created_at_unix_ms: 1,
            app_version: "0.1.0".to_string(),
            platform: "windows",
            architecture: "x86_64",
            settings: sample_settings(),
            service: service.summary(),
        };
        let mut writer = Cursor::new(Vec::new());
        write_bundle_to(&mut writer, &manifest, &service, &["app ready".to_string()])
            .expect("write archive");
        writer.set_position(0);
        let mut archive = zip::ZipArchive::new(writer).expect("open archive");
        let mut names = Vec::new();
        for index in 0..archive.len() {
            names.push(archive.by_index(index).expect("entry").name().to_string());
        }
        assert_eq!(
            names,
            [
                "summary.json",
                "service.log",
                "core-errors.log",
                "app.log",
                "README.txt",
            ]
        );

        let mut summary = String::new();
        archive
            .by_name("summary.json")
            .expect("summary")
            .read_to_string(&mut summary)
            .expect("read summary");
        assert!(!summary.contains("profiles"));
        assert!(!summary.contains("subscriptionUrl"));
        assert!(!summary.contains(&["settings", ".dat"].concat()));
    }
}
