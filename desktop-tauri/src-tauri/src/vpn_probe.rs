#![cfg(windows)]

use std::{ffi::c_void, os::windows::ffi::OsStrExt, ptr, time::Duration};
use windows_sys::Win32::Networking::WinHttp::{
    WinHttpCloseHandle, WinHttpConnect, WinHttpOpen, WinHttpOpenRequest, WinHttpQueryHeaders,
    WinHttpReceiveResponse, WinHttpSendRequest, WinHttpSetTimeouts, ERROR_WINHTTP_CANNOT_CONNECT,
    ERROR_WINHTTP_CONNECTION_ERROR, ERROR_WINHTTP_NAME_NOT_RESOLVED, ERROR_WINHTTP_SECURE_FAILURE,
    ERROR_WINHTTP_TIMEOUT, INTERNET_DEFAULT_HTTPS_PORT, WINHTTP_ACCESS_TYPE_NO_PROXY,
    WINHTTP_FLAG_SECURE, WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_QUERY_STATUS_CODE,
};

const PROBE_SERVER: &str = "www.gstatic.com";
const PROBE_PATH: &str = "/generate_204";
const PROBE_ATTEMPTS: usize = 3;
const PROBE_RETRY_DELAY: Duration = Duration::from_millis(600);

pub(crate) fn verify_tunnel() -> Result<(), String> {
    verify_tunnel_with_attempts(PROBE_ATTEMPTS)
}

pub(crate) fn verify_tunnel_once() -> Result<(), String> {
    verify_tunnel_with_attempts(1)
}

fn verify_tunnel_with_attempts(attempts: usize) -> Result<(), String> {
    let mut last_error = "Нет обмена данными через VPN-туннель".to_string();
    for attempt in 0..attempts {
        match probe_once() {
            Ok(()) => return Ok(()),
            Err(error) => last_error = error,
        }
        if attempt + 1 < attempts {
            std::thread::sleep(PROBE_RETRY_DELAY);
        }
    }
    Err(last_error)
}

fn probe_once() -> Result<(), String> {
    let agent = wide("Warpy tunnel check");
    let session = WinHttpHandle::new(unsafe {
        WinHttpOpen(
            agent.as_ptr(),
            WINHTTP_ACCESS_TYPE_NO_PROXY,
            ptr::null(),
            ptr::null(),
            0,
        )
    })?;
    if unsafe { WinHttpSetTimeouts(session.raw(), 1_500, 2_000, 2_000, 3_000) } == 0 {
        return Err(winhttp_error("Не удалось настроить проверку туннеля"));
    }

    let server = wide(PROBE_SERVER);
    let connection = WinHttpHandle::new(unsafe {
        WinHttpConnect(
            session.raw(),
            server.as_ptr(),
            INTERNET_DEFAULT_HTTPS_PORT,
            0,
        )
    })?;
    let verb = wide("GET");
    let path = wide(PROBE_PATH);
    let request = WinHttpHandle::new(unsafe {
        WinHttpOpenRequest(
            connection.raw(),
            verb.as_ptr(),
            path.as_ptr(),
            ptr::null(),
            ptr::null(),
            ptr::null(),
            WINHTTP_FLAG_SECURE,
        )
    })?;

    if unsafe { WinHttpSendRequest(request.raw(), ptr::null(), 0, ptr::null(), 0, 0, 0) } == 0 {
        return Err(winhttp_error("Не удалось отправить запрос через туннель"));
    }
    if unsafe { WinHttpReceiveResponse(request.raw(), ptr::null_mut()) } == 0 {
        return Err(winhttp_error("Сервер не ответил через VPN-туннель"));
    }

    let mut status_code = 0_u32;
    let mut status_size = std::mem::size_of::<u32>() as u32;
    let mut header_index = 0_u32;
    if unsafe {
        WinHttpQueryHeaders(
            request.raw(),
            WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
            ptr::null(),
            (&mut status_code as *mut u32).cast::<c_void>(),
            &mut status_size,
            &mut header_index,
        )
    } == 0
    {
        return Err(winhttp_error("Не удалось проверить ответ VPN"));
    }
    if !successful_probe_status(status_code) {
        return Err(format!("Проверка VPN вернула HTTP {status_code}"));
    }
    Ok(())
}

fn successful_probe_status(status: u32) -> bool {
    (200..300).contains(&status)
}

fn winhttp_error(context: &str) -> String {
    let error = std::io::Error::last_os_error();
    match error.raw_os_error().map(|code| code as u32) {
        Some(ERROR_WINHTTP_TIMEOUT) => "Сервер VPN не ответил вовремя".to_string(),
        Some(ERROR_WINHTTP_NAME_NOT_RESOLVED) => {
            "Не удалось разрешить DNS через VPN-туннель".to_string()
        }
        Some(ERROR_WINHTTP_CANNOT_CONNECT) | Some(ERROR_WINHTTP_CONNECTION_ERROR) => {
            "Сервер недоступен через VPN-туннель".to_string()
        }
        Some(ERROR_WINHTTP_SECURE_FAILURE) => {
            "Не удалось установить защищённое соединение через VPN".to_string()
        }
        _ => format!("{context}: {error}"),
    }
}

fn wide(value: &str) -> Vec<u16> {
    std::ffi::OsStr::new(value)
        .encode_wide()
        .chain(Some(0))
        .collect()
}

struct WinHttpHandle(*mut c_void);

impl WinHttpHandle {
    fn new(handle: *mut c_void) -> Result<Self, String> {
        if handle.is_null() {
            return Err(winhttp_error("Не удалось начать проверку VPN-туннеля"));
        }
        Ok(Self(handle))
    }

    fn raw(&self) -> *mut c_void {
        self.0
    }
}

impl Drop for WinHttpHandle {
    fn drop(&mut self) {
        unsafe {
            let _ = WinHttpCloseHandle(self.0);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::successful_probe_status;

    #[test]
    fn accepts_generate_204_without_requiring_a_response_body() {
        assert!(successful_probe_status(204));
        assert!(successful_probe_status(200));
        assert!(!successful_probe_status(503));
    }
}
