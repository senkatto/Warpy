#![cfg(windows)]

use std::{
    fs,
    mem::size_of,
    os::windows::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Output},
    ptr,
};
use windows_sys::Win32::{
    Foundation::{CloseHandle, LocalFree, ERROR_INSUFFICIENT_BUFFER, HANDLE},
    Security::{
        Authorization::ConvertSidToStringSidW, GetTokenInformation, TokenUser, TOKEN_QUERY,
        TOKEN_USER,
    },
    System::Threading::{OpenProcess, OpenProcessToken, PROCESS_QUERY_LIMITED_INFORMATION},
};

const TASK_NAME: &str = "Warpy";
const CREATE_NO_WINDOW: u32 = 0x08000000;

pub(crate) fn configure(enabled: bool, user_process_id: Option<u32>) -> Result<(), String> {
    if !enabled {
        return delete_task();
    }

    let process_id = user_process_id
        .ok_or_else(|| "Warpy must be running to configure autostart".to_string())?;
    let user_sid = process_user_sid(process_id)?;
    let executable = std::env::current_exe().map_err(|error| error.to_string())?;
    let xml = task_xml(&executable, &user_sid);
    let xml_path = write_task_xml(&xml)?;
    let result = run_schtasks(&[
        "/Create",
        "/TN",
        TASK_NAME,
        "/XML",
        &xml_path.to_string_lossy(),
        "/F",
    ]);
    let _ = fs::remove_file(&xml_path);
    require_success(result?, "Не удалось настроить быстрый автозапуск Warpy")
}

fn delete_task() -> Result<(), String> {
    let result = run_schtasks(&["/Delete", "/TN", TASK_NAME, "/F"])?;
    if result.status.success() {
        return Ok(());
    }

    let query = run_schtasks(&["/Query", "/TN", TASK_NAME])?;
    if query.status.success() {
        return Err(command_error(
            "Не удалось отключить быстрый автозапуск Warpy",
            &result,
        ));
    }
    Ok(())
}

fn run_schtasks(arguments: &[&str]) -> Result<Output, String> {
    Command::new(schtasks_path())
        .args(arguments)
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|error| format!("Не удалось запустить планировщик Windows: {error}"))
}

fn require_success(result: Output, context: &str) -> Result<(), String> {
    if result.status.success() {
        Ok(())
    } else {
        Err(command_error(context, &result))
    }
}

fn command_error(context: &str, result: &Output) -> String {
    format!("{context} (код {})", result.status.code().unwrap_or(-1))
}

fn schtasks_path() -> PathBuf {
    std::env::var_os("SystemRoot")
        .map(PathBuf::from)
        .map(|path| path.join("System32").join("schtasks.exe"))
        .unwrap_or_else(|| PathBuf::from("schtasks.exe"))
}

fn write_task_xml(xml: &str) -> Result<PathBuf, String> {
    let directory = std::env::var_os("ProgramData")
        .map(PathBuf::from)
        .map(|path| path.join("Warpy"))
        .ok_or_else(|| "Системная папка ProgramData недоступна".to_string())?;
    fs::create_dir_all(&directory).map_err(|error| error.to_string())?;
    let path = directory.join(format!("autostart-{}.xml", std::process::id()));
    let mut contents = Vec::with_capacity((xml.len() + 1) * 2);
    contents.extend_from_slice(&[0xFF, 0xFE]);
    for unit in xml.encode_utf16() {
        contents.extend_from_slice(&unit.to_le_bytes());
    }
    fs::write(&path, contents).map_err(|error| error.to_string())?;
    Ok(path)
}

fn task_xml(executable: &Path, user_sid: &str) -> String {
    let executable = xml_escape(&executable.to_string_lossy());
    let user_sid = xml_escape(user_sid);
    format!(
        r#"<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>Starts Warpy in the notification area after sign-in.</Description>
  </RegistrationInfo>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
      <Delay>PT3S</Delay>
      <UserId>{user_sid}</UserId>
    </LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="WarpyUser">
      <UserId>{user_sid}</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>false</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>false</WakeToRun>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>4</Priority>
    <RestartOnFailure>
      <Interval>PT1M</Interval>
      <Count>3</Count>
    </RestartOnFailure>
  </Settings>
  <Actions Context="WarpyUser">
    <Exec>
      <Command>{executable}</Command>
      <Arguments>--autostart</Arguments>
    </Exec>
  </Actions>
</Task>"#
    )
}

fn xml_escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

fn process_user_sid(process_id: u32) -> Result<String, String> {
    let process = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, process_id) };
    let process = OwnedHandle::new(process, "Не удалось открыть процесс Warpy")?;
    let mut token = ptr::null_mut();
    if unsafe { OpenProcessToken(process.raw(), TOKEN_QUERY, &mut token) } == 0 {
        return Err(format!(
            "Не удалось определить пользователя Warpy: {}",
            std::io::Error::last_os_error()
        ));
    }
    let token = OwnedHandle::new(token, "Не удалось открыть токен Warpy")?;

    let mut required = 0_u32;
    unsafe {
        GetTokenInformation(token.raw(), TokenUser, ptr::null_mut(), 0, &mut required);
    }
    if required == 0
        || std::io::Error::last_os_error().raw_os_error() != Some(ERROR_INSUFFICIENT_BUFFER as i32)
    {
        return Err(format!(
            "Не удалось определить размер токена Warpy: {}",
            std::io::Error::last_os_error()
        ));
    }

    let word_size = size_of::<usize>();
    let mut buffer = vec![0_usize; (required as usize).div_ceil(word_size)];
    if unsafe {
        GetTokenInformation(
            token.raw(),
            TokenUser,
            buffer.as_mut_ptr().cast(),
            required,
            &mut required,
        )
    } == 0
    {
        return Err(format!(
            "Не удалось прочитать пользователя Warpy: {}",
            std::io::Error::last_os_error()
        ));
    }

    let token_user = unsafe { &*(buffer.as_ptr().cast::<TOKEN_USER>()) };
    let mut sid_text = ptr::null_mut();
    if unsafe { ConvertSidToStringSidW(token_user.User.Sid, &mut sid_text) } == 0 {
        return Err(format!(
            "Не удалось преобразовать идентификатор пользователя: {}",
            std::io::Error::last_os_error()
        ));
    }
    let sid = unsafe { wide_string(sid_text) };
    unsafe {
        let _ = LocalFree(sid_text.cast());
    }
    Ok(sid)
}

unsafe fn wide_string(value: *const u16) -> String {
    let mut length = 0;
    while unsafe { *value.add(length) } != 0 {
        length += 1;
    }
    String::from_utf16_lossy(unsafe { std::slice::from_raw_parts(value, length) })
}

struct OwnedHandle(HANDLE);

impl OwnedHandle {
    fn new(handle: HANDLE, context: &str) -> Result<Self, String> {
        if handle.is_null() {
            Err(format!("{context}: {}", std::io::Error::last_os_error()))
        } else {
            Ok(Self(handle))
        }
    }

    fn raw(&self) -> HANDLE {
        self.0
    }
}

impl Drop for OwnedHandle {
    fn drop(&mut self) {
        unsafe {
            let _ = CloseHandle(self.0);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{task_xml, xml_escape};
    use std::path::Path;

    #[test]
    fn task_targets_the_user_session_and_waits_for_explorer() {
        let xml = task_xml(
            Path::new(r"C:\Program Files\Warpy & Co\warpy-desktop.exe"),
            "S-1-5-21-1000",
        );
        assert!(xml.contains("<LogonType>InteractiveToken</LogonType>"));
        assert!(xml.contains("<Delay>PT3S</Delay>"));
        assert!(xml.contains("<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>"));
        assert!(xml.contains("<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>"));
        assert!(xml.contains("<Interval>PT1M</Interval>"));
        assert!(
            xml.contains(r"<Command>C:\Program Files\Warpy &amp; Co\warpy-desktop.exe</Command>")
        );
        assert!(xml.contains("<Arguments>--autostart</Arguments>"));
    }

    #[test]
    fn xml_values_are_escaped() {
        assert_eq!(xml_escape("<&>\"'"), "&lt;&amp;&gt;&quot;&apos;");
    }
}
