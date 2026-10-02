use serde::Deserialize;
use std::{collections::HashMap, path::PathBuf};
use windows::{
    core::{w, Interface},
    Win32::{
        Foundation::HWND,
        Graphics::{
            Direct2D::{Common::*, *},
            DirectWrite::*,
            Dxgi::Common::DXGI_FORMAT_B8G8R8A8_UNORM,
        },
    },
};
use windows_numerics::{Matrix3x2, Vector2};

#[derive(Default, Deserialize, Debug, PartialEq)]
#[serde(default)]
pub(super) struct Op {
    pub(super) kind: String,
    pub(super) x: f32,
    pub(super) y: f32,
    pub(super) w: f32,
    pub(super) h: f32,
    pub(super) color: String,
    pub(super) radius: f32,
    pub(super) stroke: f32,
    pub(super) rotation: f32,
    pub(super) text: String,
    pub(super) size: f32,
    pub(super) weight: i32,
    pub(super) align: String,
    pub(super) wrap: bool,
    pub(super) break_all: bool,
    pub(super) source: String,
    pub(super) started: f64,
    pub(super) connected_at: Option<f64>,
    pub(super) connected: bool,
    pub(super) spin: bool,
}

#[derive(Default, Deserialize, Clone)]
#[serde(default)]
pub(super) struct Hit {
    pub(super) id: String,
    pub(super) kind: String,
    pub(super) x: f32,
    pub(super) y: f32,
    pub(super) w: f32,
    pub(super) h: f32,
    pub(super) max: f32,
}
impl Hit {
    pub(super) fn contains(&self, x: f32, y: f32) -> bool {
        x >= self.x && y >= self.y && x < self.x + self.w && y < self.y + self.h
    }
}
#[derive(Default, Deserialize)]
#[serde(default)]
pub(super) struct Scene {
    pub(super) ops: Vec<Op>,
    pub(super) hits: Vec<Hit>,
    pub(super) scrolls: Vec<Hit>,
}
impl Scene {
    pub(super) fn animated(&self) -> bool {
        self.ops
            .iter()
            .any(|op| matches!(op.kind.as_str(), "particles" | "warp") || op.spin)
    }
}

pub(super) struct Painter {
    target: ID2D1RenderTarget,
    write: IDWriteFactory,
    brush: ID2D1SolidColorBrush,
    fonts: HashMap<String, IDWriteTextFormat>,
    layouts: HashMap<(String, String, u32, u32), IDWriteTextLayout>,
    images: HashMap<String, ID2D1Bitmap>,
    flags: PathBuf,
    dpi: f32,
    warp: super::warp::Warp,
}

impl Painter {
    pub(super) fn measure_text(text: String, size: f32, weight: i32) -> f32 {
        thread_local! { static CACHE: std::cell::RefCell<HashMap<(String,u32,i32),f32>> = std::cell::RefCell::new(HashMap::new()); }
        let key = (text.clone(), size.to_bits(), weight);
        if let Some(value) = CACHE.with(|cache| cache.borrow().get(&key).copied()) {
            return value;
        }
        let width = unsafe {
            (|| -> windows::core::Result<f32> {
                let factory: IDWriteFactory = DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED)?;
                let format = factory.CreateTextFormat(
                    w!("Segoe UI Variable"),
                    None,
                    DWRITE_FONT_WEIGHT(weight),
                    DWRITE_FONT_STYLE_NORMAL,
                    DWRITE_FONT_STRETCH_NORMAL,
                    size,
                    w!("ru-RU"),
                )?;
                let text: Vec<u16> = text.encode_utf16().collect();
                let layout = factory.CreateTextLayout(&text, &format, 10000.0, 1000.0)?;
                let mut metrics = DWRITE_TEXT_METRICS::default();
                layout.GetMetrics(&mut metrics)?;
                Ok(metrics.widthIncludingTrailingWhitespace)
            })()
        }
        .unwrap_or(text.chars().count() as f32 * size / 2.0);
        CACHE.with(|cache| {
            let mut cache = cache.borrow_mut();
            if cache.len() >= 256 {
                cache.clear();
            }
            cache.insert(key, width);
        });
        width
    }
    pub(super) fn new(
        hwnd: HWND,
        width: u32,
        height: u32,
        dpi: u32,
        flags: PathBuf,
    ) -> windows::core::Result<Self> {
        unsafe {
            let factory: ID2D1Factory = D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, None)?;
            let target = factory.CreateHwndRenderTarget(
                &D2D1_RENDER_TARGET_PROPERTIES {
                    r#type: D2D1_RENDER_TARGET_TYPE_DEFAULT,
                    pixelFormat: D2D1_PIXEL_FORMAT {
                        format: DXGI_FORMAT_B8G8R8A8_UNORM,
                        alphaMode: D2D1_ALPHA_MODE_IGNORE,
                    },
                    dpiX: dpi as f32,
                    dpiY: dpi as f32,
                    ..Default::default()
                },
                &D2D1_HWND_RENDER_TARGET_PROPERTIES {
                    hwnd,
                    pixelSize: D2D_SIZE_U { width, height },
                    presentOptions: D2D1_PRESENT_OPTIONS_IMMEDIATELY,
                },
            )?;
            let target: ID2D1RenderTarget = target.cast()?;
            target.SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
            let brush = target.CreateSolidColorBrush(&color("#fff"), None)?;
            let write = DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED)?;
            Ok(Self {
                target,
                write,
                brush,
                fonts: HashMap::new(),
                layouts: HashMap::new(),
                images: HashMap::new(),
                flags,
                dpi: dpi as f32 / 96.0,
                warp: Default::default(),
            })
        }
    }

    pub(super) fn paint(&mut self, scene: &Scene, time_ms: f64) -> windows::core::Result<()> {
        unsafe {
            self.target.BeginDraw();
            self.target.Clear(Some(&D2D1_COLOR_F {
                r: 0.0,
                g: 0.0,
                b: 0.0,
                a: 0.0,
            }));
            for op in &scene.ops {
                let rect = D2D_RECT_F {
                    left: op.x,
                    top: op.y,
                    right: op.x + op.w,
                    bottom: op.y + op.h,
                };
                self.brush.SetColor(&color(&op.color));
                match op.kind.as_str() {
                    "warp" => {
                        for trail in self.warp.frame(
                            op.started,
                            time_ms,
                            op.size as f64,
                            op.color == "upload",
                        ) {
                            self.brush.SetColor(&D2D1_COLOR_F {
                                r: trail.color[0],
                                g: trail.color[1],
                                b: trail.color[2],
                                a: trail.color[3],
                            });
                            self.target.DrawLine(
                                Vector2 {
                                    X: op.x + trail.tail[0],
                                    Y: op.y + trail.tail[1],
                                },
                                Vector2 {
                                    X: op.x + trail.head[0],
                                    Y: op.y + trail.head[1],
                                },
                                &self.brush,
                                trail.width,
                                None,
                            );
                        }
                    }
                    "particles" => {
                        let elapsed = ((time_ms - op.started) / 1000.0).max(0.0);
                        for dot in super::particles::frame(elapsed, op.connected_at, op.connected) {
                            self.brush.SetColor(&D2D1_COLOR_F {
                                r: dot.color[0] as f32,
                                g: dot.color[1] as f32,
                                b: dot.color[2] as f32,
                                a: dot.color[3] as f32,
                            });
                            self.target.FillEllipse(
                                &D2D1_ELLIPSE {
                                    point: Vector2 {
                                        X: op.x + dot.x as f32,
                                        Y: op.y + dot.y as f32,
                                    },
                                    radiusX: dot.radius as f32,
                                    radiusY: dot.radius as f32,
                                },
                                &self.brush,
                            );
                        }
                    }
                    "rect" => {
                        let inset = op.stroke / 2.0;
                        let rounded = D2D1_ROUNDED_RECT {
                            rect: D2D_RECT_F {
                                left: rect.left + inset,
                                top: rect.top + inset,
                                right: rect.right - inset,
                                bottom: rect.bottom - inset,
                            },
                            radiusX: (op.radius - inset).max(0.0),
                            radiusY: (op.radius - inset).max(0.0),
                        };
                        if op.stroke > 0.0 {
                            self.target.DrawRoundedRectangle(
                                &rounded,
                                &self.brush,
                                op.stroke,
                                None,
                            );
                        } else {
                            self.target.FillRoundedRectangle(&rounded, &self.brush);
                        }
                    }
                    "ellipse" => {
                        let ellipse = D2D1_ELLIPSE {
                            point: Vector2 {
                                X: op.x + op.w / 2.0,
                                Y: op.y + op.h / 2.0,
                            },
                            radiusX: op.w / 2.0,
                            radiusY: op.h / 2.0,
                        };
                        if op.stroke > 0.0 {
                            self.target
                                .DrawEllipse(&ellipse, &self.brush, op.stroke, None);
                        } else {
                            self.target.FillEllipse(&ellipse, &self.brush);
                        }
                    }
                    "line" => self.target.DrawLine(
                        Vector2 { X: op.x, Y: op.y },
                        Vector2 { X: op.w, Y: op.h },
                        &self.brush,
                        op.stroke.max(1.0),
                        None,
                    ),
                    "text" => {
                        let key = format!(
                            "{}:{}:{}:{}:{}",
                            op.size, op.weight, op.align, op.wrap, op.break_all
                        );
                        if !self.fonts.contains_key(&key) {
                            let format = self.write.CreateTextFormat(
                                w!("Segoe UI Variable"),
                                None,
                                DWRITE_FONT_WEIGHT(op.weight.max(400)),
                                DWRITE_FONT_STYLE_NORMAL,
                                DWRITE_FONT_STRETCH_NORMAL,
                                op.size.max(1.0),
                                w!("ru-RU"),
                            )?;
                            format.SetTextAlignment(match op.align.as_str() {
                                "center" => DWRITE_TEXT_ALIGNMENT_CENTER,
                                "right" => DWRITE_TEXT_ALIGNMENT_TRAILING,
                                _ => DWRITE_TEXT_ALIGNMENT_LEADING,
                            })?;
                            format.SetParagraphAlignment(if op.wrap {
                                DWRITE_PARAGRAPH_ALIGNMENT_NEAR
                            } else {
                                DWRITE_PARAGRAPH_ALIGNMENT_CENTER
                            })?;
                            format.SetWordWrapping(if op.break_all {
                                DWRITE_WORD_WRAPPING_CHARACTER
                            } else if op.wrap {
                                DWRITE_WORD_WRAPPING_WRAP
                            } else {
                                DWRITE_WORD_WRAPPING_NO_WRAP
                            })?;
                            let trimming = DWRITE_TRIMMING {
                                granularity: DWRITE_TRIMMING_GRANULARITY_CHARACTER,
                                ..Default::default()
                            };
                            let sign = self.write.CreateEllipsisTrimmingSign(&format)?;
                            if !op.wrap {
                                format.SetTrimming(&trimming, &sign)?;
                            }
                            self.fonts.insert(key.clone(), format);
                        }
                        let layout_key =
                            (op.text.clone(), key.clone(), op.w.to_bits(), op.h.to_bits());
                        if !self.layouts.contains_key(&layout_key) {
                            let text: Vec<u16> = op.text.encode_utf16().collect();
                            let layout = self.write.CreateTextLayout(
                                &text,
                                &self.fonts[&key],
                                op.w,
                                op.h,
                            )?;
                            if self.layouts.len() >= 256 {
                                self.layouts.clear();
                            }
                            self.layouts.insert(layout_key.clone(), layout);
                        }
                        self.target.DrawTextLayout(
                            Vector2 { X: op.x, Y: op.y },
                            &self.layouts[&layout_key],
                            &self.brush,
                            D2D1_DRAW_TEXT_OPTIONS_CLIP,
                        );
                    }
                    "svg" | "image" => {
                        if let Some(bitmap) = self.bitmap(op) {
                            let rotation = if op.spin {
                                (time_ms / 1400.0 * std::f64::consts::TAU) as f32
                            } else {
                                op.rotation
                            };
                            if rotation != 0.0 {
                                let (sin, cos) = rotation.sin_cos();
                                let cx = op.x + op.w / 2.0;
                                let cy = op.y + op.h / 2.0;
                                self.target.SetTransform(&Matrix3x2 {
                                    M11: cos,
                                    M12: sin,
                                    M21: -sin,
                                    M22: cos,
                                    M31: cx - cx * cos + cy * sin,
                                    M32: cy - cx * sin - cy * cos,
                                });
                            }
                            self.target.DrawBitmap(
                                &bitmap,
                                Some(&rect),
                                1.0,
                                D2D1_BITMAP_INTERPOLATION_MODE_LINEAR,
                                None,
                            );
                            if rotation != 0.0 {
                                self.target.SetTransform(&Matrix3x2 {
                                    M11: 1.0,
                                    M22: 1.0,
                                    ..Default::default()
                                });
                            }
                        }
                    }
                    "clip" => self
                        .target
                        .PushAxisAlignedClip(&rect, D2D1_ANTIALIAS_MODE_PER_PRIMITIVE),
                    "unclip" => self.target.PopAxisAlignedClip(),
                    _ => {}
                }
            }
            self.target.EndDraw(None, None)
        }
    }

    #[cfg(test)]
    pub(super) fn snapshot(
        scene: &Scene,
        destination: &std::path::Path,
        time_ms: f64,
    ) -> Result<(), String> {
        use windows::Win32::{Graphics::Imaging::*, System::Com::*};
        unsafe {
            let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
            let imaging: IWICImagingFactory =
                CoCreateInstance(&CLSID_WICImagingFactory, None, CLSCTX_INPROC_SERVER)
                    .map_err(|e| e.to_string())?;
            let bitmap = imaging
                .CreateBitmap(
                    420,
                    720,
                    &GUID_WICPixelFormat32bppPBGRA,
                    WICBitmapCacheOnLoad,
                )
                .map_err(|e| e.to_string())?;
            let factory: ID2D1Factory = D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, None)
                .map_err(|e| e.to_string())?;
            let target = factory
                .CreateWicBitmapRenderTarget(
                    &bitmap,
                    &D2D1_RENDER_TARGET_PROPERTIES {
                        r#type: D2D1_RENDER_TARGET_TYPE_SOFTWARE,
                        pixelFormat: D2D1_PIXEL_FORMAT {
                            format: DXGI_FORMAT_B8G8R8A8_UNORM,
                            alphaMode: D2D1_ALPHA_MODE_PREMULTIPLIED,
                        },
                        dpiX: 96.0,
                        dpiY: 96.0,
                        ..Default::default()
                    },
                )
                .map_err(|e| e.to_string())?;
            target.SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
            let brush = target
                .CreateSolidColorBrush(&color("#fff"), None)
                .map_err(|e| e.to_string())?;
            let mut painter = Self {
                target,
                brush,
                write: DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED)
                    .map_err(|e| e.to_string())?,
                fonts: HashMap::new(),
                layouts: HashMap::new(),
                images: HashMap::new(),
                flags: PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("flags"),
                dpi: 1.0,
                warp: Default::default(),
            };
            painter.paint(scene, time_ms).map_err(|e| e.to_string())?;
            drop(painter);
            let lock = bitmap
                .Lock(
                    &WICRect {
                        X: 0,
                        Y: 0,
                        Width: 420,
                        Height: 720,
                    },
                    WICBitmapLockRead.0 as u32,
                )
                .map_err(|e| e.to_string())?;
            let mut bytes = 0;
            let mut pointer = std::ptr::null_mut();
            lock.GetDataPointer(&mut bytes, &mut pointer)
                .map_err(|e| e.to_string())?;
            let mut pixels = std::slice::from_raw_parts(pointer, bytes as usize).to_vec();
            for pixel in pixels.chunks_exact_mut(4) {
                pixel.swap(0, 2);
                if pixel[3] > 0 {
                    for channel in 0..3 {
                        pixel[channel] =
                            ((pixel[channel] as u32 * 255) / pixel[3] as u32).min(255) as u8;
                    }
                }
            }
            // Apply the same rounded window region used by the real HWND.
            for y in 0..720 {
                for x in 0..420 {
                    let dx = (56.0_f32 - x as f32).max(x as f32 - 364.0).max(0.0);
                    let dy = (56.0_f32 - y as f32).max(y as f32 - 664.0).max(0.0);
                    if !(20..400).contains(&x)
                        || !(20..700).contains(&y)
                        || dx * dx + dy * dy > 36.0 * 36.0
                    {
                        pixels[(y * 420 + x) * 4 + 3] = 0;
                    }
                }
            }
            let mut encoder = png::Encoder::new(
                std::fs::File::create(destination).map_err(|e| e.to_string())?,
                420,
                720,
            );
            encoder.set_color(png::ColorType::Rgba);
            encoder.set_depth(png::BitDepth::Eight);
            encoder
                .write_header()
                .map_err(|e| e.to_string())?
                .write_image_data(&pixels)
                .map_err(|e| e.to_string())?;
            Ok(())
        }
    }

    fn bitmap(&mut self, op: &Op) -> Option<ID2D1Bitmap> {
        let key = format!("{}:{}:{}", op.w, op.h, op.source);
        if let Some(bitmap) = self.images.get(&key) {
            return Some(bitmap.clone());
        }
        let (width, height, mut pixels) = if op.kind == "svg" {
            let tree =
                resvg::usvg::Tree::from_str(&op.source, &resvg::usvg::Options::default()).ok()?;
            let width = (op.w * self.dpi).ceil() as u32;
            let height = (op.h * self.dpi).ceil() as u32;
            let mut pixmap = resvg::tiny_skia::Pixmap::new(width, height)?;
            resvg::render(
                &tree,
                resvg::tiny_skia::Transform::from_scale(
                    width as f32 / tree.size().width(),
                    height as f32 / tree.size().height(),
                ),
                &mut pixmap.as_mut(),
            );
            (width, height, pixmap.take())
        } else {
            let filename = op.source.rsplit('/').next()?.replace(".svg", ".png");
            if filename.contains('\\') || filename.contains("..") {
                return None;
            }
            let image = tauri::image::Image::from_path(self.flags.join(filename)).ok()?;
            let mut pixels = image.rgba().to_vec();
            for pixel in pixels.chunks_exact_mut(4) {
                for channel in 0..3 {
                    pixel[channel] = (pixel[channel] as u32 * pixel[3] as u32 / 255) as u8;
                }
            }
            (image.width(), image.height(), pixels)
        };
        for pixel in pixels.chunks_exact_mut(4) {
            pixel.swap(0, 2);
        }
        let properties = D2D1_BITMAP_PROPERTIES {
            pixelFormat: D2D1_PIXEL_FORMAT {
                format: DXGI_FORMAT_B8G8R8A8_UNORM,
                alphaMode: D2D1_ALPHA_MODE_PREMULTIPLIED,
            },
            dpiX: 96.0,
            dpiY: 96.0,
        };
        let bitmap = unsafe {
            self.target.CreateBitmap(
                D2D_SIZE_U { width, height },
                Some(pixels.as_ptr().cast()),
                width * 4,
                &properties,
            )
        }
        .ok()?;
        if self.images.len() >= 256 {
            self.images.clear();
        }
        self.images.insert(key, bitmap.clone());
        Some(bitmap)
    }
}

fn color(source: &str) -> D2D1_COLOR_F {
    let mut channels = [1.0, 1.0, 1.0, 1.0];
    if let Some(hex) = source.strip_prefix('#') {
        let expanded = if hex.len() == 3 {
            hex.chars().flat_map(|ch| [ch, ch]).collect::<String>()
        } else {
            hex.to_string()
        };
        if expanded.len() == 6 {
            if let Ok(number) = u32::from_str_radix(&expanded, 16) {
                channels = [
                    ((number >> 16) & 255) as f32 / 255.0,
                    ((number >> 8) & 255) as f32 / 255.0,
                    (number & 255) as f32 / 255.0,
                    1.0,
                ];
            }
        }
    } else if source.starts_with("rgba(") {
        let values: Vec<f32> = source
            .trim_start_matches("rgba(")
            .trim_end_matches(')')
            .split(',')
            .filter_map(|value| value.trim().parse().ok())
            .collect();
        if values.len() == 4 {
            channels = [
                values[0] / 255.0,
                values[1] / 255.0,
                values[2] / 255.0,
                values[3],
            ];
        }
    }
    D2D1_COLOR_F {
        r: channels[0],
        g: channels[1],
        b: channels[2],
        a: channels[3],
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn painter_keeps_particle_alpha_and_css_colors() {
        let particle = color("rgba(0,192,127,0.25)");
        assert_eq!(particle.a, 0.25);
        assert_eq!(particle.r, 0.0);
        assert_eq!(color("#fff").r, 1.0);
        assert_eq!(color("#09090b").b, 11.0 / 255.0);
    }
}
