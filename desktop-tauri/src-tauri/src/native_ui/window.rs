use super::*;
use painter::{Painter, Scene};
use std::{cell::Cell, ptr, sync::atomic::Ordering};
use windows::{
    core::w,
    Win32::{
        Foundation::{HANDLE, HGLOBAL, HINSTANCE, LRESULT, RECT},
        Graphics::Gdi::{
            BeginPaint, CreateRoundRectRgn, EndPaint, InvalidateRect, SetWindowRgn, UpdateWindow,
            PAINTSTRUCT,
        },
        System::{
            Com::{CoInitializeEx, COINIT_APARTMENTTHREADED},
            DataExchange::*,
            LibraryLoader::GetModuleHandleW,
            Memory::*,
        },
        UI::{
            HiDpi::GetDpiForWindow,
            Input::KeyboardAndMouse::{GetKeyState, VK_CONTROL, VK_SHIFT},
            WindowsAndMessaging::*,
        },
    },
};

const FRAME_TIME: std::time::Duration = std::time::Duration::from_nanos(1_000_000_000 / 60);
struct FrameTimer(windows_sys::Win32::Foundation::HANDLE);
impl FrameTimer {
    unsafe fn new() -> Result<Self, String> {
        use windows_sys::Win32::System::Threading::*;
        let mut handle = CreateWaitableTimerExW(
            ptr::null(),
            ptr::null(),
            CREATE_WAITABLE_TIMER_HIGH_RESOLUTION,
            TIMER_ALL_ACCESS,
        );
        if handle.is_null() {
            handle = CreateWaitableTimerExW(ptr::null(), ptr::null(), 0, TIMER_ALL_ACCESS);
        }
        if handle.is_null() {
            return Err(windows::core::Error::from_win32().to_string());
        }
        Ok(Self(handle))
    }
    unsafe fn arm(&self, delay: std::time::Duration) -> Result<(), String> {
        let due = -((delay.as_nanos() / 100).max(1) as i64);
        if windows_sys::Win32::System::Threading::SetWaitableTimerEx(
            self.0,
            &due,
            0,
            None,
            ptr::null(),
            ptr::null(),
            0,
        ) == 0
        {
            return Err(windows::core::Error::from_win32().to_string());
        }
        Ok(())
    }
}
impl Drop for FrameTimer {
    fn drop(&mut self) {
        unsafe {
            windows_sys::Win32::Foundation::CloseHandle(self.0);
        }
    }
}

struct Window {
    hwnd: HWND,
    controller: Controller,
    painter: Option<Painter>,
    scene: Scene,
    app: tauri::AppHandle,
    scale: f32,
    visible: bool,
    dispatching: Cell<bool>,
    high_surrogate: Option<u16>,
}

impl Window {
    unsafe fn refresh(&mut self) {
        for command in self.controller.drain() {
            match command.as_str() {
                "hide" => {
                    let _ = ShowWindow(self.hwnd, SW_HIDE);
                    self.visibility(false);
                }
                "minimize" => {
                    let _ = ShowWindow(self.hwnd, SW_MINIMIZE);
                    self.visibility(false);
                }
                "show" => self.show(),
                "toggle" => {
                    if self.visible {
                        let _ = ShowWindow(self.hwnd, SW_HIDE);
                        self.visibility(false);
                    } else {
                        self.show();
                    }
                }
                _ => {}
            }
        }
        if self.visible {
            match self.controller.scene() {
                Ok(Some(scene)) => {
                    self.scene = scene;
                    let _ = InvalidateRect(Some(self.hwnd), None, false);
                }
                Ok(None) => {}
                Err(error) => log_message(self.app.clone(), format!("Native scene: {error}")),
            }
        }
        let _ = KillTimer(Some(self.hwnd), 1);
        SetTimer(
            Some(self.hwnd),
            1,
            self.controller.next_wake().max(10),
            None,
        );
    }
    unsafe fn visibility(&mut self, visible: bool) {
        if self.visible == visible {
            return;
        }
        self.visible = visible;
        self.controller.set_visible(visible);
        if !visible {
            self.painter = None;
        }
    }
    unsafe fn show(&mut self) {
        let _ = ShowWindow(self.hwnd, SW_RESTORE);
        let _ = SetForegroundWindow(self.hwnd);
        self.visibility(true);
    }
    unsafe fn paint(&mut self) {
        let mut ps = PAINTSTRUCT::default();
        let _ = BeginPaint(self.hwnd, &mut ps);
        if self.painter.is_none() {
            let dpi = GetDpiForWindow(self.hwnd);
            let flags = self
                .app
                .path()
                .resource_dir()
                .unwrap_or_default()
                .join("flags");
            self.painter = Painter::new(
                self.hwnd,
                (420.0 * self.scale) as u32,
                (720.0 * self.scale) as u32,
                dpi,
                flags,
            )
            .ok();
        }
        if let Some(painter) = self.painter.as_mut() {
            if let Err(error) = painter.paint(&self.scene, self.controller.now()) {
                self.painter = None;
                log_message(self.app.clone(), format!("Native paint: {error}"));
            }
        }
        let _ = EndPaint(self.hwnd, &ps);
    }
}

unsafe extern "system" fn procedure(hwnd: HWND, message: u32, wp: WPARAM, lp: LPARAM) -> LRESULT {
    if message == WM_NCCREATE {
        let create = &*(lp.0 as *const CREATESTRUCTW);
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, create.lpCreateParams as isize);
    }
    let pointer = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut Window;
    if pointer.is_null() {
        return DefWindowProcW(hwnd, message, wp, lp);
    }
    // ShowWindow/SetWindowPos can synchronously send nested window messages.
    // Defer those to Windows rather than reentering borrowing
    // the controller twice.
    if (*pointer).dispatching.replace(true) {
        return DefWindowProcW(hwnd, message, wp, lp);
    }
    struct DispatchGuard(*mut Window);
    impl Drop for DispatchGuard {
        fn drop(&mut self) {
            unsafe {
                (*self.0).dispatching.set(false);
            }
        }
    }
    let _guard = DispatchGuard(pointer);
    let state = &mut *pointer;
    state.hwnd = hwnd;
    match message {
        WM_PAINT => state.paint(),
        WM_ERASEBKGND => return LRESULT(1),
        WM_TIMER | WAKE => state.refresh(),
        WM_CLOSE => {
            let _ = ShowWindow(hwnd, SW_HIDE);
            state.visibility(false);
            state.refresh();
        }
        WM_SIZE => {
            if wp.0 == SIZE_MINIMIZED as usize {
                state.visibility(false);
            } else {
                state.visibility(true);
                state.refresh();
            }
        }
        WM_NCHITTEST => {
            let mut point = windows::Win32::Foundation::POINT {
                x: (lp.0 as u16 as i16) as i32,
                y: ((lp.0 >> 16) as u16 as i16) as i32,
            };
            let _ = windows::Win32::Graphics::Gdi::ScreenToClient(hwnd, &mut point);
            let x = point.x as f32 / state.scale;
            let y = point.y as f32 / state.scale;
            if (20.0..94.0).contains(&y) {
                let control = state.scene.hits.iter().any(|hit| hit.contains(x, y));
                if !control {
                    return LRESULT(HTCAPTION as isize);
                }
            }
            return LRESULT(HTCLIENT as isize);
        }
        WM_MOUSEMOVE | WM_LBUTTONUP => {
            let x = (lp.0 as u16 as i16) as f32 / state.scale;
            let y = ((lp.0 >> 16) as u16 as i16) as f32 / state.scale;
            let hit = state
                .scene
                .hits
                .iter()
                .rev()
                .find(|hit| hit.contains(x, y))
                .cloned();
            state.controller.pointer(
                if message == WM_MOUSEMOVE {
                    "move"
                } else {
                    "click"
                },
                hit,
            );
            state.refresh();
        }
        WM_MOUSEWHEEL => {
            let mut point = windows::Win32::Foundation::POINT {
                x: (lp.0 as u16 as i16) as i32,
                y: ((lp.0 >> 16) as u16 as i16) as i32,
            };
            let _ = windows::Win32::Graphics::Gdi::ScreenToClient(hwnd, &mut point);
            let x = point.x as f32 / state.scale;
            let y = point.y as f32 / state.scale;
            let delta = ((wp.0 >> 16) as u16 as i16) as f32;
            if let Some(hit) = state
                .scene
                .scrolls
                .iter()
                .rev()
                .find(|hit| hit.contains(x, y))
                .cloned()
            {
                state.controller.scroll(&hit, delta);
                state.refresh();
            }
        }
        WM_DPICHANGED => {
            let suggested = &*(lp.0 as *const RECT);
            state.scale = (wp.0 as u16).max(96) as f32 / 96.0;
            state.painter = None;
            let _ = SetWindowPos(
                hwnd,
                None,
                suggested.left,
                suggested.top,
                (420.0 * state.scale) as i32,
                (720.0 * state.scale) as i32,
                SWP_NOZORDER,
            );
            update_region(hwnd, state.scale);
            state.controller.mark_dirty();
            state.refresh();
        }
        WM_KEYDOWN => {
            let key = match wp.0 {
                9 => "Tab",
                27 => "Escape",
                8 => "Backspace",
                13 => "Enter",
                35 => "End",
                36 => "Home",
                37 => "ArrowLeft",
                38 => "ArrowUp",
                39 => "ArrowRight",
                40 => "ArrowDown",
                46 => "Delete",
                65 => "a",
                67 => "c",
                86 => "v",
                88 => "x",
                _ => "",
            };
            let ctrl = GetKeyState(VK_CONTROL.0 as i32) < 0;
            let shift = GetKeyState(VK_SHIFT.0 as i32) < 0;
            if !key.is_empty() && (ctrl || wp.0 < 32 || matches!(wp.0, 35..=40 | 46)) {
                state.controller.key(key, ctrl, shift, &state.scene.hits);
                state.refresh();
            }
        }
        WM_CHAR => {
            if wp.0 >= 32 && GetKeyState(VK_CONTROL.0 as i32) >= 0 {
                let value = wp.0 as u16;
                if (0xd800..=0xdbff).contains(&value) {
                    state.high_surrogate = Some(value);
                } else {
                    let ch = if (0xdc00..=0xdfff).contains(&value) {
                        state.high_surrogate.take().and_then(|high| {
                            char::from_u32(
                                0x10000 + ((high as u32 - 0xd800) << 10) + (value as u32 - 0xdc00),
                            )
                        })
                    } else {
                        state.high_surrogate = None;
                        char::from_u32(value as u32)
                    };
                    if let Some(ch) = ch {
                        state
                            .controller
                            .key(&ch.to_string(), false, false, &state.scene.hits);
                        state.refresh();
                    }
                }
            }
        }
        WM_DESTROY => {
            PostQuitMessage(0);
        }
        _ => return DefWindowProcW(hwnd, message, wp, lp),
    }
    LRESULT(0)
}

pub(super) fn run(
    app: tauri::AppHandle,
    sender: Sender,
    receiver: mpsc::Receiver<Message>,
    preview: bool,
    autostart: bool,
) -> Result<(), String> {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
        let controller = Controller::new(app.clone(), sender.clone(), receiver, preview)?;
        let mut state = Box::new(Window {
            hwnd: HWND::default(),
            controller,
            painter: None,
            scene: Scene::default(),
            app,
            scale: 1.0,
            visible: !autostart,
            dispatching: Cell::new(false),
            high_surrogate: None,
        });
        let instance = GetModuleHandleW(None).map_err(|e| e.to_string())?;
        let icon = LoadImageW(
            Some(HINSTANCE(instance.0)),
            windows::core::PCWSTR(32512_usize as _),
            IMAGE_ICON,
            32,
            32,
            LR_DEFAULTCOLOR,
        )
        .ok()
        .map(|value| HICON(value.0))
        .unwrap_or_default();
        let class = WNDCLASSW {
            lpfnWndProc: Some(procedure),
            hInstance: HINSTANCE(instance.0),
            lpszClassName: w!("WarpyNativeWindow"),
            hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
            hIcon: icon,
            ..Default::default()
        };
        if RegisterClassW(&class) == 0 {
            return Err("Cannot register native Warpy window".to_string());
        }
        let width = 420;
        let height = 720;
        let hwnd = CreateWindowExW(
            WS_EX_APPWINDOW,
            w!("WarpyNativeWindow"),
            if preview {
                w!("Warpy Native Preview")
            } else {
                w!("Warpy")
            },
            WS_POPUP | WS_MINIMIZEBOX,
            (GetSystemMetrics(SM_CXSCREEN) - width) / 2,
            (GetSystemMetrics(SM_CYSCREEN) - height) / 2,
            width,
            height,
            None,
            None,
            Some(HINSTANCE(instance.0)),
            Some((&mut *state as *mut Window).cast()),
        )
        .map_err(|e| e.to_string())?;
        state.hwnd = hwnd;
        let dpi = GetDpiForWindow(hwnd).max(96);
        state.scale = dpi as f32 / 96.0;
        let width = (420.0 * state.scale) as i32;
        let height = (720.0 * state.scale) as i32;
        let _ = SetWindowPos(
            hwnd,
            None,
            (GetSystemMetrics(SM_CXSCREEN) - width) / 2,
            (GetSystemMetrics(SM_CYSCREEN) - height) / 2,
            width,
            height,
            SWP_NOZORDER,
        );
        update_region(hwnd, state.scale);
        sender.hwnd.store(hwnd.0 as usize, Ordering::Release);
        if autostart {
            state.visible = false;
            state.controller.set_visible(false);
        } else {
            let _ = ShowWindow(hwnd, SW_SHOW);
        }
        state.refresh();
        let timer = FrameTimer::new()?;
        let mut next_frame = Instant::now();
        let mut frame_log = if preview {
            std::env::var_os("WARPY_NATIVE_FRAME_LOG").map(PathBuf::from)
        } else {
            None
        };
        let measured_at = Instant::now();
        let mut frame_samples = Vec::new();
        let mut message = MSG::default();
        'messages: loop {
            for _ in 0..64 {
                if !PeekMessageW(&mut message, None, 0, 0, PM_REMOVE).as_bool() {
                    break;
                }
                if message.message == WM_QUIT {
                    break 'messages;
                }
                let _ = TranslateMessage(&message);
                DispatchMessageW(&message);
            }
            let animated = state.visible && state.scene.animated();
            if animated {
                if Instant::now() >= next_frame {
                    let start = Instant::now();
                    let _ = InvalidateRect(Some(hwnd), None, false);
                    let _ = UpdateWindow(hwnd);
                    if frame_log.is_some() {
                        frame_samples.push((
                            measured_at.elapsed().as_secs_f64() * 1000.0,
                            start.elapsed().as_secs_f64() * 1000.0,
                        ));
                    }
                    next_frame += FRAME_TIME;
                    while next_frame <= Instant::now() {
                        next_frame += FRAME_TIME;
                    }
                }
                timer.arm(next_frame.saturating_duration_since(Instant::now()))?;
            } else {
                windows_sys::Win32::System::Threading::CancelWaitableTimer(timer.0);
                next_frame = Instant::now();
            }
            if measured_at.elapsed().as_secs() >= 30 {
                if let Some(path) = frame_log.take() {
                    let _ = fs::write(path, serde_json::to_vec(&frame_samples).unwrap_or_default());
                }
            }
            let handles = [HANDLE(timer.0)];
            let result = MsgWaitForMultipleObjectsEx(
                if animated { Some(&handles) } else { None },
                u32::MAX,
                QS_ALLINPUT,
                MWMO_INPUTAVAILABLE,
            );
            if result.0 == u32::MAX {
                return Err(windows::core::Error::from_win32().to_string());
            }
        }
        sender.hwnd.store(0, Ordering::Release);
        Ok(())
    }
}

unsafe fn update_region(hwnd: HWND, scale: f32) {
    let region = CreateRoundRectRgn(
        (20.0 * scale) as i32,
        (20.0 * scale) as i32,
        (400.0 * scale) as i32,
        (700.0 * scale) as i32,
        (72.0 * scale) as i32,
        (72.0 * scale) as i32,
    );
    let _ = SetWindowRgn(hwnd, Some(region), true);
}

const UNICODE_TEXT: u32 = 13;
struct Clipboard;
impl Clipboard {
    fn open(owner: Option<HWND>) -> Result<Self, String> {
        unsafe {
            OpenClipboard(owner).map_err(|e| e.to_string())?;
        }
        Ok(Self)
    }
}
impl Drop for Clipboard {
    fn drop(&mut self) {
        unsafe {
            let _ = CloseClipboard();
        }
    }
}
pub(super) fn read_clipboard() -> Result<String, String> {
    let _clipboard = Clipboard::open(None)?;
    unsafe {
        if IsClipboardFormatAvailable(UNICODE_TEXT).is_err() {
            return Ok(String::new());
        }
        let memory = GetClipboardData(UNICODE_TEXT).map_err(|e| e.to_string())?;
        let data = GlobalLock(HGLOBAL(memory.0)) as *const u16;
        if data.is_null() {
            return Err("Cannot read clipboard".to_string());
        }
        let max = GlobalSize(HGLOBAL(memory.0)) / 2;
        let data = std::slice::from_raw_parts(data, max.min(MAX_SETTINGS_BYTES / 2));
        let end = data
            .iter()
            .position(|value| *value == 0)
            .unwrap_or(data.len());
        let value = String::from_utf16_lossy(&data[..end]);
        let _ = GlobalUnlock(HGLOBAL(memory.0));
        Ok(value)
    }
}
pub(super) fn write_clipboard(value: &str, owner: HWND) -> Result<(), String> {
    let _clipboard = Clipboard::open(Some(owner))?;
    unsafe {
        let value: Vec<u16> = value.encode_utf16().chain(Some(0)).collect();
        let memory = GlobalAlloc(GMEM_MOVEABLE, value.len() * 2).map_err(|e| e.to_string())?;
        let data = GlobalLock(memory) as *mut u16;
        if data.is_null() {
            let _ = windows::Win32::Foundation::GlobalFree(Some(memory));
            return Err("Cannot write clipboard".to_string());
        }
        ptr::copy_nonoverlapping(value.as_ptr(), data, value.len());
        let _ = GlobalUnlock(memory);
        if let Err(error) = EmptyClipboard() {
            let _ = windows::Win32::Foundation::GlobalFree(Some(memory));
            return Err(error.to_string());
        }
        if let Err(error) = SetClipboardData(
            UNICODE_TEXT,
            Some(windows::Win32::Foundation::HANDLE(memory.0)),
        ) {
            let _ = windows::Win32::Foundation::GlobalFree(Some(memory));
            return Err(error.to_string());
        }
        Ok(())
    }
}
