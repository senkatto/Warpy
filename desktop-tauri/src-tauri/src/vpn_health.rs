#![cfg(windows)]

use serde::{Deserialize, Serialize};
use std::{collections::VecDeque, ffi::c_void, fs, path::Path, ptr, slice};
use windows_sys::Win32::NetworkManagement::{
    IpHelper::{
        FreeMibTable, GetIfTable2, IF_TYPE_ETHERNET_CSMACD, IF_TYPE_IEEE80211, IF_TYPE_WWANPP,
        IF_TYPE_WWANPP2, MIB_IF_ROW2, MIB_IF_TABLE2,
    },
    Ndis::IfOperStatusUp,
};

const STORE_VERSION: u8 = 1;
const MAX_RECORDS: usize = 512;
const MAX_SAMPLES: usize = 12;
const MAX_STORE_BYTES: u64 = 512 * 1024;
const SUCCESS_PROBE_INTERVAL_MS: u64 = 15_000;
const FAILURE_PROBE_INTERVAL_MS: u64 = 8_000;
const CANDIDATE_PROBE_INTERVAL_MS: u64 = 10_000;
const CANDIDATE_WARMUP_REFRESH_MS: u64 = 30_000;
const CANDIDATE_SUCCESS_REFRESH_MS: u64 = 10 * 60_000;
const CANDIDATE_FAILURE_REFRESH_MS: u64 = 60_000;
const HEALTH_MAX_AGE_MS: u64 = 15 * 60_000;
const PERSIST_INTERVAL_MS: u64 = 60_000;
const MIN_SUCCESSFUL_SAMPLES: usize = 2;
const MAX_ELIGIBLE_LOSS_PERCENT: u8 = 25;
const FASTER_CONFIRMATIONS: u8 = 2;
const FASTER_MIN_ACTIVE_AGE_MS: u64 = 2 * 60_000;
const FASTER_MIN_GAIN_MS: u64 = 30;
const FASTER_MIN_GAIN_PERCENT: u64 = 20;
const FAILURE_CONFIRMATIONS: usize = 2;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct VpnHealthSnapshot {
    pub(crate) network_type: String,
    pub(crate) active_outbound: Option<String>,
    pub(crate) generated_at_ms: u64,
    pub(crate) profiles: Vec<VpnProfileHealth>,
    pub(crate) recommendation: Option<VpnHealthRecommendation>,
    pub(crate) auto_enabled: bool,
    pub(crate) preferred_outbound: Option<String>,
    pub(crate) last_auto_switch: Option<VpnAutoSwitchEvent>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct VpnAutoSwitchEvent {
    pub(crate) from_outbound: String,
    pub(crate) to_outbound: String,
    pub(crate) reason: String,
    pub(crate) outcome: String,
    pub(crate) observed_at_ms: u64,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct VpnHealthRecommendation {
    pub(crate) outbound: String,
    pub(crate) reason: String,
    pub(crate) active_score: Option<u64>,
    pub(crate) candidate_score: u64,
    pub(crate) observed_at_ms: u64,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct VpnProfileHealth {
    pub(crate) outbound: String,
    pub(crate) profile_id: String,
    pub(crate) sample_count: usize,
    pub(crate) handshake_ok: Option<bool>,
    pub(crate) latency_ms: Option<u64>,
    pub(crate) jitter_ms: Option<u64>,
    pub(crate) loss_percent: Option<u8>,
    pub(crate) last_error: Option<String>,
    pub(crate) last_checked_at_ms: Option<u64>,
    pub(crate) successful_samples: usize,
    pub(crate) consecutive_successes: usize,
    pub(crate) score: Option<u64>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct HealthProbeTarget {
    pub(crate) outbound: String,
    pub(crate) profile_id: String,
    pub(crate) active: bool,
}

#[derive(Debug, Serialize, Deserialize)]
struct HealthFile {
    version: u8,
    records: Vec<HealthRecord>,
}

impl Default for HealthFile {
    fn default() -> Self {
        Self {
            version: STORE_VERSION,
            records: Vec::new(),
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct HealthRecord {
    profile_id: String,
    network_type: String,
    samples: VecDeque<HealthSample>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct HealthSample {
    checked_at_ms: u64,
    latency_ms: Option<u64>,
    error: Option<String>,
}

#[derive(Debug, Default)]
pub(crate) struct VpnHealthStore {
    file: HealthFile,
    dirty: bool,
    last_persisted_at_ms: u64,
    candidate_cursor: usize,
    last_candidate_probe_at_ms: Option<u64>,
    advisor: AutoAdvisor,
}

#[derive(Debug, Default)]
struct AutoAdvisor {
    network_type: String,
    active_outbound: String,
    active_since_ms: u64,
    candidate_outbound: Option<String>,
    candidate_checked_at_ms: Option<u64>,
    confirmations: u8,
    recommendation: Option<VpnHealthRecommendation>,
}

impl VpnHealthStore {
    pub(crate) fn load(path: &Path, now_ms: u64) -> Self {
        let file = fs::metadata(path)
            .ok()
            .filter(|metadata| metadata.len() <= MAX_STORE_BYTES)
            .and_then(|_| fs::read(path).ok())
            .and_then(|contents| serde_json::from_slice::<HealthFile>(&contents).ok())
            .filter(|file| file.version == STORE_VERSION)
            .map(normalize_file)
            .unwrap_or_default();
        Self {
            file,
            dirty: false,
            last_persisted_at_ms: now_ms,
            candidate_cursor: 0,
            last_candidate_probe_at_ms: None,
            advisor: AutoAdvisor::default(),
        }
    }

    pub(crate) fn should_probe(&self, profile_id: &str, network_type: &str, now_ms: u64) -> bool {
        let Some(sample) = self
            .record(profile_id, network_type)
            .and_then(|record| record.samples.back())
        else {
            return true;
        };
        let interval = if sample.latency_ms.is_some() {
            SUCCESS_PROBE_INTERVAL_MS
        } else {
            FAILURE_PROBE_INTERVAL_MS
        };
        now_ms.saturating_sub(sample.checked_at_ms) >= interval
    }

    pub(crate) fn next_probe_target(
        &mut self,
        active_outbound: &str,
        targets: &[(String, String)],
        network_type: &str,
        now_ms: u64,
    ) -> Option<HealthProbeTarget> {
        if let Some((outbound, profile_id)) = targets
            .iter()
            .find(|(outbound, _)| outbound == active_outbound)
        {
            if self.should_probe(profile_id, network_type, now_ms) {
                return Some(HealthProbeTarget {
                    outbound: outbound.clone(),
                    profile_id: profile_id.clone(),
                    active: true,
                });
            }
        }
        if targets.len() < 2
            || self.last_candidate_probe_at_ms.is_some_and(|last_probe| {
                now_ms.saturating_sub(last_probe) < CANDIDATE_PROBE_INTERVAL_MS
            })
        {
            return None;
        }

        for offset in 0..targets.len() {
            let index = (self.candidate_cursor + offset) % targets.len();
            let (outbound, profile_id) = &targets[index];
            if outbound == active_outbound
                || !self.should_probe_candidate(profile_id, network_type, now_ms)
            {
                continue;
            }
            self.candidate_cursor = (index + 1) % targets.len();
            self.last_candidate_probe_at_ms = Some(now_ms);
            return Some(HealthProbeTarget {
                outbound: outbound.clone(),
                profile_id: profile_id.clone(),
                active: false,
            });
        }
        None
    }

    pub(crate) fn record_probe(
        &mut self,
        profile_id: &str,
        network_type: &str,
        checked_at_ms: u64,
        result: Result<u64, String>,
    ) -> bool {
        let succeeded = matches!(result, Ok(latency_ms) if latency_ms > 0);
        let sample = match result {
            Ok(latency_ms) if latency_ms > 0 => HealthSample {
                checked_at_ms,
                latency_ms: Some(latency_ms),
                error: None,
            },
            Ok(_) => HealthSample {
                checked_at_ms,
                latency_ms: None,
                error: Some("empty_response".to_string()),
            },
            Err(error) => HealthSample {
                checked_at_ms,
                latency_ms: None,
                error: Some(probe_error_code(&error)),
            },
        };

        let record = if let Some(index) = self.file.records.iter().position(|record| {
            record.profile_id == profile_id && record.network_type == network_type
        }) {
            &mut self.file.records[index]
        } else {
            self.file.records.push(HealthRecord {
                profile_id: profile_id.to_string(),
                network_type: network_type.to_string(),
                samples: VecDeque::new(),
            });
            self.file.records.last_mut().expect("record was appended")
        };
        let state_changed = record
            .samples
            .back()
            .is_none_or(|previous| previous.latency_ms.is_some() != succeeded);
        record.samples.push_back(sample);
        while record.samples.len() > MAX_SAMPLES {
            record.samples.pop_front();
        }
        self.trim_records();
        self.dirty = true;
        state_changed
    }

    pub(crate) fn snapshot(
        &self,
        network_type: &str,
        active_outbound: Option<String>,
        targets: Vec<(String, String)>,
        generated_at_ms: u64,
    ) -> VpnHealthSnapshot {
        let profiles = targets
            .into_iter()
            .map(|(outbound, profile_id)| {
                self.profile_summary(&outbound, &profile_id, network_type, generated_at_ms)
            })
            .collect();
        let recommendation = active_outbound.as_deref().and_then(|active| {
            (self.advisor.network_type == network_type && self.advisor.active_outbound == active)
                .then(|| self.advisor.recommendation.clone())
                .flatten()
        });
        VpnHealthSnapshot {
            network_type: network_type.to_string(),
            active_outbound,
            generated_at_ms,
            profiles,
            recommendation,
            auto_enabled: false,
            preferred_outbound: None,
            last_auto_switch: None,
        }
    }

    pub(crate) fn refresh_recommendation(
        &mut self,
        network_type: &str,
        active_outbound: &str,
        targets: &[(String, String)],
        now_ms: u64,
    ) {
        if self.advisor.network_type != network_type
            || self.advisor.active_outbound != active_outbound
        {
            self.advisor = AutoAdvisor {
                network_type: network_type.to_string(),
                active_outbound: active_outbound.to_string(),
                active_since_ms: now_ms,
                ..AutoAdvisor::default()
            };
            return;
        }

        let profiles = targets
            .iter()
            .map(|(outbound, profile_id)| {
                self.profile_summary(outbound, profile_id, network_type, now_ms)
            })
            .collect::<Vec<_>>();
        let Some(active) = profiles
            .iter()
            .find(|profile| profile.outbound == active_outbound)
        else {
            self.advisor.recommendation = None;
            return;
        };
        let candidate = profiles
            .iter()
            .filter(|profile| profile.outbound != active_outbound)
            .filter_map(|profile| profile.score.map(|score| (profile, score)))
            .min_by(|(left, left_score), (right, right_score)| {
                left_score
                    .cmp(right_score)
                    .then_with(|| left.outbound.cmp(&right.outbound))
            });

        if self.consecutive_failures(active.profile_id.as_str(), network_type)
            >= FAILURE_CONFIRMATIONS
        {
            self.advisor.recommendation =
                candidate.map(|(candidate, candidate_score)| VpnHealthRecommendation {
                    outbound: candidate.outbound.clone(),
                    reason: "unavailable".to_string(),
                    active_score: active.score,
                    candidate_score,
                    observed_at_ms: now_ms,
                });
            self.reset_faster_confirmation();
            return;
        }

        let Some(active_score) = active.score else {
            self.advisor.recommendation = None;
            self.reset_faster_confirmation();
            return;
        };
        let Some((candidate, candidate_score)) = candidate else {
            self.advisor.recommendation = None;
            self.reset_faster_confirmation();
            return;
        };
        let required_gain = FASTER_MIN_GAIN_MS.max(
            active_score
                .saturating_mul(FASTER_MIN_GAIN_PERCENT)
                .div_ceil(100),
        );
        if candidate_score.saturating_add(required_gain) > active_score {
            self.advisor.recommendation = None;
            self.reset_faster_confirmation();
            return;
        }

        let checked_at_ms = candidate.last_checked_at_ms;
        if self.advisor.candidate_outbound.as_deref() != Some(candidate.outbound.as_str()) {
            self.advisor.candidate_outbound = Some(candidate.outbound.clone());
            self.advisor.candidate_checked_at_ms = checked_at_ms;
            self.advisor.confirmations = 1;
            self.advisor.recommendation = None;
        } else if checked_at_ms != self.advisor.candidate_checked_at_ms {
            self.advisor.candidate_checked_at_ms = checked_at_ms;
            self.advisor.confirmations = self.advisor.confirmations.saturating_add(1);
        }
        if now_ms.saturating_sub(self.advisor.active_since_ms) >= FASTER_MIN_ACTIVE_AGE_MS
            && self.advisor.confirmations >= FASTER_CONFIRMATIONS
        {
            self.advisor.recommendation = Some(VpnHealthRecommendation {
                outbound: candidate.outbound.clone(),
                reason: "faster".to_string(),
                active_score: Some(active_score),
                candidate_score,
                observed_at_ms: now_ms,
            });
        }
    }

    pub(crate) fn persistence_due(&self, now_ms: u64, urgent: bool) -> bool {
        self.dirty
            && (urgent || now_ms.saturating_sub(self.last_persisted_at_ms) >= PERSIST_INTERVAL_MS)
    }

    pub(crate) fn serialized(&self) -> Result<Vec<u8>, String> {
        serde_json::to_vec(&self.file).map_err(|error| error.to_string())
    }

    pub(crate) fn mark_persisted(&mut self, now_ms: u64) {
        self.dirty = false;
        self.last_persisted_at_ms = now_ms;
    }

    pub(crate) fn is_dirty(&self) -> bool {
        self.dirty
    }

    fn record(&self, profile_id: &str, network_type: &str) -> Option<&HealthRecord> {
        self.file
            .records
            .iter()
            .find(|record| record.profile_id == profile_id && record.network_type == network_type)
    }

    fn should_probe_candidate(&self, profile_id: &str, network_type: &str, now_ms: u64) -> bool {
        let Some(sample) = self
            .record(profile_id, network_type)
            .and_then(|record| record.samples.back())
        else {
            return true;
        };
        let interval = if sample.latency_ms.is_some() {
            let consecutive_successes = self
                .record(profile_id, network_type)
                .map(|record| {
                    record
                        .samples
                        .iter()
                        .rev()
                        .take_while(|sample| sample.latency_ms.is_some())
                        .count()
                })
                .unwrap_or(0);
            if consecutive_successes < MIN_SUCCESSFUL_SAMPLES {
                CANDIDATE_WARMUP_REFRESH_MS
            } else {
                CANDIDATE_SUCCESS_REFRESH_MS
            }
        } else {
            CANDIDATE_FAILURE_REFRESH_MS
        };
        now_ms.saturating_sub(sample.checked_at_ms) >= interval
    }

    fn consecutive_failures(&self, profile_id: &str, network_type: &str) -> usize {
        self.record(profile_id, network_type)
            .map(|record| {
                record
                    .samples
                    .iter()
                    .rev()
                    .take_while(|sample| sample.latency_ms.is_none())
                    .count()
            })
            .unwrap_or(0)
    }

    fn reset_faster_confirmation(&mut self) {
        self.advisor.candidate_outbound = None;
        self.advisor.candidate_checked_at_ms = None;
        self.advisor.confirmations = 0;
    }

    fn profile_summary(
        &self,
        outbound: &str,
        profile_id: &str,
        network_type: &str,
        generated_at_ms: u64,
    ) -> VpnProfileHealth {
        let samples = self
            .record(profile_id, network_type)
            .map(|record| &record.samples);
        let latencies = samples
            .into_iter()
            .flat_map(|samples| samples.iter().filter_map(|sample| sample.latency_ms))
            .collect::<Vec<_>>();
        let sample_count = samples.map_or(0, VecDeque::len);
        let successful_samples = latencies.len();
        let consecutive_successes = samples
            .map(|samples| {
                samples
                    .iter()
                    .rev()
                    .take_while(|sample| sample.latency_ms.is_some())
                    .count()
            })
            .unwrap_or(0);
        let latency_ms = average(&latencies);
        let jitter_ms = average(
            &latencies
                .windows(2)
                .map(|pair| pair[0].abs_diff(pair[1]))
                .collect::<Vec<_>>(),
        );
        let loss_percent = (sample_count > 0).then(|| {
            let failed = sample_count.saturating_sub(latencies.len());
            (((failed * 100) + (sample_count / 2)) / sample_count) as u8
        });
        let handshake_ok = samples
            .and_then(|samples| samples.back())
            .map(|sample| sample.latency_ms.is_some());
        let last_error = samples
            .and_then(|samples| samples.iter().rev().find_map(|sample| sample.error.clone()));
        let last_checked_at_ms = samples
            .and_then(|samples| samples.back())
            .map(|sample| sample.checked_at_ms);
        let fresh = last_checked_at_ms.is_some_and(|checked_at| {
            generated_at_ms.saturating_sub(checked_at) <= HEALTH_MAX_AGE_MS
        });
        let score = (successful_samples >= MIN_SUCCESSFUL_SAMPLES
            && handshake_ok == Some(true)
            && loss_percent.is_some_and(|loss| loss <= MAX_ELIGIBLE_LOSS_PERCENT)
            && fresh)
            .then(|| {
                latency_ms
                    .unwrap_or_default()
                    .saturating_add(jitter_ms.unwrap_or_default().saturating_mul(2))
                    .saturating_add(u64::from(loss_percent.unwrap_or_default()).saturating_mul(4))
            });

        VpnProfileHealth {
            outbound: outbound.to_string(),
            profile_id: profile_id.to_string(),
            sample_count,
            handshake_ok,
            latency_ms,
            jitter_ms,
            loss_percent,
            last_error,
            last_checked_at_ms,
            successful_samples,
            consecutive_successes,
            score,
        }
    }

    fn trim_records(&mut self) {
        while self.file.records.len() > MAX_RECORDS {
            let oldest = self
                .file
                .records
                .iter()
                .enumerate()
                .min_by_key(|(_, record)| {
                    record
                        .samples
                        .back()
                        .map_or(0, |sample| sample.checked_at_ms)
                })
                .map(|(index, _)| index)
                .unwrap_or(0);
            self.file.records.remove(oldest);
        }
    }
}

pub(crate) fn current_network_type() -> String {
    let mut table: *mut MIB_IF_TABLE2 = ptr::null_mut();
    if unsafe { GetIfTable2(&mut table) } != 0 || table.is_null() {
        return "other".to_string();
    }

    let result = unsafe {
        let rows: &[MIB_IF_ROW2] =
            slice::from_raw_parts((*table).Table.as_ptr(), (*table).NumEntries as usize);
        rows.iter()
            .filter(|row| row.OperStatus == IfOperStatusUp)
            .filter_map(|row| {
                classify_interface_type(row.Type)
                    .map(|network_type| (row.InOctets.saturating_add(row.OutOctets), network_type))
            })
            .max_by_key(|(traffic, _)| *traffic)
            .map(|(_, network_type)| network_type)
            .unwrap_or("other")
            .to_string()
    };
    unsafe {
        FreeMibTable(table.cast::<c_void>());
    }
    result
}

fn classify_interface_type(interface_type: u32) -> Option<&'static str> {
    match interface_type {
        IF_TYPE_IEEE80211 => Some("wifi"),
        IF_TYPE_ETHERNET_CSMACD => Some("ethernet"),
        IF_TYPE_WWANPP | IF_TYPE_WWANPP2 => Some("cellular"),
        _ => None,
    }
}

fn normalize_file(mut file: HealthFile) -> HealthFile {
    file.records.retain(|record| {
        record.profile_id.len() == 64
            && matches!(
                record.network_type.as_str(),
                "wifi" | "ethernet" | "cellular" | "other"
            )
    });
    for record in &mut file.records {
        while record.samples.len() > MAX_SAMPLES {
            record.samples.pop_front();
        }
        for sample in &mut record.samples {
            if sample.latency_ms == Some(0) {
                sample.latency_ms = None;
            }
            sample.error = sample.error.take().map(|error| probe_error_code(&error));
        }
    }
    if file.records.len() > MAX_RECORDS {
        file.records.sort_by_key(|record| {
            record
                .samples
                .back()
                .map_or(0, |sample| sample.checked_at_ms)
        });
        file.records = file.records.split_off(file.records.len() - MAX_RECORDS);
    }
    file
}

fn average(values: &[u64]) -> Option<u64> {
    (!values.is_empty()).then(|| {
        let total = values.iter().copied().map(u128::from).sum::<u128>();
        ((total + (values.len() as u128 / 2)) / values.len() as u128) as u64
    })
}

fn probe_error_code(error: &str) -> String {
    let lowercase = error.to_lowercase();
    if matches!(
        lowercase.as_str(),
        "timeout" | "unavailable" | "invalid_response" | "empty_response" | "probe_failed"
    ) || lowercase.strip_prefix("http_").is_some_and(|status| {
        !status.is_empty() && status.chars().all(|character| character.is_ascii_digit())
    }) {
        return lowercase;
    }
    if let Some(position) = lowercase.find("http ") {
        let status = lowercase[position + 5..]
            .chars()
            .take_while(char::is_ascii_digit)
            .collect::<String>();
        if !status.is_empty() {
            return format!("http_{status}");
        }
    }
    if lowercase.contains("timeout")
        || lowercase.contains("timed out")
        || lowercase.contains("время ожидания")
    {
        return "timeout".to_string();
    }
    if lowercase.contains("некоррект") || lowercase.contains("invalid") {
        return "invalid_response".to_string();
    }
    if lowercase.contains("недоступ")
        || lowercase.contains("refused")
        || lowercase.contains("connection")
    {
        return "unavailable".to_string();
    }
    "probe_failed".to_string()
}

#[cfg(test)]
mod tests {
    use super::{classify_interface_type, VpnHealthStore, MAX_SAMPLES};
    use std::fs;
    use windows_sys::Win32::NetworkManagement::IpHelper::{
        IF_TYPE_ETHERNET_CSMACD, IF_TYPE_IEEE80211, IF_TYPE_SOFTWARE_LOOPBACK, IF_TYPE_WWANPP,
    };

    const PROFILE_ID: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    const PROFILE_2: &str = "1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    const PROFILE_3: &str = "2123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    #[test]
    fn aggregates_latency_jitter_loss_and_last_error() {
        let mut store = VpnHealthStore::default();
        store.record_probe(PROFILE_ID, "wifi", 1_000, Ok(40));
        store.record_probe(PROFILE_ID, "wifi", 2_000, Ok(60));
        store.record_probe(PROFILE_ID, "wifi", 3_000, Err("timeout".to_string()));
        let snapshot = store.snapshot(
            "wifi",
            Some("profile-1".to_string()),
            vec![("profile-1".to_string(), PROFILE_ID.to_string())],
            4_000,
        );
        let profile = &snapshot.profiles[0];

        assert_eq!(profile.sample_count, 3);
        assert_eq!(profile.handshake_ok, Some(false));
        assert_eq!(profile.latency_ms, Some(50));
        assert_eq!(profile.jitter_ms, Some(20));
        assert_eq!(profile.loss_percent, Some(33));
        assert_eq!(profile.last_error.as_deref(), Some("timeout"));
        assert_eq!(profile.last_checked_at_ms, Some(3_000));
        assert_eq!(profile.successful_samples, 2);
        assert_eq!(profile.score, None);
    }

    #[test]
    fn history_is_bounded_and_has_failure_cooldown() {
        let mut store = VpnHealthStore::default();
        for index in 0..MAX_SAMPLES + 3 {
            store.record_probe(PROFILE_ID, "ethernet", index as u64 * 1_000, Ok(25));
        }
        let snapshot = store.snapshot(
            "ethernet",
            None,
            vec![("profile-1".to_string(), PROFILE_ID.to_string())],
            20_000,
        );
        assert_eq!(snapshot.profiles[0].sample_count, MAX_SAMPLES);
        assert!(!store.should_probe(PROFILE_ID, "ethernet", 19_000));
        assert!(store.should_probe(PROFILE_ID, "ethernet", 29_000));

        assert!(store.record_probe(PROFILE_ID, "wifi", 30_000, Err("timeout".to_string())));
        assert!(!store.record_probe(PROFILE_ID, "wifi", 31_000, Err("timeout".to_string())));
        assert!(!store.should_probe(PROFILE_ID, "wifi", 37_999));
        assert!(store.should_probe(PROFILE_ID, "wifi", 39_000));
        assert!(store.record_probe(PROFILE_ID, "wifi", 39_000, Ok(25)));
    }

    #[test]
    fn network_classification_never_uses_adapter_names() {
        assert_eq!(classify_interface_type(IF_TYPE_IEEE80211), Some("wifi"));
        assert_eq!(
            classify_interface_type(IF_TYPE_ETHERNET_CSMACD),
            Some("ethernet")
        );
        assert_eq!(classify_interface_type(IF_TYPE_WWANPP), Some("cellular"));
        assert_eq!(classify_interface_type(IF_TYPE_SOFTWARE_LOOPBACK), None);
    }

    #[test]
    fn persisted_history_contains_only_sanitized_error_codes() {
        let mut store = VpnHealthStore::default();
        store.record_probe(
            PROFILE_ID,
            "wifi",
            1_000,
            Err("connection failed password=private-value".to_string()),
        );
        let serialized = store.serialized().expect("serialize health history");
        let text = String::from_utf8(serialized.clone()).expect("health history is utf-8");
        assert!(!text.contains("private-value"));
        assert!(text.contains("unavailable"));

        let path = std::env::temp_dir().join(format!("warpy-health-{}.json", std::process::id()));
        fs::write(&path, serialized).expect("write health history");
        let loaded = VpnHealthStore::load(&path, 2_000);
        let _ = fs::remove_file(path);
        let snapshot = loaded.snapshot(
            "wifi",
            None,
            vec![("profile-1".to_string(), PROFILE_ID.to_string())],
            2_000,
        );
        assert_eq!(
            snapshot.profiles[0].last_error.as_deref(),
            Some("unavailable")
        );
    }

    #[test]
    fn score_requires_confirmed_recent_success() {
        let mut store = VpnHealthStore::default();
        store.record_probe(PROFILE_ID, "wifi", 1_000, Ok(150));
        store.record_probe(PROFILE_ID, "wifi", 2_000, Ok(170));
        store.record_probe(PROFILE_2, "wifi", 1_000, Ok(50));
        store.record_probe(PROFILE_2, "wifi", 2_000, Ok(60));
        let snapshot = store.snapshot(
            "wifi",
            Some("profile-1".to_string()),
            vec![
                ("profile-1".to_string(), PROFILE_ID.to_string()),
                ("profile-2".to_string(), PROFILE_2.to_string()),
            ],
            3_000,
        );

        assert_eq!(snapshot.profiles[0].score, Some(200));
        assert_eq!(snapshot.profiles[1].score, Some(75));

        store.record_probe(PROFILE_2, "wifi", 4_000, Err("timeout".to_string()));
        let snapshot = store.snapshot(
            "wifi",
            Some("profile-1".to_string()),
            vec![("profile-2".to_string(), PROFILE_2.to_string())],
            4_000,
        );
        assert_eq!(snapshot.profiles[0].score, None);

        store.record_probe(PROFILE_2, "wifi", 5_000, Ok(55));
        assert!(store
            .snapshot(
                "wifi",
                Some("profile-1".to_string()),
                vec![("profile-2".to_string(), PROFILE_2.to_string())],
                5_000,
            )
            .profiles[0]
            .score
            .is_some());
        store.record_probe(PROFILE_2, "wifi", 6_000, Err("timeout".to_string()));
        store.record_probe(PROFILE_2, "wifi", 7_000, Ok(55));
        assert_eq!(
            store
                .snapshot(
                    "wifi",
                    Some("profile-1".to_string()),
                    vec![("profile-2".to_string(), PROFILE_2.to_string())],
                    7_000,
                )
                .profiles[0]
                .score,
            None
        );
    }

    #[test]
    fn candidate_scheduler_probes_one_profile_at_a_time_and_prioritizes_active() {
        let mut store = VpnHealthStore::default();
        store.record_probe(PROFILE_ID, "wifi", 0, Ok(100));
        let targets = vec![
            ("profile-1".to_string(), PROFILE_ID.to_string()),
            ("profile-2".to_string(), PROFILE_2.to_string()),
            ("profile-3".to_string(), PROFILE_3.to_string()),
        ];

        let first = store
            .next_probe_target("profile-1", &targets, "wifi", 1_000)
            .expect("first candidate");
        assert_eq!(first.outbound, "profile-2");
        assert!(!first.active);
        store.record_probe(PROFILE_2, "wifi", 1_000, Ok(80));
        assert!(store
            .next_probe_target("profile-1", &targets, "wifi", 5_000)
            .is_none());

        let second = store
            .next_probe_target("profile-1", &targets, "wifi", 11_000)
            .expect("second candidate");
        assert_eq!(second.outbound, "profile-3");
        store.record_probe(PROFILE_3, "wifi", 11_000, Ok(90));

        let active = store
            .next_probe_target("profile-1", &targets, "wifi", 15_000)
            .expect("active profile probe");
        assert_eq!(active.outbound, "profile-1");
        assert!(active.active);
    }

    #[test]
    fn candidate_warms_up_quickly_then_uses_slow_refresh() {
        let mut store = VpnHealthStore::default();
        store.record_probe(PROFILE_ID, "wifi", 0, Ok(100));
        let targets = vec![
            ("profile-1".to_string(), PROFILE_ID.to_string()),
            ("profile-2".to_string(), PROFILE_2.to_string()),
        ];
        let first = store
            .next_probe_target("profile-1", &targets, "wifi", 1_000)
            .expect("initial candidate probe");
        assert_eq!(first.outbound, "profile-2");
        store.record_probe(PROFILE_2, "wifi", 1_000, Ok(70));
        store.record_probe(PROFILE_ID, "wifi", 20_000, Ok(100));

        assert!(store
            .next_probe_target("profile-1", &targets, "wifi", 30_999)
            .is_none());
        let confirmation = store
            .next_probe_target("profile-1", &targets, "wifi", 31_000)
            .expect("candidate confirmation");
        assert_eq!(confirmation.outbound, "profile-2");
        store.record_probe(PROFILE_2, "wifi", 31_000, Ok(72));
        store.record_probe(PROFILE_ID, "wifi", 620_000, Ok(100));

        assert!(store
            .next_probe_target("profile-1", &targets, "wifi", 630_999)
            .is_none());
        assert_eq!(
            store
                .next_probe_target("profile-1", &targets, "wifi", 631_000)
                .expect("candidate refresh")
                .outbound,
            "profile-2"
        );
    }

    #[test]
    fn recovering_candidate_gets_a_second_confirmation_after_thirty_seconds() {
        let mut store = VpnHealthStore::default();
        store.record_probe(PROFILE_2, "wifi", 1_000, Ok(70));
        store.record_probe(PROFILE_2, "wifi", 2_000, Ok(72));
        store.record_probe(PROFILE_2, "wifi", 10_000, Err("timeout".to_string()));

        assert!(!store.should_probe_candidate(PROFILE_2, "wifi", 69_999));
        assert!(store.should_probe_candidate(PROFILE_2, "wifi", 70_000));
        store.record_probe(PROFILE_2, "wifi", 70_000, Ok(71));
        assert!(!store.should_probe_candidate(PROFILE_2, "wifi", 99_999));
        assert!(store.should_probe_candidate(PROFILE_2, "wifi", 100_000));
    }

    #[test]
    fn faster_recommendation_needs_fresh_confirmation_and_resets_on_switch() {
        let mut store = VpnHealthStore::default();
        store.record_probe(PROFILE_ID, "wifi", 1_000, Ok(150));
        store.record_probe(PROFILE_ID, "wifi", 2_000, Ok(170));
        store.record_probe(PROFILE_2, "wifi", 1_000, Ok(50));
        store.record_probe(PROFILE_2, "wifi", 2_000, Ok(60));
        let targets = vec![
            ("profile-1".to_string(), PROFILE_ID.to_string()),
            ("profile-2".to_string(), PROFILE_2.to_string()),
        ];

        store.refresh_recommendation("wifi", "profile-1", &targets, 10_000);
        store.refresh_recommendation("wifi", "profile-1", &targets, 130_000);
        assert!(store
            .snapshot(
                "wifi",
                Some("profile-1".to_string()),
                targets.clone(),
                130_000,
            )
            .recommendation
            .is_none());

        store.record_probe(PROFILE_2, "wifi", 131_000, Ok(55));
        store.refresh_recommendation("wifi", "profile-1", &targets, 131_000);
        let snapshot = store.snapshot(
            "wifi",
            Some("profile-1".to_string()),
            targets.clone(),
            131_000,
        );
        let recommendation = snapshot.recommendation.expect("faster recommendation");
        assert_eq!(recommendation.outbound, "profile-2");
        assert_eq!(recommendation.reason, "faster");

        store.refresh_recommendation("wifi", "profile-2", &targets, 132_000);
        assert!(store
            .snapshot("wifi", Some("profile-2".to_string()), targets, 132_000,)
            .recommendation
            .is_none());
    }

    #[test]
    fn repeated_active_failures_recommend_a_confirmed_backup() {
        let mut store = VpnHealthStore::default();
        store.record_probe(PROFILE_ID, "ethernet", 1_000, Ok(100));
        store.record_probe(PROFILE_ID, "ethernet", 2_000, Ok(110));
        store.record_probe(PROFILE_ID, "ethernet", 3_000, Err("timeout".to_string()));
        store.record_probe(PROFILE_ID, "ethernet", 4_000, Err("timeout".to_string()));
        store.record_probe(PROFILE_2, "ethernet", 1_000, Ok(70));
        store.record_probe(PROFILE_2, "ethernet", 2_000, Ok(75));
        let targets = vec![
            ("profile-1".to_string(), PROFILE_ID.to_string()),
            ("profile-2".to_string(), PROFILE_2.to_string()),
        ];

        store.refresh_recommendation("ethernet", "profile-1", &targets, 4_000);
        store.refresh_recommendation("ethernet", "profile-1", &targets, 5_000);
        let recommendation = store
            .snapshot("ethernet", Some("profile-1".to_string()), targets, 5_000)
            .recommendation
            .expect("failover recommendation");
        assert_eq!(recommendation.outbound, "profile-2");
        assert_eq!(recommendation.reason, "unavailable");
    }
}
