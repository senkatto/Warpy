#![cfg(windows)]

use std::{
    ffi::c_void,
    ptr,
    sync::mpsc::SyncSender,
    time::{Duration, Instant},
};
use windows_sys::Win32::{
    Foundation::{HANDLE, NO_ERROR},
    NetworkManagement::IpHelper::{
        CancelMibChangeNotify2, NotifyIpInterfaceChange, MIB_IPINTERFACE_ROW, MIB_NOTIFICATION_TYPE,
    },
    Networking::WinSock::AF_UNSPEC,
};

const NETWORK_SETTLE_DELAY: Duration = Duration::from_secs(2);
const RESUME_SETTLE_DELAY: Duration = Duration::from_secs(3);
const UNLOCK_SETTLE_DELAY: Duration = Duration::from_millis(750);
const MAX_SETTLE_DELAY: Duration = Duration::from_secs(5);
const IDLE_WAIT: Duration = Duration::from_secs(1);

const TRIGGER_NETWORK: u8 = 1;
const TRIGGER_RESUME: u8 = 2;
const TRIGGER_UNLOCK: u8 = 4;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ConnectivityEvent {
    NetworkChanged,
    Resume,
    Unlock,
    Stop,
}

impl ConnectivityEvent {
    fn settle_delay(self) -> Duration {
        match self {
            Self::NetworkChanged => NETWORK_SETTLE_DELAY,
            Self::Resume => RESUME_SETTLE_DELAY,
            Self::Unlock => UNLOCK_SETTLE_DELAY,
            Self::Stop => Duration::ZERO,
        }
    }

    fn trigger_mask(self) -> u8 {
        match self {
            Self::NetworkChanged => TRIGGER_NETWORK,
            Self::Resume => TRIGGER_RESUME,
            Self::Unlock => TRIGGER_UNLOCK,
            Self::Stop => 0,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct ConnectivityTrigger(u8);

impl ConnectivityTrigger {
    pub(crate) fn label(self) -> &'static str {
        if self.0 & TRIGGER_RESUME != 0 {
            "resume"
        } else if self.0 & TRIGGER_UNLOCK != 0 {
            "unlock"
        } else {
            "network-change"
        }
    }
}

#[derive(Default)]
pub(crate) struct ConnectivitySchedule {
    first_event_at: Option<Instant>,
    due_at: Option<Instant>,
    trigger_mask: u8,
}

impl ConnectivitySchedule {
    pub(crate) fn push(&mut self, event: ConnectivityEvent, now: Instant) {
        if event == ConnectivityEvent::Stop {
            return;
        }
        let first_event_at = *self.first_event_at.get_or_insert(now);
        let latest_allowed = first_event_at + MAX_SETTLE_DELAY;
        let requested = (now + event.settle_delay()).min(latest_allowed);
        self.due_at = Some(
            self.due_at
                .map(|current| current.max(requested).min(latest_allowed))
                .unwrap_or(requested),
        );
        self.trigger_mask |= event.trigger_mask();
    }

    pub(crate) fn wait_duration(&self, now: Instant) -> Duration {
        self.due_at
            .map(|due_at| due_at.saturating_duration_since(now))
            .unwrap_or(IDLE_WAIT)
    }

    pub(crate) fn take_due(&mut self, now: Instant) -> Option<ConnectivityTrigger> {
        if self.due_at.is_none_or(|due_at| now < due_at) {
            return None;
        }
        let trigger = ConnectivityTrigger(self.trigger_mask);
        *self = Self::default();
        Some(trigger)
    }
}

struct CallbackContext {
    sender: SyncSender<ConnectivityEvent>,
}

pub(crate) struct NetworkChangeSubscription {
    handle: HANDLE,
    context: *mut CallbackContext,
}

impl NetworkChangeSubscription {
    pub(crate) fn register(sender: SyncSender<ConnectivityEvent>) -> Result<Self, String> {
        let context = Box::into_raw(Box::new(CallbackContext { sender }));
        let mut handle = ptr::null_mut();
        let result = unsafe {
            NotifyIpInterfaceChange(
                AF_UNSPEC,
                Some(network_change_callback),
                context.cast::<c_void>(),
                0,
                &mut handle,
            )
        };
        if result != NO_ERROR {
            unsafe {
                drop(Box::from_raw(context));
            }
            return Err(format!(
                "NotifyIpInterfaceChange failed: {}",
                std::io::Error::from_raw_os_error(result as i32)
            ));
        }
        Ok(Self { handle, context })
    }
}

impl Drop for NetworkChangeSubscription {
    fn drop(&mut self) {
        if !self.handle.is_null() {
            unsafe {
                let _ = CancelMibChangeNotify2(self.handle);
            }
        }
        if !self.context.is_null() {
            unsafe {
                drop(Box::from_raw(self.context));
            }
            self.context = ptr::null_mut();
        }
    }
}

unsafe extern "system" fn network_change_callback(
    caller_context: *const c_void,
    _row: *const MIB_IPINTERFACE_ROW,
    _notification_type: MIB_NOTIFICATION_TYPE,
) {
    let Some(context) = (unsafe { caller_context.cast::<CallbackContext>().as_ref() }) else {
        return;
    };
    let _ = context.sender.try_send(ConnectivityEvent::NetworkChanged);
}

#[cfg(test)]
mod tests {
    use super::{ConnectivityEvent, ConnectivitySchedule};
    use std::time::{Duration, Instant};

    #[test]
    fn coalesces_resume_unlock_and_network_changes() {
        let started = Instant::now();
        let mut schedule = ConnectivitySchedule::default();
        schedule.push(ConnectivityEvent::Unlock, started);
        schedule.push(
            ConnectivityEvent::NetworkChanged,
            started + Duration::from_millis(500),
        );
        schedule.push(ConnectivityEvent::Resume, started + Duration::from_secs(1));

        assert!(schedule
            .take_due(started + Duration::from_secs(3))
            .is_none());
        let trigger = schedule
            .take_due(started + Duration::from_secs(4))
            .expect("merged trigger");
        assert_eq!(trigger.label(), "resume");
    }

    #[test]
    fn event_storm_has_a_bounded_settle_window() {
        let started = Instant::now();
        let mut schedule = ConnectivitySchedule::default();
        for second in 0..20 {
            schedule.push(
                ConnectivityEvent::NetworkChanged,
                started + Duration::from_secs(second),
            );
        }

        assert_eq!(
            schedule.wait_duration(started + Duration::from_secs(4)),
            Duration::from_secs(1)
        );
        assert_eq!(
            schedule
                .take_due(started + Duration::from_secs(5))
                .expect("bounded trigger")
                .label(),
            "network-change"
        );
    }
}
