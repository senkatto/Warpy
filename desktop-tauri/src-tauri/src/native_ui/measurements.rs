use super::{measurement_client, Message, Sender};
use serde_json::json;
use std::{
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};
use tokio_stream::StreamExt;

pub(super) async fn ping() -> Result<f64, String> {
    tokio::time::timeout(Duration::from_secs(3), async {
        let client = measurement_client().map_err(|e| e.to_string())?;
        let start = Instant::now();
        let url = format!(
            "https://speed.cloudflare.com/__down?bytes=1024&health={}",
            super::controller::unix()
        );
        if let Ok(response) = client
            .get(url)
            .header("Cache-Control", "no-store")
            .send()
            .await
        {
            if response.status().is_success()
                && response.bytes().await.is_ok_and(|body| body.len() >= 1024)
            {
                return Ok(start.elapsed().as_secs_f64() * 1000.0);
            }
        }
        let response = client
            .get("https://www.google.com/generate_204")
            .header("Cache-Control", "no-store")
            .send()
            .await
            .map_err(|e| e.to_string())?;
        if response.status().is_success() {
            Ok(start.elapsed().as_secs_f64() * 1000.0)
        } else {
            Err(format!("HTTP {}", response.status()))
        }
    })
    .await
    .map_err(|_| "Network measurement timed out".to_string())?
}

fn median(samples: &mut [f64]) -> Option<f64> {
    if samples.len() < 3 {
        return None;
    }
    samples.sort_by(f64::total_cmp);
    Some(if samples.len().is_multiple_of(2) {
        (samples[samples.len() / 2 - 1] + samples[samples.len() / 2]) / 2.0
    } else {
        samples[samples.len() / 2]
    })
}

async fn transfer(sender: &Sender, generation: u64, upload: bool) -> Result<f64, String> {
    let client = measurement_client().map_err(|e| e.to_string())?;
    let start = Instant::now();
    let measure_at = start + Duration::from_secs(1);
    let finish_at = measure_at + Duration::from_secs(5);
    let bytes = Arc::new(AtomicU64::new(0));
    let mut workers = tokio::task::JoinSet::new();
    for stream in 0..4 {
        let client = client.clone();
        let bytes = bytes.clone();
        workers.spawn(async move {
            while Instant::now() < finish_at {
                let url = format!(
                    "https://speed.cloudflare.com/{}stream={stream}&run={}",
                    if upload {
                        "__up?"
                    } else {
                        "__down?bytes=100000000&"
                    },
                    super::controller::unix()
                );
                let mut response = if upload {
                    let counter = bytes.clone();
                    let body = tokio_stream::iter(0..128).map(move |_| {
                        if Instant::now() >= measure_at && Instant::now() < finish_at {
                            counter.fetch_add(64 * 1024, Ordering::Relaxed);
                        }
                        Ok::<_, std::io::Error>(vec![0u8; 64 * 1024])
                    });
                    client
                        .post(url)
                        .header(reqwest::header::CONTENT_LENGTH, 8 * 1024 * 1024)
                        .body(reqwest::Body::wrap_stream(body))
                        .send()
                        .await
                } else {
                    client
                        .get(url)
                        .header("Cache-Control", "no-store")
                        .send()
                        .await
                }
                .map_err(|e| e.to_string())?;
                if !response.status().is_success() {
                    return Err(format!("HTTP {}", response.status()));
                }
                while let Some(chunk) = response.chunk().await.map_err(|e| e.to_string())? {
                    if !upload && Instant::now() >= measure_at && Instant::now() < finish_at {
                        bytes.fetch_add(chunk.len() as u64, Ordering::Relaxed);
                    }
                }
            }
            Ok::<(), String>(())
        });
    }
    let stage = if upload { "UPLOAD" } else { "DOWNLOAD" };
    let mut interval = tokio::time::interval(Duration::from_millis(150));
    loop {
        tokio::select! {
            _ = tokio::time::sleep_until(tokio::time::Instant::from_std(finish_at)) => break,
            _ = interval.tick() => {
                let seconds = Instant::now().saturating_duration_since(measure_at).as_secs_f64();
                let mbps = bytes.load(Ordering::Relaxed) as f64*8.0/seconds.max(0.1)/1_000_000.0;
                sender.send(Message::Event("native://speed-progress".into(),json!({"generation":generation,"stage":stage,"value":mbps})));
            }
            result = workers.join_next(), if !workers.is_empty() => {
                match result {
                    Some(Ok(Ok(()))) => {},
                    Some(Ok(Err(error))) => return Err(error),
                    Some(Err(error)) => return Err(error.to_string()),
                    None => {},
                }
            }
        }
    }
    // JoinSet aborts all owned streams both at stage completion and on cancellation.
    drop(workers);
    let count = bytes.load(Ordering::Relaxed);
    if count == 0 {
        return Err("No traffic received during measurement".into());
    }
    Ok(count as f64 * 8.0 / 5.0 / 1_000_000.0)
}

pub(super) async fn speedtest(sender: Sender, generation: u64) {
    let result = async {
        sender.send(Message::Event(
            "native://speed-progress".into(),
            json!({"generation":generation,"stage":"PING","value":0}),
        ));
        let mut samples = Vec::new();
        for _ in 0..5 {
            if let Ok(sample) = ping().await {
                samples.push(sample);
            }
        }
        let latency = median(&mut samples)
            .ok_or_else(|| "Too few successful latency measurements".to_string())?;
        let down = transfer(&sender, generation, false).await?;
        let up = transfer(&sender, generation, true).await?;
        Ok::<_, String>([down, up, latency])
    }
    .await;
    sender.send(Message::Event(
        "native://speed-result".into(),
        json!({"generation":generation,"result":result}),
    ));
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn latency_requires_successful_samples_and_uses_the_median() {
        assert_eq!(median(&mut [12.0, 999.0]), None);
        assert_eq!(median(&mut [999.0, 31.0, 30.0, 29.0, 32.0]), Some(31.0));
        assert_eq!(median(&mut [50.0, 10.0, 30.0, 20.0]), Some(25.0));
    }
}
