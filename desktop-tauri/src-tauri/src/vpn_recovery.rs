use std::time::{Duration, Instant};

pub(crate) const MAX_RECOVERY_ATTEMPTS: usize = 3;

#[derive(Default)]
pub(crate) struct RecoveryState {
    attempts: usize,
    next_attempt: Option<Instant>,
}

impl RecoveryState {
    pub(crate) fn is_due(&self) -> bool {
        self.attempts < MAX_RECOVERY_ATTEMPTS
            && self
                .next_attempt
                .is_some_and(|deadline| Instant::now() >= deadline)
    }

    pub(crate) fn begin_attempt(&mut self) -> Option<usize> {
        if !self.is_due() {
            return None;
        }
        self.attempts += 1;
        self.next_attempt = None;
        Some(self.attempts)
    }

    pub(crate) fn schedule_after(&mut self, attempt: usize) {
        self.next_attempt = Some(Instant::now() + recovery_delay(attempt));
    }

    /// Coalesces repeated recovery triggers. A new external trigger may start a
    /// fresh bounded cycle only after the previous cycle exhausted its budget.
    pub(crate) fn request_now(&mut self) -> bool {
        if self.next_attempt.is_some() {
            return false;
        }
        if self.attempts >= MAX_RECOVERY_ATTEMPTS {
            self.attempts = 0;
        }
        self.next_attempt = Some(Instant::now());
        true
    }

    pub(crate) fn reset(&mut self) {
        *self = Self::default();
    }
}

pub(crate) fn recovery_delay(attempt: usize) -> Duration {
    match attempt {
        0 | 1 => Duration::from_secs(2),
        2 => Duration::from_secs(5),
        _ => Duration::from_secs(15),
    }
}

#[cfg(test)]
mod tests {
    use super::{recovery_delay, RecoveryState};
    use std::time::Duration;

    #[test]
    fn recovery_backoff_is_bounded() {
        assert_eq!(recovery_delay(1), Duration::from_secs(2));
        assert_eq!(recovery_delay(2), Duration::from_secs(5));
        assert_eq!(recovery_delay(3), Duration::from_secs(15));
    }

    #[test]
    fn repeated_triggers_are_coalesced() {
        let mut state = RecoveryState::default();

        assert!(state.request_now());
        assert!(!state.request_now());
        assert_eq!(state.begin_attempt(), Some(1));
    }
}
