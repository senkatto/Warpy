#![cfg(windows)]

use serde::Serialize;
use std::{ffi::c_void, os::windows::ffi::OsStrExt, ptr};
use windows_sys::Win32::Networking::WinHttp::{
    WinHttpCloseHandle, WinHttpConnect, WinHttpCrackUrl, WinHttpOpen, WinHttpOpenRequest,
    WinHttpQueryHeaders, WinHttpReadData, WinHttpReceiveResponse, WinHttpSendRequest,
    WinHttpSetOption, WinHttpSetTimeouts, ERROR_WINHTTP_CANNOT_CONNECT,
    ERROR_WINHTTP_CONNECTION_ERROR, ERROR_WINHTTP_NAME_NOT_RESOLVED, ERROR_WINHTTP_SECURE_FAILURE,
    ERROR_WINHTTP_TIMEOUT, URL_COMPONENTS, WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
    WINHTTP_DECOMPRESSION_FLAG_DEFLATE, WINHTTP_DECOMPRESSION_FLAG_GZIP, WINHTTP_FLAG_REFRESH,
    WINHTTP_FLAG_SECURE, WINHTTP_INTERNET_SCHEME_HTTPS, WINHTTP_OPTION_DECOMPRESSION,
    WINHTTP_OPTION_REDIRECT_POLICY, WINHTTP_OPTION_REDIRECT_POLICY_DISALLOW_HTTPS_TO_HTTP,
    WINHTTP_QUERY_CONTENT_LENGTH, WINHTTP_QUERY_CONTENT_TYPE, WINHTTP_QUERY_FLAG_NUMBER,
    WINHTTP_QUERY_STATUS_CODE,
};

const MAX_URL_LENGTH: usize = 4096;
const MAX_SUBSCRIPTION_BYTES: usize = 2 * 1024 * 1024;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct FetchedSubscription {
    body: String,
    content_type: Option<String>,
}

#[derive(Debug, PartialEq)]
struct HttpsUrl {
    host: String,
    port: u16,
    object_name: String,
}

pub(crate) fn fetch(url: &str) -> Result<FetchedSubscription, String> {
    let parsed = parse_https_url(url)?;
    let agent = wide("Warpy subscription");
    let session = WinHttpHandle::new(unsafe {
        WinHttpOpen(
            agent.as_ptr(),
            WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
            ptr::null(),
            ptr::null(),
            0,
        )
    })?;
    if unsafe { WinHttpSetTimeouts(session.raw(), 5_000, 7_000, 10_000, 20_000) } == 0 {
        return Err(winhttp_error("Не удалось настроить загрузку подписки"));
    }

    let host = wide(&parsed.host);
    let connection = WinHttpHandle::new(unsafe {
        WinHttpConnect(session.raw(), host.as_ptr(), parsed.port, 0)
    })?;
    let verb = wide("GET");
    let object_name = wide(&parsed.object_name);
    let request = WinHttpHandle::new(unsafe {
        WinHttpOpenRequest(
            connection.raw(),
            verb.as_ptr(),
            object_name.as_ptr(),
            ptr::null(),
            ptr::null(),
            ptr::null(),
            WINHTTP_FLAG_SECURE | WINHTTP_FLAG_REFRESH,
        )
    })?;

    let redirect_policy = WINHTTP_OPTION_REDIRECT_POLICY_DISALLOW_HTTPS_TO_HTTP;
    if unsafe {
        WinHttpSetOption(
            request.raw(),
            WINHTTP_OPTION_REDIRECT_POLICY,
            (&redirect_policy as *const u32).cast::<c_void>(),
            std::mem::size_of::<u32>() as u32,
        )
    } == 0
    {
        return Err(winhttp_error(
            "Не удалось защитить перенаправление подписки",
        ));
    }

    let decompression = WINHTTP_DECOMPRESSION_FLAG_GZIP | WINHTTP_DECOMPRESSION_FLAG_DEFLATE;
    if unsafe {
        WinHttpSetOption(
            request.raw(),
            WINHTTP_OPTION_DECOMPRESSION,
            (&decompression as *const u32).cast::<c_void>(),
            std::mem::size_of::<u32>() as u32,
        )
    } == 0
    {
        return Err(winhttp_error("Не удалось настроить распаковку подписки"));
    }

    if unsafe { WinHttpSendRequest(request.raw(), ptr::null(), 0, ptr::null(), 0, 0, 0) } == 0 {
        return Err(winhttp_error("Не удалось отправить запрос подписки"));
    }
    if unsafe { WinHttpReceiveResponse(request.raw(), ptr::null_mut()) } == 0 {
        return Err(winhttp_error("Сервер подписки не ответил"));
    }

    let status = query_number_header(request.raw(), WINHTTP_QUERY_STATUS_CODE)
        .ok_or_else(|| winhttp_error("Не удалось проверить ответ подписки"))?;
    if status != 200 {
        return Err(format!("Сервер подписки вернул HTTP {status}"));
    }

    if query_number_header(request.raw(), WINHTTP_QUERY_CONTENT_LENGTH)
        .is_some_and(|length| length as usize > MAX_SUBSCRIPTION_BYTES)
    {
        return Err("Подписка превышает допустимый размер".to_string());
    }

    let content_type = query_string_header(request.raw(), WINHTTP_QUERY_CONTENT_TYPE);
    validate_content_type(content_type.as_deref())?;

    let mut body = Vec::new();
    let mut buffer = [0_u8; 16 * 1024];
    loop {
        let mut bytes_read = 0_u32;
        if unsafe {
            WinHttpReadData(
                request.raw(),
                buffer.as_mut_ptr().cast::<c_void>(),
                buffer.len() as u32,
                &mut bytes_read,
            )
        } == 0
        {
            return Err(winhttp_error("Не удалось прочитать подписку"));
        }
        if bytes_read == 0 {
            break;
        }
        if body.len() + bytes_read as usize > MAX_SUBSCRIPTION_BYTES {
            return Err("Подписка превышает допустимый размер".to_string());
        }
        body.extend_from_slice(&buffer[..bytes_read as usize]);
    }

    let body =
        String::from_utf8(body).map_err(|_| "Подписка содержит некорректный текст".to_string())?;
    validate_body(&body)?;
    Ok(FetchedSubscription { body, content_type })
}

fn parse_https_url(value: &str) -> Result<HttpsUrl, String> {
    let value = value.trim();
    if value.is_empty() || value.len() > MAX_URL_LENGTH || value.contains(['\r', '\n']) {
        return Err("Некорректный адрес подписки".to_string());
    }

    let mut encoded = wide(value);
    let mut components: URL_COMPONENTS = unsafe { std::mem::zeroed() };
    components.dwStructSize = std::mem::size_of::<URL_COMPONENTS>() as u32;
    components.dwSchemeLength = u32::MAX;
    components.dwHostNameLength = u32::MAX;
    components.dwUserNameLength = u32::MAX;
    components.dwPasswordLength = u32::MAX;
    components.dwUrlPathLength = u32::MAX;
    components.dwExtraInfoLength = u32::MAX;
    if unsafe { WinHttpCrackUrl(encoded.as_mut_ptr(), 0, 0, &mut components) } == 0 {
        return Err("Некорректный адрес подписки".to_string());
    }
    if components.nScheme != WINHTTP_INTERNET_SCHEME_HTTPS {
        return Err("Подписка должна использовать HTTPS".to_string());
    }
    if components.dwUserNameLength != 0 || components.dwPasswordLength != 0 {
        return Err("Адрес подписки не должен содержать логин или пароль".to_string());
    }

    let host = component_string(components.lpszHostName, components.dwHostNameLength);
    if host.is_empty() {
        return Err("В адресе подписки не указан сервер".to_string());
    }
    let mut object_name = component_string(components.lpszUrlPath, components.dwUrlPathLength);
    object_name.push_str(&component_string(
        components.lpszExtraInfo,
        components.dwExtraInfoLength,
    ));
    if let Some(fragment) = object_name.find('#') {
        object_name.truncate(fragment);
    }
    if object_name.is_empty() {
        object_name.push('/');
    }

    Ok(HttpsUrl {
        host,
        port: components.nPort,
        object_name,
    })
}

fn validate_content_type(content_type: Option<&str>) -> Result<(), String> {
    let Some(content_type) = content_type else {
        return Ok(());
    };
    let media_type = content_type
        .split(';')
        .next()
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    if matches!(
        media_type.as_str(),
        "text/plain"
            | "application/octet-stream"
            | "application/json"
            | "application/yaml"
            | "application/x-yaml"
            | "text/yaml"
            | "text/x-yaml"
    ) {
        return Ok(());
    }
    Err("Сервер вернул неподдерживаемый тип подписки".to_string())
}

fn validate_body(body: &str) -> Result<(), String> {
    let trimmed = body.trim_start_matches('\u{feff}').trim_start();
    if trimmed.is_empty() {
        return Err("Подписка пуста".to_string());
    }
    let prefix = trimmed
        .chars()
        .take(64)
        .collect::<String>()
        .to_ascii_lowercase();
    if prefix.starts_with("<!doctype html")
        || prefix.starts_with("<html")
        || prefix.starts_with("<head")
        || prefix.starts_with("<body")
    {
        return Err("Вместо подписки сервер вернул веб-страницу".to_string());
    }
    if body.contains('\0') {
        return Err("Подписка содержит некорректный текст".to_string());
    }
    Ok(())
}

fn query_number_header(request: *mut c_void, query: u32) -> Option<u32> {
    let mut value = 0_u32;
    let mut size = std::mem::size_of::<u32>() as u32;
    let mut index = 0_u32;
    let ok = unsafe {
        WinHttpQueryHeaders(
            request,
            query | WINHTTP_QUERY_FLAG_NUMBER,
            ptr::null(),
            (&mut value as *mut u32).cast::<c_void>(),
            &mut size,
            &mut index,
        )
    };
    (ok != 0).then_some(value)
}

fn query_string_header(request: *mut c_void, query: u32) -> Option<String> {
    let mut buffer = [0_u16; 256];
    let mut size = std::mem::size_of_val(&buffer) as u32;
    let mut index = 0_u32;
    let ok = unsafe {
        WinHttpQueryHeaders(
            request,
            query,
            ptr::null(),
            buffer.as_mut_ptr().cast::<c_void>(),
            &mut size,
            &mut index,
        )
    };
    if ok == 0 {
        return None;
    }
    let length = buffer
        .iter()
        .position(|value| *value == 0)
        .unwrap_or(buffer.len());
    Some(String::from_utf16_lossy(&buffer[..length]))
}

fn component_string(pointer: *mut u16, length: u32) -> String {
    if pointer.is_null() || length == 0 {
        return String::new();
    }
    unsafe { String::from_utf16_lossy(std::slice::from_raw_parts(pointer, length as usize)) }
}

fn winhttp_error(context: &str) -> String {
    let error = std::io::Error::last_os_error();
    match error.raw_os_error().map(|code| code as u32) {
        Some(ERROR_WINHTTP_TIMEOUT) => "Сервер подписки не ответил вовремя".to_string(),
        Some(ERROR_WINHTTP_NAME_NOT_RESOLVED) => "Не удалось найти сервер подписки".to_string(),
        Some(ERROR_WINHTTP_CANNOT_CONNECT) | Some(ERROR_WINHTTP_CONNECTION_ERROR) => {
            "Не удалось подключиться к серверу подписки".to_string()
        }
        Some(ERROR_WINHTTP_SECURE_FAILURE) => {
            "Не удалось проверить защищённое соединение подписки".to_string()
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
            return Err(winhttp_error("Не удалось начать загрузку подписки"));
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
    use super::{parse_https_url, validate_body, validate_content_type, HttpsUrl};

    #[test]
    fn parses_https_urls_without_sending_fragments() {
        assert_eq!(
            parse_https_url("https://example.com:8443/path/list?token=value#fragment"),
            Ok(HttpsUrl {
                host: "example.com".to_string(),
                port: 8443,
                object_name: "/path/list?token=value".to_string(),
            })
        );
    }

    #[test]
    fn rejects_insecure_or_credentialed_urls() {
        assert!(parse_https_url("http://example.com/list").is_err());
        assert!(parse_https_url("https://user:secret@example.com/list").is_err());
        assert!(parse_https_url("file:///C:/subscription.txt").is_err());
    }

    #[test]
    fn validates_content_before_parsing_profiles() {
        assert!(validate_content_type(Some("text/plain; charset=utf-8")).is_ok());
        assert!(validate_content_type(Some("application/octet-stream")).is_ok());
        assert!(validate_content_type(Some("application/json; charset=utf-8")).is_ok());
        assert!(validate_content_type(Some("text/html")).is_err());
        assert!(validate_body("<!doctype html><title>Login</title>").is_err());
        assert!(validate_body("vless://profile").is_ok());
    }
}
