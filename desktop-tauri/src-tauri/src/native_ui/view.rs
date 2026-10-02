// The coordinates, artwork and type sizes intentionally match scene.js.
use super::{
    assets,
    config::{self, text},
    model::{Overlay, Ui},
    painter::{Hit, Op, Painter, Scene},
};
type Rect = [f32; 4];
macro_rules! r {
    ($x:expr,$y:expr,$w:expr,$h:expr) => {
        [$x as f32, $y as f32, $w as f32, $h as f32]
    };
}
fn measure(text: &str, size: f32, weight: i32) -> f32 {
    Painter::measure_text(text.into(), size, weight)
}
struct Builder<'a> {
    ui: &'a Ui,
    scene: Scene,
    now: f64,
    unix: u64,
}
impl Builder<'_> {
    fn rect(&mut self, r: Rect, color: &str, radius: impl Into<f64>, stroke: impl Into<f64>) {
        self.scene.ops.push(Op {
            kind: "rect".into(),
            x: r[0],
            y: r[1],
            w: r[2],
            h: r[3],
            color: color.into(),
            radius: radius.into() as f32,
            stroke: stroke.into() as f32,
            ..Default::default()
        });
    }
    #[allow(clippy::too_many_arguments)]
    fn l(
        &mut self,
        value: impl Into<String>,
        r: Rect,
        size: impl Into<f64>,
        color: &str,
        weight: i32,
        align: &str,
        wrap: bool,
    ) {
        self.scene.ops.push(Op {
            kind: "text".into(),
            text: value.into(),
            x: r[0],
            y: r[1],
            w: r[2],
            h: r[3],
            size: size.into() as f32,
            color: color.into(),
            weight,
            align: align.into(),
            wrap,
            ..Default::default()
        });
    }
    fn svg_source(&mut self, source: &str, r: Rect, color: &str) {
        if source.is_empty() {
            return;
        }
        let source = source.replace("currentColor", color);
        let source = if source.contains("xmlns=") {
            source
        } else {
            source.replacen("<svg ", "<svg xmlns=\"http://www.w3.org/2000/svg\" ", 1)
        };
        self.scene.ops.push(Op {
            kind: "svg".into(),
            source,
            x: r[0],
            y: r[1],
            w: r[2],
            h: r[3],
            ..Default::default()
        });
    }
    fn svg(&mut self, id: &str, r: Rect, color: &str) {
        self.svg_source(
            assets()["elements"][id]["svg"].as_str().unwrap_or(""),
            r,
            color,
        );
    }
    fn image(&mut self, source: String, r: Rect) {
        self.scene.ops.push(Op {
            kind: "image".into(),
            source,
            x: r[0],
            y: r[1],
            w: r[2],
            h: r[3],
            ..Default::default()
        });
    }
    fn hit(&mut self, id: &str, r: Rect, kind: &str) {
        self.scene.hits.push(Hit {
            id: id.into(),
            kind: kind.into(),
            x: r[0],
            y: r[1],
            w: r[2],
            h: r[3],
            ..Default::default()
        });
    }
    #[allow(clippy::too_many_arguments)]
    fn button(
        &mut self,
        id: &str,
        value: impl Into<String>,
        r: Rect,
        primary: bool,
        size: i32,
        radius: i32,
        disabled: bool,
    ) {
        let hover = self.ui.hover == id;
        self.rect(
            r,
            if disabled {
                "#1c1c1e"
            } else if primary {
                if hover {
                    "#e0e0e0"
                } else {
                    "#fff"
                }
            } else if hover {
                "#29292c"
            } else {
                "#1c1c1e"
            },
            radius,
            0,
        );
        if !primary && radius != 20 {
            self.rect(r, "#29292c", radius, 1);
        }
        self.l(
            value,
            r![r[0] + 8., r[1], r[2] - 16., r[3]],
            size,
            if disabled {
                "#555"
            } else if primary {
                "#000"
            } else {
                "#fff"
            },
            600,
            "center",
            false,
        );
        if !disabled {
            self.hit(id, r, "click");
        }
    }
    fn close(&mut self, id: &str, x: f32, y: f32) {
        self.l(
            if id == "close-tunneling" {
                "←"
            } else {
                "✕"
            },
            r![x, y, 24, 26],
            18,
            "#808082",
            400,
            "center",
            false,
        );
        self.hit(id, r![x, y, 24, 26], "click");
    }
    fn input(&mut self, id: &str, r: Rect, size: i32, placeholder: String) {
        let field = self.ui.field(id);
        self.rect(r, "#111113", 9, 0);
        self.rect(
            r,
            if self.ui.focus == id {
                "#00c07f"
            } else {
                "#29292c"
            },
            9,
            1,
        );
        let text = if field.value.is_empty() {
            placeholder
        } else {
            field.value.clone()
        };
        let text = if self.ui.focus == id && !field.read_only {
            let mut chars = field.value.chars().collect::<Vec<_>>();
            chars.insert(self.ui.caret.min(chars.len()), '│');
            chars.into_iter().collect()
        } else {
            text
        };
        let padding = if id == "share-link" {
            10.0
        } else if field.multiline {
            8.0
        } else {
            5.0
        };
        let x = if field.multiline { padding } else { 8.0 };
        let width = if field.multiline { padding * 2.0 } else { 16.0 };
        self.l(
            text,
            r![r[0] + x, r[1] + padding, r[2] - width, r[3] - padding * 2.],
            size,
            if field.value.is_empty() {
                "#626265"
            } else {
                "#fff"
            },
            400,
            if id == "s-mtu" { "center" } else { "left" },
            field.multiline,
        );
        self.scene.ops.last_mut().unwrap().break_all = id == "share-link";
        self.hit(id, r, "input");
    }
    fn toggle(&mut self, id: &str, x: f32, y: f32) {
        let checked = self.ui.field(id).checked;
        self.rect(
            r![x, y, 38, 20],
            if checked { "#00c07f" } else { "#242426" },
            10,
            0,
        );
        self.rect(
            r![x + if checked { 21. } else { 3. }, y + 3., 14, 14],
            "#fff",
            7,
            0,
        );
        self.hit(id, r![x - 5., y - 10., 48, 40], "toggle");
    }
    fn select(&mut self, id: &str, x: f32, y: f32, w: f32) {
        self.rect(r![x, y, w, 30], "#1c1c1e", 8, 0);
        self.rect(r![x, y, w, 30], "#303033", 8, 1);
        let suffix = match self.ui.field(id).value.as_str() {
            "only" => "Only",
            "bypass" => "Bypass",
            _ => "Off",
        };
        let key = format!(
            "{}{suffix}",
            if id == "s-apps-mode" { "apps" } else { "sites" }
        );
        self.l(
            self.ui.t(&key),
            r![x + 8., y, w - 35., 30],
            11,
            "#fff",
            400,
            "left",
            false,
        );
        self.l(
            "⌄",
            r![x + w - 29., y, 20, 30],
            18,
            "#ccc",
            600,
            "center",
            false,
        );
        self.hit(id, r![x, y, w, 30], "select");
    }
    fn clip(&mut self, id: &str, r: Rect, max: f32) {
        self.scene.scrolls.push(Hit {
            id: id.into(),
            x: r[0],
            y: r[1],
            w: r[2],
            h: r[3],
            max,
            ..Default::default()
        });
        self.scene.ops.push(Op {
            kind: "clip".into(),
            x: r[0],
            y: r[1],
            w: r[2],
            h: r[3],
            ..Default::default()
        });
    }
    fn unclip(&mut self) {
        self.scene.ops.push(Op {
            kind: "unclip".into(),
            ..Default::default()
        });
    }
    fn dim(&mut self, id: &str) {
        self.scene.hits.clear();
        self.scene.scrolls.clear();
        self.rect(r![20, 20, 380, 680], "rgba(0,0,0,.65)", 36, 0);
        self.hit(id, r![20, 20, 380, 680], "outside");
    }
    fn dialog(&mut self, id: &str, height: f32, width: f32, padding: f32, radius: f32) -> Rect {
        self.dim(id);
        let x = (420. - width) / 2.;
        let y = (720. - height) / 2.;
        self.rect(r![x, y, width, height], "#121214", radius, 0);
        self.hit("", r![x, y, width, height], "block");
        r![
            x + padding,
            y + padding,
            width - padding * 2.,
            height - padding * 2.
        ]
    }
    fn main(&mut self) {
        let connected = self.ui.connected();
        let connecting = self.ui.connecting();
        let empty = self.ui.profiles().is_empty();
        if connected || connecting {
            self.scene.ops.push(Op {
                kind: "particles".into(),
                x: 40.,
                y: 104.,
                started: self.ui.animation_start,
                connected_at: self.ui.connected_at,
                connected,
                ..Default::default()
            });
        } else {
            self.scene.ops.push(Op {
                kind: "ellipse".into(),
                x: 124.,
                y: 188.,
                w: 172.,
                h: 172.,
                color: "#1c1c1e".into(),
                ..Default::default()
            });
        }
        if empty {
            self.button(
                "power-btn",
                self.ui.t("addProfile"),
                r![124, 242, 172, 64],
                false,
                14,
                20,
                false,
            );
        } else {
            self.hit("power-btn", r![124, 188, 172, 172], "click");
            if !connected && !connecting {
                self.svg(
                    "power-btn",
                    r![186, 250, 48, 48],
                    if self.ui.status == "error" || !self.ui.error.is_empty() {
                        "#ff4d4d"
                    } else {
                        "#666"
                    },
                );
            }
            if connected {
                self.l(
                    if self.now < self.ui.alert_until {
                        self.ui.t("connected").to_uppercase()
                    } else {
                        self.ui.uptime(self.unix)
                    },
                    r![124, 258, 172, 32],
                    15,
                    "#00c07f",
                    600,
                    "center",
                    false,
                );
            }
            if connecting {
                self.svg_source("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 50 50\"><circle cx=\"25\" cy=\"25\" r=\"20\" fill=\"none\" stroke=\"white\" stroke-opacity=\".04\" stroke-width=\"3\"/></svg>",r![182,246,56,56],"");
                self.svg_source("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 50 50\"><circle cx=\"25\" cy=\"25\" r=\"20\" fill=\"none\" stroke=\"#ffa726\" stroke-linecap=\"round\" stroke-dasharray=\"90 150\" stroke-width=\"3\"/></svg>",r![182,246,56,56],"");
                self.scene.ops.last_mut().unwrap().spin = true;
            }
        }
        let info_height = if connected {
            139.
        } else if empty {
            49.
        } else {
            89.
        };
        let y = 444. + (680. - 424. - info_height - 79.) / 2. - 15.;
        let profile = self.ui.profile();
        let name = if connecting {
            self.ui.t("establishingTunnel")
        } else {
            profile
                .map(|p| text(p, "name").to_string())
                .unwrap_or_else(|| self.ui.t("addProfileHint"))
        };
        self.l(name, r![70, y, 280, 19], 14, "#99999b", 400, "center", true);
        if let Some(p) = profile {
            let chip = capitalize(text(p, "protocol"));
            let width = measure(&chip, 12., 500) + 22.;
            self.rect(r![210. - width / 2., y + 33., width, 26], "#363638", 10, 1);
            self.l(
                chip,
                r![210. - width / 2., y + 33., width, 26],
                12,
                "#b3b3b5",
                500,
                "center",
                false,
            );
        }
        if connected {
            self.l(
                format!("{} {}", self.ui.speed, self.ui.t("kbps")),
                r![70, y + 73., 280, 16],
                12,
                "#808082",
                500,
                "center",
                false,
            );
            self.l(
                format!(
                    "{} {}",
                    self.ui
                        .ping
                        .map(|p| p.to_string())
                        .unwrap_or_else(|| "—".into()),
                    self.ui.t("ms")
                ),
                r![70, y + 93., 280, 16],
                12,
                "#808082",
                500,
                "center",
                false,
            );
        }
        self.l(
            self.ui.error.clone(),
            r![70, y + info_height - 18., 280, 30],
            12,
            "#ff4d4d",
            400,
            "center",
            true,
        );
        self.rect(
            r![40, 621, 340, 55],
            if self.ui.hover == "btn-profiles" {
                "#252527"
            } else {
                "#1c1c1e"
            },
            18,
            0,
        );
        let protocol = profile.map(|p| text(p, "protocol")).unwrap_or("");
        let chip = capitalize(protocol);
        let chip_width = if chip.is_empty() {
            0.
        } else {
            measure(&chip, 11., 600) + 18.
        };
        if !chip.is_empty() {
            self.rect(r![56, 637, chip_width, 23], "#3d3d40", 8, 1);
            self.l(
                chip,
                r![56, 637, chip_width, 23],
                11,
                "#a0a0a2",
                600,
                "center",
                false,
            );
        }
        let mut x = 56.
            + if chip_width > 0. {
                chip_width + 12.
            } else {
                0.
            };
        let (name, country) = profile
            .map(|p| self.ui.profile_display(p))
            .unwrap_or_else(|| (self.ui.t("emptyMsg"), String::new()));
        if !country.is_empty() {
            self.image(format!("assets/flags/{country}.svg"), r![x, 642, 20, 14]);
            x += 28.;
        }
        self.l(
            name,
            r![x, 639, 340. - (x - 40.) - 36., 19],
            14,
            "#fff",
            600,
            "left",
            false,
        );
        self.svg("btn-profiles", r![344, 638.5, 20, 20], "#666");
        self.hit("btn-profiles", r![40, 621, 340, 55], "click");
    }
    fn top(&mut self) {
        if self.ui.open(Overlay::Settings) {
            return;
        }
        self.svg("logo", r![40, 47, 109, 20], "#fff");
        for (i, id) in [
            "btn-add",
            "btn-speed",
            "btn-settings",
            "win-min",
            "win-close",
        ]
        .iter()
        .enumerate()
        {
            let x = 178. + i as f32 * 42.;
            let hover = self.ui.hover == *id;
            self.rect(
                r![x, 40, 34, 34],
                if hover {
                    if *id == "win-close" {
                        "#ff4d4d"
                    } else {
                        "#2a2a2c"
                    }
                } else {
                    "#1c1c1e"
                },
                17,
                0,
            );
            self.svg(id, r![x + 9., 49, 16, 16], "#ccc");
            self.hit(id, r![x, 40, 34, 34], "click");
        }
    }
    fn settings(&mut self) {
        self.scene.hits.clear();
        self.scene.scrolls.clear();
        self.rect(r![20, 20, 380, 680], "#09090b", 36, 0);
        let tunneling = self.ui.tunneling;
        self.close(
            if tunneling {
                "close-tunneling"
            } else {
                "close-settings"
            },
            40.,
            46.,
        );
        self.l(
            self.ui.t(if tunneling {
                "routingSection"
            } else {
                "settings"
            }),
            r![80, 44, 290, 34],
            17,
            "#fff",
            600,
            "left",
            false,
        );
        let id = if tunneling {
            "settings-tunneling-page"
        } else {
            "settings-main-page"
        };
        let max = if tunneling { 0. } else { 23. };
        let scroll = self.ui.scroll.get(id).copied().unwrap_or(0.).clamp(0., max);
        self.clip(id, r![40, 98, 340, 505], max);
        let mut y = 98. - scroll;
        if !tunneling {
            self.rect(r![46, y + 14., 26, 26], "#0b211c", 8, 0);
            self.l(
                "⇄",
                r![46, y + 14., 26, 26],
                17,
                "#55dca7",
                400,
                "center",
                false,
            );
            self.l(
                self.ui.t("routingSection"),
                r![84, y + 10., 271, 16],
                12,
                "#55dca7",
                600,
                "left",
                false,
            );
            self.l(
                self.ui.t("routingDescription"),
                r![84, y + 29., 271, 14],
                10,
                "#626265",
                400,
                "left",
                false,
            );
            self.l(
                "›",
                r![364, y + 10., 12, 34],
                22,
                "#6b6b6e",
                400,
                "left",
                false,
            );
            self.hit("btn-open-tunneling", r![44, y, 332, 54], "click");
            y += 54.;
            for (id, key, help) in [
                ("s-resume-on-boot", "resumeOnBoot", ""),
                ("s-kill-switch", "killSwitch", "killSwitchTooltip"),
                ("s-lan", "lan", ""),
                ("s-quic", "quic", "quicTooltip"),
                ("s-mtu", "mtu", "mtuTooltip"),
            ] {
                let title = if key == "mtu" {
                    "MTU".into()
                } else {
                    self.ui.t(key)
                };
                let desc = self.ui.t(&format!("{key}Description"));
                let width = if id == "s-mtu" { 248. } else { 276. };
                let desc_height = if measure(&desc, 10., 400) > width {
                    28.
                } else {
                    14.
                };
                let label_height = if id == "s-kill-switch" {
                    48.
                } else {
                    38. + desc_height
                };
                let height = label_height + if id == "s-kill-switch" { 22. } else { 0. };
                let title_y = y + if id == "s-kill-switch" { 10.5 } else { 9. };
                self.rect(r![44, y, 332, 1], "#17171a", 0, 0);
                self.l(
                    title.clone(),
                    r![46, title_y, width, 16],
                    12,
                    "#55dca7",
                    600,
                    "left",
                    false,
                );
                self.l(
                    desc,
                    r![46, title_y + 19., width, desc_height],
                    10,
                    "#626265",
                    400,
                    "left",
                    true,
                );
                if id != "s-mtu" {
                    self.hit(id, r![44, y, 332, label_height], "toggle");
                }
                if !help.is_empty() {
                    let x = (46. + measure(&title, 12., 600) + 6.).min(316.);
                    self.rect(r![x, title_y, 16, 16], "#202023", 8, 0);
                    self.l(
                        "?",
                        r![x, title_y, 16, 16],
                        10,
                        "#858588",
                        700,
                        "center",
                        false,
                    );
                    self.hit(help, r![x - 2., title_y - 4., 20, 24], "help");
                }
                if id == "s-mtu" {
                    self.input(
                        id,
                        r![306, y + (label_height - 1. - 30.) / 2., 68, 30],
                        12,
                        String::new(),
                    );
                } else {
                    self.toggle(
                        id,
                        336.,
                        y + (label_height - 1. - 20.) / 2.
                            + if id == "s-kill-switch" { 3.5 } else { 0. },
                    );
                }
                if id == "s-kill-switch" {
                    let service = self.ui.kill_switch_status.as_str();
                    let key = if service == "Armed" {
                        "killSwitchArmed"
                    } else if service == "Suppressed:split-tunneling" {
                        "killSwitchSplitSuppressed"
                    } else if service.starts_with("Suppressed:") {
                        "killSwitchSuppressed"
                    } else if service.starts_with("Error:") {
                        "killSwitchError"
                    } else if self.ui.settings["killSwitch"] == true {
                        "killSwitchReady"
                    } else {
                        "killSwitchOff"
                    };
                    self.l(
                        self.ui.t(key),
                        r![46, y + 48., 328, 14],
                        10,
                        if service == "Armed" {
                            "#00c07f"
                        } else {
                            "#777"
                        },
                        400,
                        "left",
                        false,
                    );
                }
                y += height;
            }
            self.rect(r![44, y + 17., 332, 1], "#1b1b1e", 0, 0);
            y += 26.;
            self.rect(r![44, y, 332, 1], "#1b1b1e", 0, 0);
            self.svg("btn-language", r![46, y + 14., 20, 20], "#858588");
            self.l(
                self.ui.t("lang"),
                r![80, y + 16., 180, 16],
                12,
                "#55dca7",
                600,
                "left",
                false,
            );
            self.l(
                if self.ui.field("s-lang").value == "ru" {
                    "Русский"
                } else {
                    "English"
                },
                r![260, y + 16., 72, 16],
                11,
                "#808083",
                400,
                "right",
                false,
            );
            self.svg(
                "language-setting-chevron",
                r![344, y + 16., 16, 16],
                "#6b6b6e",
            );
            self.hit("btn-language", r![44, y, 332, 48], "click");
            self.rect(r![44, y + 47., 332, 1], "#1b1b1e", 0, 0);
            y += 64.;
            self.rect(r![44, y, 332, 40], "#1c1c1e", 10, 0);
            self.rect(r![44, y, 332, 40], "#303033", 10, 1);
            let label = self.update_label();
            let width = measure(&label, 12., 600);
            self.svg(
                "btn-check-update",
                r![210. - (width + 24.) / 2., y + 12.5, 15, 15],
                "#ddd",
            );
            self.l(
                label,
                r![210. - (width + 24.) / 2. + 24., y, width, 40],
                12,
                "#fff",
                600,
                "left",
                false,
            );
            if !self.ui.busy.contains("update") && !self.ui.update_installing {
                self.hit("btn-check-update", r![44, y, 332, 40], "click");
            }
            self.l(
                self.ui.update_status.clone(),
                r![44, y + 40., 332, 14],
                10,
                "#808083",
                400,
                "left",
                true,
            );
        } else {
            for kind in ["apps", "sites"] {
                let id = format!("s-{kind}-mode");
                self.l(
                    self.ui.t(kind),
                    r![46, y + 18., 150, 16],
                    12,
                    "#55dca7",
                    600,
                    "left",
                    false,
                );
                self.select(&id, 230., y + 11., 144.);
                y += 49.;
                if kind == "apps" {
                    self.button(
                        "btn-app-browse",
                        self.ui.t("browse"),
                        r![46, y, 160, 28],
                        false,
                        11,
                        10,
                        false,
                    );
                    self.button(
                        "btn-app-running",
                        self.ui.t("running"),
                        r![214, y, 160, 28],
                        false,
                        11,
                        10,
                        false,
                    );
                    y += 44.;
                }
                self.input(
                    &format!("s-{kind}-list"),
                    r![46, y, 328, 54],
                    11,
                    self.ui.t(&format!("{kind}ListPlaceholder")),
                );
                y += 66.;
                self.rect(r![44, y, 332, 1], "#17171a", 0, 0);
            }
        }
        self.unclip();
        self.button(
            "btn-save-settings",
            self.ui.t("saveBtn"),
            r![40, 615, 340, 37],
            true,
            13,
            10,
            !self.ui.dirty_settings() || self.ui.busy.contains("save"),
        );
        self.l(
            format!("v{}", self.ui.version),
            r![40, 666, 340, 14],
            10,
            "#555558",
            400,
            "center",
            false,
        );
    }
    fn update_label(&self) -> String {
        if self.ui.update_installing {
            self.ui.t("updateInstalling")
        } else if self.ui.busy.contains("update") {
            self.ui.t("updateChecking")
        } else if let Some(update) = &self.ui.update {
            format!("{} {}", self.ui.t("updateInstall"), text(update, "version"))
        } else {
            self.ui.t("checkUpdates")
        }
    }
    fn profiles(&mut self) {
        self.dim("overlay-profiles");
        let groups = profile_groups(self.ui);
        let mut rows = Vec::new();
        if let Some(group) = &self.ui.group {
            for (i, p) in self.ui.profiles().iter().enumerate() {
                if text(p, "group") == group {
                    rows.push((
                        format!("select-profile:{i}"),
                        Some(p),
                        vec![
                            (format!("share-profile:{i}"), SHARE),
                            (format!("delete-profile:{i}"), DELETE),
                        ],
                        self.ui.active() == i,
                        self.ui.profile_display(p).0,
                        self.ui.profile_display(p).1,
                    ));
                }
            }
        } else {
            for (name, indexes) in &groups {
                let share = self.ui.settings["subscriptions"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .find(|s| {
                        indexes.iter().all(|i| {
                            text(&self.ui.profiles()[*i], "subscriptionId") == text(s, "id")
                        })
                    });
                let mut actions = Vec::new();
                if share.is_some() {
                    actions.push((format!("share-group:{name}"), SHARE));
                }
                actions.push((format!("delete-group:{name}"), DELETE));
                actions.push((format!("open-group:{name}"), ARROW));
                rows.push((
                    format!("open-group:{name}"),
                    None,
                    actions,
                    indexes.contains(&self.ui.active()),
                    name.clone(),
                    String::new(),
                ));
            }
            for (i, p) in self.ui.profiles().iter().enumerate() {
                if text(p, "group").is_empty() {
                    let (name, country) = self.ui.profile_display(p);
                    rows.push((
                        format!("select-profile:{i}"),
                        Some(p),
                        vec![
                            (format!("share-profile:{i}"), SHARE),
                            (format!("delete-profile:{i}"), DELETE),
                        ],
                        self.ui.active() == i,
                        name,
                        country,
                    ));
                }
            }
        }
        let height = (84. + rows.len().max(1) as f32 * 66.).min(480.);
        let y = 700. - height;
        self.rect(r![20, y, 380, height + 28.], "#121214", 28, 0);
        self.hit("", r![20, y, 380, height], "block");
        let title = self
            .ui
            .group
            .clone()
            .unwrap_or_else(|| self.ui.t("profilesTitle"));
        self.l(
            title.clone(),
            r![42, y + 22., 145, 34],
            17,
            "#fff",
            600,
            "left",
            false,
        );
        if self.ui.group.is_none() {
            let add = self.ui.t("addProfile");
            self.button(
                "profiles-add-btn",
                add.clone(),
                r![
                    42. + measure(&title, 17., 600) + 10.,
                    y + 25.,
                    measure(&add, 11., 600) + 18.,
                    29
                ],
                false,
                11,
                10,
                false,
            );
            self.close("close-profiles", 354., y + 25.);
        } else {
            self.button(
                "profiles-back-btn",
                self.ui.t("back"),
                r![290, y + 25., 66, 30],
                false,
                11,
                10,
                false,
            );
        }
        let top = y + 70.;
        let bottom = 678.;
        let max = (rows.len().max(1) as f32 * 66. - (bottom - top)).max(0.);
        let scroll = self
            .ui
            .scroll
            .get("profile-list")
            .copied()
            .unwrap_or(0.)
            .clamp(0., max);
        self.clip("profile-list", r![42, top, 338, bottom - top], max);
        if rows.is_empty() {
            self.l(
                self.ui.t("emptyMsg"),
                r![42, top + 24., 339, 18],
                13,
                "#59595b",
                400,
                "center",
                false,
            );
        }
        for (i, (id, profile, actions, active, title, country)) in rows.iter().enumerate() {
            let ry = top + i as f32 * 66. - scroll;
            self.rect(
                r![42, ry, 339, 58],
                if self.ui.hover == *id {
                    "#1a1a1d"
                } else {
                    "#121214"
                },
                18,
                0,
            );
            self.rect(r![42, ry, 339, 58], "#1d1d20", 18, 1);
            let mut x = 57.;
            if *active {
                self.rect(r![x + 6., ry + 24., 9, 9], "#00c07f", 4.5, 0);
                x += 35.;
            }
            if !country.is_empty() {
                self.image(
                    format!("assets/flags/{country}.svg"),
                    r![x, ry + 22., 20, 14],
                );
                x += 28.;
            }
            let width = 381. - x - 15. - actions.len() as f32 * 30. + 6.;
            self.l(
                title.clone(),
                r![x, ry + 12.5, width, 17],
                13,
                "#e0e0e1",
                600,
                "left",
                false,
            );
            let (details, unavailable) = if let Some(p) = profile {
                let probe = self.ui.probes.get(&config::profile_key(p));
                let probe_text = if let Some(probe) = probe {
                    if probe.checking {
                        if text(&self.ui.settings, "lang") == "ru" {
                            "проверка…".into()
                        } else {
                            "checking…".into()
                        }
                    } else if let Some(delay) = probe.delay {
                        format!("{delay} ms")
                    } else {
                        if text(&self.ui.settings, "lang") == "ru" {
                            "недоступен".into()
                        } else {
                            "unavailable".into()
                        }
                    }
                } else {
                    if text(&self.ui.settings, "lang") == "ru" {
                        "не проверен".into()
                    } else {
                        "not checked".into()
                    }
                };
                (
                    format!(
                        "{} · {}:{} · {probe_text}",
                        text(p, "protocol"),
                        text(p, "host"),
                        p["port"]
                    ),
                    probe.is_some_and(|p| !p.checking && p.delay.is_none()),
                )
            } else {
                let count = groups
                    .iter()
                    .find(|(name, _)| name == title)
                    .map(|(_, i)| i.len())
                    .unwrap_or(0);
                (self.ui.count_label(count), false)
            };
            self.l(
                details.to_uppercase(),
                r![x, ry + 31.5, width, 14],
                10,
                if unavailable { "#ff5d62" } else { "#666669" },
                600,
                "left",
                false,
            );
            if ry + 58. > top && ry < bottom {
                self.hit(
                    id,
                    r![42, top.max(ry), 339, bottom.min(ry + 58.) - top.max(ry)],
                    "click",
                );
            }
            for (j, (action, svg)) in actions.iter().enumerate() {
                let ax = 381. - 9. - (actions.len() - j) as f32 * 30.;
                self.svg_source(svg, r![ax + 4., ry + 21., 16, 16], "#777");
                if action.starts_with("open-group:") {
                    self.scene.ops.last_mut().unwrap().rotation = -std::f32::consts::FRAC_PI_2;
                }
                if ry + 58. > top && ry < bottom {
                    self.hit(
                        action,
                        r![
                            ax,
                            top.max(ry + 14.),
                            26,
                            (bottom.min(ry + 44.) - top.max(ry + 14.)).max(0.)
                        ],
                        "click",
                    );
                }
            }
        }
        self.unclip();
        if max > 0. {
            self.rect(
                r![378, top + scroll / max * (bottom - top - 40.), 3, 40],
                "#303033",
                1.5,
                0,
            );
        }
    }
    fn dialogs(&mut self) {
        if self.ui.open(Overlay::Add) {
            let [x, y, w, _] = self.dialog("overlay-add", 273., 340., 20., 20.);
            self.l(
                self.ui.t("addProfile"),
                r![x, y, w, 22],
                17,
                "#fff",
                600,
                "left",
                false,
            );
            self.l(
                self.ui.t("clipboardHint"),
                r![x, y + 28., w, 36],
                12,
                "#777",
                400,
                "left",
                true,
            );
            self.rect(r![x, y + 72., w, 106], "#0e1b17", 14, 0);
            self.rect(r![x, y + 72., w, 106], "#174c36", 14, 1);
            self.svg(
                "btn-clipboard-import",
                r![x + w / 2. - 12., y + 97., 24, 24],
                "#00c07f",
            );
            let busy = self.ui.busy.contains("import");
            self.l(
                self.ui.t(if busy {
                    "importLoading"
                } else {
                    "addClipboardBtn"
                }),
                r![x + 8., y + 133., w - 16., 16],
                14,
                "#00c07f",
                600,
                "center",
                false,
            );
            if !busy {
                self.hit("btn-clipboard-import", r![x, y + 72., w, 106], "click");
            }
            self.button(
                "cancel-add",
                self.ui.t("cancel"),
                r![x, y + 194., w, 39],
                false,
                13,
                10,
                false,
            );
        }
        if self.ui.open(Overlay::Share) {
            let [x, y, w, _] = self.dialog("overlay-share", 449., 340., 20., 20.);
            self.l(
                self.ui.t("shareTitle"),
                r![x, y, w - 28., 24],
                17,
                "#fff",
                600,
                "left",
                false,
            );
            self.close("close-share", x + w - 24., y);
            self.rect(r![123, y + 44., 174, 174], "#fff", 12, 0);
            self.svg_source(&self.ui.qr, r![135, y + 56., 150, 150], "");
            self.input("share-link", r![x, y + 230., w, 130], 11, String::new());
            self.button(
                "btn-copy-share",
                self.ui.t("copy"),
                r![x, y + 372., w, 37],
                true,
                13,
                10,
                false,
            );
        }
        if self.ui.open(Overlay::Language) {
            let [x, y, w, _] = self.dialog("overlay-language", 162., 300., 18., 20.);
            self.l(
                self.ui.t("lang"),
                r![x, y, w - 28., 24],
                17,
                "#fff",
                600,
                "left",
                false,
            );
            self.close("close-language", x + w - 24., y);
            for (i, (lang, label)) in [("ru", "Русский"), ("en", "English")].iter().enumerate()
            {
                let ry = y + 38. + i as f32 * 46.;
                let selected = self.ui.field("s-lang").value == *lang;
                self.rect(
                    r![x, ry, w, 42],
                    if selected { "#10291f" } else { "#121214" },
                    9,
                    0,
                );
                self.l(
                    *label,
                    r![x + 12., ry, w - 38., 42],
                    12,
                    "#ddd",
                    400,
                    "left",
                    false,
                );
                if selected {
                    self.l(
                        "✓",
                        r![x + w - 28., ry, 20, 42],
                        14,
                        "#00c07f",
                        700,
                        "left",
                        false,
                    );
                }
                self.hit(&format!("language:{lang}"), r![x, ry, w, 42], "click");
            }
        }
        if self.ui.open(Overlay::Running) {
            let processes = self.ui.visible_processes();
            let message = if !self.ui.processes_message.is_empty() {
                self.ui.processes_message.clone()
            } else if processes.is_empty() {
                self.ui.t("nothingFound")
            } else {
                String::new()
            };
            let height = if message.is_empty() {
                (18. + processes.len() as f32 * 40.).min(251.)
            } else {
                74.
            };
            let [x, y, w, _] = self.dialog("overlay-running-apps", 249. + height, 350., 20., 20.);
            self.l(
                self.ui.t("runningAppsTitle"),
                r![x, y, w - 28., 24],
                17,
                "#fff",
                600,
                "left",
                false,
            );
            self.close("close-running-apps", x + w - 24., y);
            self.input(
                "running-apps-search",
                r![x, y + 38., w, 34],
                12,
                self.ui.t("searchPlaceholder"),
            );
            self.l(
                self.ui.t("excludeSystem"),
                r![x, y + 96., w - 48., 16],
                12,
                "#aaa",
                400,
                "left",
                false,
            );
            self.toggle("running-apps-exclude-system", x + w - 38., y + 94.);
            let top = y + 140.;
            self.rect(r![x, top, w, height], "#0f0f11", 12, 0);
            self.rect(r![x, top, w, height], "#1f1f22", 12, 1);
            let max = (if message.is_empty() {
                processes.len() as f32 * 40.
            } else {
                40.
            } + 18.
                - height)
                .max(0.);
            let scroll = self
                .ui
                .scroll
                .get("running-apps-list")
                .copied()
                .unwrap_or(0.)
                .clamp(0., max);
            self.clip("running-apps-list", r![x, top, w, height], max);
            if !message.is_empty() {
                self.l(
                    message,
                    r![x + 8., top + 9. - scroll, w - 16., 56],
                    12,
                    "#777",
                    400,
                    "center",
                    true,
                );
            } else {
                for (i, process) in processes.iter().enumerate() {
                    let ry = top + 9. + i as f32 * 40. - scroll;
                    let checked = self
                        .ui
                        .selected_processes
                        .iter()
                        .any(|selected| selected.eq_ignore_ascii_case(process));
                    self.rect(
                        r![x + 21., ry + 10., 16, 16],
                        if checked { "#00c07f" } else { "#303033" },
                        3,
                        0,
                    );
                    if checked {
                        self.l(
                            "✓",
                            r![x + 21., ry + 8., 16, 20],
                            12,
                            "#fff",
                            700,
                            "center",
                            false,
                        );
                    }
                    self.l(
                        process.clone(),
                        r![x + 49., ry, w - 70., 36],
                        12,
                        "#ddd",
                        400,
                        "left",
                        false,
                    );
                    if ry >= top && ry + 36. <= top + height {
                        self.hit(&format!("process:{process}"), r![x, ry, w, 36], "click");
                    }
                }
            }
            self.unclip();
            self.button(
                "cancel-running-apps",
                self.ui.t("cancel"),
                r![x, top + height + 30., (w - 10.) / 2., 39],
                false,
                13,
                10,
                false,
            );
            self.button(
                "confirm-running-apps",
                self.ui.t("confirm"),
                r![x + (w + 10.) / 2., top + height + 30., (w - 10.) / 2., 39],
                true,
                13,
                10,
                false,
            );
        }
        if self.ui.open(Overlay::Speed) {
            self.speedtest();
        }
        for (overlay, id, width) in [
            (Overlay::Confirm, "overlay-confirm", 280.),
            (Overlay::Message, "overlay-message", 280.),
            (Overlay::Unsaved, "overlay-settings-unsaved", 330.),
        ] {
            if !self.ui.open(overlay) {
                continue;
            }
            let message = match overlay {
                Overlay::Confirm => self.ui.confirm.clone(),
                Overlay::Message => self.ui.message.clone(),
                _ => self.ui.t("unsavedSettings"),
            };
            let lines = (measure(&message, 13., 400) / (width - 42.)).ceil().max(1.);
            let text_height = lines * 19.5;
            let button_height = if overlay == Overlay::Unsaved {
                33.
            } else {
                32.
            };
            let [x, y, w, _] =
                self.dialog(id, 42. + text_height + 30. + button_height, width, 21., 20.);
            self.l(
                message,
                r![x, y, w, text_height],
                13,
                "#f2f2f2",
                400,
                "center",
                true,
            );
            if overlay == Overlay::Message {
                self.button(
                    "btn-message-ok",
                    self.ui.t("ok"),
                    r![210. - 66., y + text_height + 30., 132, 32],
                    true,
                    12,
                    10,
                    false,
                );
            } else {
                let buttons = if overlay == Overlay::Confirm {
                    vec![("btn-confirm-cancel", "cancel"), ("btn-confirm-ok", "yes")]
                } else {
                    vec![
                        ("settings-unsaved-cancel", "cancel"),
                        ("settings-unsaved-discard", "discardChanges"),
                        ("settings-unsaved-save", "saveBtn"),
                    ]
                };
                for (i, (id, key)) in buttons.iter().enumerate() {
                    self.button(
                        id,
                        self.ui.t(key),
                        r![
                            x + i as f32 * (w + 8.) / buttons.len() as f32,
                            y + text_height + 30.,
                            (w - (buttons.len() - 1) as f32 * 8.) / buttons.len() as f32,
                            button_height
                        ],
                        i == buttons.len() - 1,
                        if overlay == Overlay::Unsaved { 11 } else { 12 },
                        10,
                        false,
                    );
                }
            }
        }
    }
    fn speedtest(&mut self) {
        let [x, y, w, _] = self.dialog("overlay-speedtest", 353., 320., 24., 28.);
        if let Some(results) = self.ui.speedtest.results {
            for (i, kind) in ["down", "up", "ping"].iter().enumerate() {
                let unit = self.ui.t(if *kind == "ping" { "ms" } else { "mbps" });
                let width = measure(&unit, 14., 400);
                let rx = 210. - (92. + width) / 2.;
                let ry = y + 55.5 + i as f32 * 53.;
                let color = match *kind {
                    "down" => "#00c07f",
                    "up" => "#4da3ff",
                    _ => "#ffa726",
                };
                self.svg_source(
                    assets()["elements"][&format!("speedtest-icon-{kind}")]["svg"]
                        .as_str()
                        .unwrap(),
                    r![rx, ry + 15., 14, 14],
                    "#fff",
                );
                self.l(
                    format!("{:.0}", results[i]),
                    r![rx + 36., ry, 48, 35],
                    26,
                    color,
                    700,
                    "left",
                    false,
                );
                self.l(
                    unit,
                    r![rx + 92., ry + 14., width, 19],
                    14,
                    "#fff",
                    400,
                    "left",
                    false,
                );
            }
        } else {
            if self.ui.speedtest.running {
                self.scene.ops.push(Op {
                    kind: "warp".into(),
                    x: 110.,
                    y: y + 26.,
                    started: self.ui.speedtest.started,
                    color: self.ui.speedtest.kind.clone(),
                    size: self.ui.speedtest.progress as f32,
                    ..Default::default()
                });
            }
            let color = if self.ui.speedtest.kind == "failed" {
                "#ff575f"
            } else if self.ui.speedtest.kind == "upload" {
                "#4da3ff"
            } else if self.ui.speedtest.kind.is_empty() {
                "#999"
            } else {
                "#00c07f"
            };
            self.l(
                self.ui.speedtest.stage.clone(),
                r![x, y + 103.5, w, 9],
                9,
                color,
                700,
                "center",
                false,
            );
            self.l(
                self.ui.speedtest.value.clone(),
                r![x, y + 116.5, w, 20],
                20,
                "#fff",
                700,
                "center",
                false,
            );
            self.l(
                self.ui.speedtest.unit.clone(),
                r![x, y + 139.5, w, 9],
                9,
                "#777",
                600,
                "center",
                false,
            );
        }
        let close = self.ui.t("close");
        let action = self.ui.t(if self.ui.speedtest.running {
            "speedtestRunning"
        } else {
            "speedtestBtnStart"
        });
        let close_width = measure(&close, 13., 600) + 22.;
        let action_width = measure(&action, 13., 600) + 20.;
        let bx = 210. - (close_width + action_width + 10.) / 2.;
        self.button(
            "speedtest-btn-close",
            close,
            r![bx, y + 266., close_width, 39],
            false,
            13,
            10,
            false,
        );
        self.button(
            "speedtest-btn-action",
            action,
            r![bx + close_width + 10., y + 266., action_width, 39],
            true,
            13,
            10,
            self.ui.speedtest.running,
        );
    }
    fn update_banner(&mut self) {
        if let Some(update) = &self.ui.update {
            if text(update, "version") == self.ui.dismissed_update {
                return;
            }
            self.rect(r![40, 90, 340, 72], "#17191a", 14, 0);
            self.l(
                if self.ui.update_installing {
                    self.ui.t("updateInstalling")
                } else {
                    format!(
                        "{} {}",
                        self.ui.t(if update["rollback"] == true {
                            "updateRollbackAvailable"
                        } else {
                            "updateAvailable"
                        }),
                        text(update, "version")
                    )
                },
                r![53, 101, 190, 24],
                12,
                "#fff",
                600,
                "left",
                false,
            );
            self.l(
                if self.ui.update_installing {
                    self.ui
                        .update_percent
                        .map(|percent| format!("{percent}%"))
                        .unwrap_or_else(|| self.ui.t("updateRestartNotice"))
                } else {
                    self.ui.t("updateRestartNotice")
                },
                r![53, 125, 190, 26],
                10,
                "#888",
                400,
                "left",
                true,
            );
            self.button(
                "update-banner-later",
                self.ui.t("updateLater"),
                r![246, 108, 52, 30],
                false,
                10,
                10,
                false,
            );
            self.button(
                "update-banner-install",
                self.ui.t("updateInstall"),
                r![304, 108, 62, 30],
                true,
                10,
                10,
                false,
            );
        }
    }
    fn floating(&mut self) {
        if let Some((id, r)) = &self.ui.select {
            self.rect(r![r[0], r[1], r[2], 96], "#242427", 8, 0);
            for (i, (value, suffix)) in [("off", "Off"), ("only", "Only"), ("bypass", "Bypass")]
                .iter()
                .enumerate()
            {
                let key = format!(
                    "{}{suffix}",
                    if id == "s-apps-mode" { "apps" } else { "sites" }
                );
                self.l(
                    self.ui.t(&key),
                    r![r[0] + 8., r[1] + i as f32 * 32., r[2] - 16., 32],
                    11,
                    "#fff",
                    400,
                    "left",
                    false,
                );
                self.hit(
                    &format!("option:{id}:{value}"),
                    r![r[0], r[1] + i as f32 * 32., r[2], 32],
                    "option",
                );
            }
        }
        if !self.ui.tooltip.is_empty() {
            self.rect(r![60, 558, 300, 76], "#29292c", 10, 0);
            self.l(
                self.ui.tooltip.clone(),
                r![72, 566, 276, 60],
                12,
                "#eee",
                400,
                "left",
                true,
            );
        }
    }
}
fn capitalize(value: &str) -> String {
    value
        .chars()
        .next()
        .map(|first| format!("{}{}", first.to_uppercase(), &value[first.len_utf8()..]))
        .unwrap_or_default()
}
const SHARE:&str="<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><circle cx=\"18\" cy=\"5\" r=\"3\"></circle><circle cx=\"6\" cy=\"12\" r=\"3\"></circle><circle cx=\"18\" cy=\"19\" r=\"3\"></circle><line x1=\"8.59\" y1=\"13.51\" x2=\"15.42\" y2=\"17.49\"></line><line x1=\"15.41\" y1=\"6.51\" x2=\"8.59\" y2=\"10.49\"></line></svg>";
const DELETE:&str="<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><polyline points=\"3 6 5 6 21 6\"></polyline><path d=\"M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2\"></path></svg>";
const ARROW:&str="<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><polyline points=\"6 9 12 15 18 9\"></polyline></svg>";
pub(super) fn profile_groups(ui: &Ui) -> Vec<(String, Vec<usize>)> {
    let mut groups: Vec<(String, Vec<usize>)> = Vec::new();
    for (i, p) in ui.profiles().iter().enumerate() {
        let group = text(p, "group");
        if group.is_empty() {
            continue;
        }
        if let Some((_, indexes)) = groups.iter_mut().find(|(name, _)| name == group) {
            indexes.push(i);
        } else {
            groups.push((group.into(), vec![i]));
        }
    }
    groups
}

pub(super) fn build(ui: &Ui, now: f64, unix: u64) -> Scene {
    let mut b = Builder {
        ui,
        scene: Scene::default(),
        now,
        unix,
    };
    b.rect(r![20, 20, 380, 680], "#09090b", 36, 0);
    if !ui.open(Overlay::Settings) {
        b.main();
    }
    b.top();
    b.update_banner();
    if ui.open(Overlay::Profiles) {
        b.profiles();
    }
    if ui.open(Overlay::Settings) {
        b.settings();
    }
    b.dialogs();
    b.floating();
    b.scene
}
