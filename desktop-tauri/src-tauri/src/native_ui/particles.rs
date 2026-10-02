// The same 86 particles and motion equations as index.js, evaluated without
// rebuilding the controller scene or transferring JSON on animation frames.
use std::sync::LazyLock;
use serde::Serialize;

struct Particle {
    rx: f64, ry: f64, intro_delay: f64, cycle: f64, pull_delay: f64,
    radius: f64, edge_offset: f64, end_radius: f64, wobble: f64,
    phase: f64, second_phase: f64, color_delay: f64, color: usize,
}

#[derive(Serialize)]
pub(super) struct Dot { pub x: f64, pub y: f64, pub radius: f64, pub color: [f64; 4] }

static PARTICLES: LazyLock<[Particle; 86]> = LazyLock::new(|| std::array::from_fn(|i| {
    let i = i as u32;
    let angle = (random(i*i+19,11)*360.0).to_radians();
    Particle {
        rx: angle.cos(), ry: angle.sin(), intro_delay: random(i*17+5,41)*0.42,
        cycle: 1.85+random(i*13+7,29)*1.15, pull_delay: random(i*31+9,47)*0.42,
        radius: 1.4+(i%4) as f64*0.35, edge_offset: -12.0+random(i,37)*24.0,
        end_radius: 15.0+random(i,51)*12.0, wobble: 0.8+random(i,83)*3.5,
        phase: random(i,89)*360.0, second_phase: random(i,103)*360.0,
        color_delay: random(i*23+3,71)*0.18, color: (i%3) as usize,
    }
}));

fn random(index: u32, salt: u32) -> f64 {
    (((index+1).wrapping_mul(1103515245).wrapping_add(salt*12345)&0x7fffffff)%1000) as f64/1000.0
}

pub(super) fn frame(elapsed: f64, connected_at: Option<f64>, connected: bool) -> impl Iterator<Item=Dot> {
    let pull_started = connected_at.map(|at| at+0.18);
    let color_progress = if connected { pull_started.map(|at| ((elapsed-at)/0.76).clamp(0.0,1.0)).unwrap_or(0.0) } else { 0.0 };
    const START: [[f64;3];3] = [[255.0;3],[221.0;3],[170.0;3]];
    const END: [[f64;3];3] = [[0.0,192.0,127.0],[68.0,215.0,164.0],[0.0,144.0,94.0]];
    PARTICLES.iter().map(move |p| {
        let intro_alpha = ((elapsed-p.intro_delay).max(0.0)/0.2).min(1.0);
        let pull_elapsed = pull_started.map(|at| elapsed-at-p.pull_delay).unwrap_or(-1.0);
        let pulling = pull_started.is_some() && pull_elapsed>0.0;
        let local = if pulling { (pull_elapsed/p.cycle)%1.0 } else { 0.0 };
        let fade_in = if !pulling || pull_elapsed<p.cycle { 1.0 } else { (local/0.1).min(1.0) };
        let fade_out = if pulling { ((1.0-local)/0.22).min(1.0) } else { 1.0 };
        let blink = 0.78+0.22*(p.phase+elapsed*80.0).to_radians().sin();
        let progress = ((color_progress-p.color_delay)/(1.0-p.color_delay)).clamp(0.0,1.0);
        let pull = if pulling { ((local-0.34)/0.66).clamp(0.0,1.0) } else { 0.0 };
        let accelerated = pull.powi(4);
        let start = 90.0+p.edge_offset;
        let travel = start+(p.end_radius-start)*accelerated;
        let tangent = (p.wobble+(1.0-pull)*4.2)*(p.phase+elapsed*65.0).to_radians().sin();
        let radial = p.wobble*0.25*(p.second_phase+elapsed*55.0).to_radians().sin();
        let mut color = [0.0;4];
        for channel in 0..3 { color[channel]=(START[p.color][channel]+(END[p.color][channel]-START[p.color][channel])*progress).round()/255.0; }
        color[3]=(0.64+(0.82-0.64)*progress)*intro_alpha*fade_in*fade_out*blink;
        Dot { x:170.0+p.rx*travel-p.ry*tangent+p.rx*radial,
            y:170.0+p.ry*travel+p.rx*tangent+p.ry*radial,
            radius:p.radius*(1.0-accelerated*0.67)*(0.8+0.35*blink), color }
    })
}
