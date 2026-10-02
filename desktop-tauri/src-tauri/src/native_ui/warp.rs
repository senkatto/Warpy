#[derive(Default)]
pub(super) struct Warp {
    started: Option<f64>,
    last: f64,
    seed: u32,
    stars: Vec<Star>,
}
struct Star {
    angle: f64,
    r: f64,
    previous: f64,
    speed: f64,
    width: f32,
    max: f64,
    color: [f32; 3],
}
pub(super) struct Trail {
    pub head: [f32; 2],
    pub tail: [f32; 2],
    pub width: f32,
    pub color: [f32; 4],
}
impl Warp {
    fn random(&mut self) -> f64 {
        self.seed ^= self.seed << 13;
        self.seed ^= self.seed >> 17;
        self.seed ^= self.seed << 5;
        self.seed as f64 / u32::MAX as f64
    }
    fn star(&mut self, initial: bool, upload: bool) -> Star {
        let angle = self.random() * std::f64::consts::TAU;
        let r = if initial {
            self.random() * 70. + 25.
        } else {
            self.random() * 8. + 28.
        };
        let tint = self.random();
        let color = if tint < 0.12 {
            if upload {
                [0.3, 0.64, 1.]
            } else {
                [0., 0.75, 0.5]
            }
        } else {
            [1.; 3]
        };
        Star {
            angle,
            r,
            previous: r,
            speed: self.random() * 0.8 + 0.3,
            width: (self.random() * 1.2 + 0.5) as f32,
            max: 90. + self.random() * 30.,
            color,
        }
    }
    pub(super) fn frame(&mut self, started: f64, now: f64, speed: f64, upload: bool) -> Vec<Trail> {
        if self.started != Some(started) {
            self.started = Some(started);
            self.last = now;
            self.seed = (started.to_bits() as u32) ^ 0x8ac7263d;
            self.stars.clear();
            for _ in 0..35 {
                let star = self.star(true, upload);
                self.stars.push(star);
            }
        }
        let frames = ((now - self.last) / 1000. * 60.).clamp(0., 4.);
        self.last = now;
        let factor = 0.5 + speed.clamp(0., 1.) * 4.5;
        let mut trails = Vec::with_capacity(35);
        for i in 0..self.stars.len() {
            let s = &mut self.stars[i];
            s.previous = s.r;
            s.r += s.speed * factor * frames;
            if s.r > s.max {
                let star = self.star(false, upload);
                self.stars[i] = star;
                continue;
            }
            if s.r < 30. {
                continue;
            }
            let tail = s.previous.max(30.);
            let alpha = ((s.r - 30.) / 18.)
                .min(1.)
                .min((1. - (s.r - (s.max - 18.)) / 18.).max(0.))
                * 0.85;
            trails.push(Trail {
                head: [
                    (100. + s.angle.cos() * s.r) as f32,
                    (100. + s.angle.sin() * s.r) as f32,
                ],
                tail: [
                    (100. + s.angle.cos() * tail) as f32,
                    (100. + s.angle.sin() * tail) as f32,
                ],
                width: s.width,
                color: [s.color[0], s.color[1], s.color[2], alpha as f32],
            });
        }
        trails
    }
}
