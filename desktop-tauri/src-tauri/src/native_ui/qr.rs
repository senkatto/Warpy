use qrcodegen::{Mask, QrCode, QrCodeEcc, QrSegment, Version};

// Preserve the previous QR layout: byte encoding, level L and the original
// mask penalty calculation (which scores format/version bits as light).
pub(super) fn svg(value: &str) -> Result<String, String> {
    let segments = [QrSegment::make_bytes(value.as_bytes())];
    let first = QrCode::encode_segments_advanced(
        &segments,
        QrCodeEcc::Low,
        Version::MIN,
        Version::MAX,
        Some(Mask::new(0)),
        false,
    )
    .map_err(|e| e.to_string())?;
    let version = first.version();
    let mut best = first;
    let mut penalty = score(&best);
    for mask in 1..8 {
        let qr = QrCode::encode_segments_advanced(
            &segments,
            QrCodeEcc::Low,
            version,
            version,
            Some(Mask::new(mask)),
            false,
        )
        .map_err(|e| e.to_string())?;
        let candidate = score(&qr);
        if candidate < penalty {
            best = qr;
            penalty = candidate;
        }
    }
    let size = best.size() * 5 + 20;
    let mut path = String::new();
    for y in 0..best.size() {
        for x in 0..best.size() {
            if best.get_module(x, y) {
                path.push_str(&format!(
                    "M{},{}l5,0 0,5 -5,0 0,-5z ",
                    x * 5 + 10,
                    y * 5 + 10
                ));
            }
        }
    }
    Ok(format!("<svg version=\"1.1\" xmlns=\"http://www.w3.org/2000/svg\" width=\"{size}px\" height=\"{size}px\" viewBox=\"0 0 {size} {size}\"  preserveAspectRatio=\"xMinYMin meet\"><rect width=\"100%\" height=\"100%\" fill=\"white\" cx=\"0\" cy=\"0\"/><path d=\"{path}\" stroke=\"transparent\" fill=\"black\"/></svg>"))
}
fn score(qr: &QrCode) -> f64 {
    let n = qr.size();
    let mut cells = (0..n)
        .map(|y| (0..n).map(|x| qr.get_module(x, y)).collect::<Vec<_>>())
        .collect::<Vec<_>>();
    for i in 0..15 {
        let y = if i < 6 {
            i
        } else if i < 8 {
            i + 1
        } else {
            n - 15 + i
        };
        cells[y as usize][8] = false;
        let x = if i < 8 {
            n - i - 1
        } else if i < 9 {
            15 - i
        } else {
            14 - i
        };
        cells[8][x as usize] = false;
    }
    cells[(n - 8) as usize][8] = false;
    if qr.version().value() >= 7 {
        for i in 0..18 {
            let a = i / 3;
            let b = i % 3 + n - 11;
            cells[a as usize][b as usize] = false;
            cells[b as usize][a as usize] = false;
        }
    }
    let dark = |x: i32, y: i32| cells[y as usize][x as usize];
    let mut lost = 0.;
    let mut count = 0;
    for y in 0..n {
        for x in 0..n {
            let mut neighbors = 0;
            for dy in -1..=1 {
                for dx in -1..=1 {
                    if (dx != 0 || dy != 0)
                        && (0..n).contains(&(x + dx))
                        && (0..n).contains(&(y + dy))
                        && dark(x, y) == dark(x + dx, y + dy)
                    {
                        neighbors += 1;
                    }
                }
            }
            if neighbors > 5 {
                lost += (neighbors - 2) as f64;
            }
            if dark(x, y) {
                count += 1;
            }
        }
    }
    for y in 0..n - 1 {
        for x in 0..n - 1 {
            let count = [
                dark(x, y),
                dark(x + 1, y),
                dark(x, y + 1),
                dark(x + 1, y + 1),
            ]
            .into_iter()
            .filter(|v| *v)
            .count();
            if count == 0 || count == 4 {
                lost += 3.;
            }
        }
    }
    let pattern = [true, false, true, true, true, false, true];
    for y in 0..n {
        for x in 0..n - 6 {
            if (0..7).all(|i| dark(x + i, y) == pattern[i as usize]) {
                lost += 40.;
            }
            if (0..7).all(|i| dark(y, x + i) == pattern[i as usize]) {
                lost += 40.;
            }
        }
    }
    lost + (100. * count as f64 / (n * n) as f64 - 50.).abs() / 5. * 10.
}
