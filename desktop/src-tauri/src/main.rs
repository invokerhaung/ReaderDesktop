#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::{
    collections::HashMap,
    error::Error,
    fs,
    io::{Read, Write},
    net::{SocketAddr, TcpListener, TcpStream},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{mpsc, Arc, Mutex, OnceLock},
    time::{Duration, Instant},
};

use serde::{Deserialize, Serialize};
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, TrayIconBuilder, TrayIconEvent},
    Manager, RunEvent, WebviewUrl, WebviewWindowBuilder, WindowEvent,
};
use tauri_plugin_global_shortcut::{GlobalShortcutExt, ShortcutState};
use uuid::Uuid;

const BOSS_KEY: &str = "Ctrl+Alt+H";
const FLOAT_HOTKEY_DEFAULT: &str = "Ctrl+Alt+X";
const FLOAT_MIN_WIDTH: f64 = 300.0;
const FLOAT_MIN_HEIGHT: f64 = 200.0;
const FLOAT_DEFAULT_WIDTH: u32 = 460;
const FLOAT_DEFAULT_HEIGHT: u32 = 620;

struct ServerProcess {
    child: Mutex<Option<Child>>,
    job: Mutex<Option<usize>>,
    port: u16,
    token: String,
}

impl ServerProcess {
    fn stop(&self) {
        if let Some(mut child) = self.child.lock().ok().and_then(|mut slot| slot.take()) {
            let _ = child.kill();
            let _ = child.wait();
        }
        if let Some(job) = self.job.lock().ok().and_then(|mut slot| slot.take()) {
            close_job(job);
        }
    }
}

impl Drop for ServerProcess {
    fn drop(&mut self) {
        self.stop();
    }
}

/// 浮窗持久化配置（几何信息使用逻辑像素）。
#[derive(Clone, Default, Serialize, Deserialize)]
struct FloatConfig {
    #[serde(default)]
    hotkey: Option<String>,
    #[serde(default)]
    always_on_top: Option<bool>,
    #[serde(default)]
    emergency_hide: Option<bool>,
    #[serde(default)]
    x: Option<i32>,
    #[serde(default)]
    y: Option<i32>,
    #[serde(default)]
    width: Option<u32>,
    #[serde(default)]
    height: Option<u32>,
}

struct FloatRuntime {
    file: PathBuf,
    config: Mutex<FloatConfig>,
    registered_hotkey: Mutex<Option<String>>,
    hotkey_error: Mutex<Option<String>>,
    geometry_save_scheduled: Mutex<bool>,
}

impl FloatRuntime {
    fn load(file: PathBuf) -> Self {
        let config = fs::read_to_string(&file)
            .ok()
            .and_then(|text| serde_json::from_str::<FloatConfig>(&text).ok())
            .unwrap_or_default();
        FloatRuntime {
            file,
            config: Mutex::new(config),
            registered_hotkey: Mutex::new(None),
            hotkey_error: Mutex::new(None),
            geometry_save_scheduled: Mutex::new(false),
        }
    }

    fn snapshot(&self) -> FloatConfig {
        self.config
            .lock()
            .map(|guard| guard.clone())
            .unwrap_or_default()
    }

    fn update_memory<F: FnOnce(&mut FloatConfig)>(&self, edit: F) {
        if let Ok(mut guard) = self.config.lock() {
            edit(&mut guard);
        }
    }

    fn flush(&self) {
        let Ok(guard) = self.config.lock() else { return };
        if let Ok(text) = serde_json::to_string_pretty(&*guard) {
            let _ = fs::write(&self.file, text);
        }
    }

    fn update<F: FnOnce(&mut FloatConfig)>(&self, edit: F) {
        self.update_memory(edit);
        self.flush();
    }
}

/// 主窗口所在 JVM 服务的地址，浮窗通过 /bootstrap 复用同一个服务与登录态。
struct ShellState {
    server_port: u16,
    server_token: String,
}

fn float_runtime(app: &tauri::AppHandle) -> Option<Arc<FloatRuntime>> {
    app.try_state::<Arc<FloatRuntime>>()
        .map(|state| state.inner().clone())
}

fn main() {
    let builder = tauri::Builder::default()
        .plugin(tauri_plugin_global_shortcut::Builder::new().build());
    // 调试壳允许与正式安装版并行运行，方便不改安装包直接验证改动
    #[cfg(not(debug_assertions))]
    let builder = builder.plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
        if let Some(window) = app.get_webview_window("main") {
            let _ = window.show();
            let _ = window.unminimize();
            let _ = window.set_focus();
        }
    }));
    builder
        .setup(|app| {
            let data_dir = resolve_data_dir(app.handle())?;
            fs::create_dir_all(&data_dir)?;
            let float_runtime = Arc::new(FloatRuntime::load(data_dir.join("float-window.json")));
            app.manage(float_runtime.clone());
            let bridge = start_webview_bridge(app.handle().clone()).ok();
            if let Some(bridge) = &bridge {
                app.manage(BridgeState {
                    port: bridge.port(),
                    token: bridge.token.clone(),
                });
            }
            let server = start_server(app.handle(), bridge.as_ref())?;
            let url = format!(
                "http://127.0.0.1:{}/bootstrap?token={}",
                server.port, server.token
            );
            let parsed: url::Url = url.parse()?;
            let window = WebviewWindowBuilder::new(app, "main", WebviewUrl::External(parsed))
                .title("墨读")
                .inner_size(1280.0, 860.0)
                .min_inner_size(960.0, 640.0)
                .center()
                .build()?;
            let _ = window.set_focus();
            app.manage(ShellState {
                server_port: server.port,
                server_token: server.token.clone(),
            });
            app.manage(server);
            setup_tray(app)?;
            let _ = app.global_shortcut().on_shortcut(BOSS_KEY, |app, _, event| {
                if event.state == ShortcutState::Pressed {
                    toggle_main_window(app);
                }
            });
            let hotkey = float_runtime
                .snapshot()
                .hotkey
                .unwrap_or_else(|| FLOAT_HOTKEY_DEFAULT.to_string());
            if let Err(error) = apply_float_hotkey(app.handle(), &float_runtime, &hotkey) {
                if let Ok(mut slot) = float_runtime.hotkey_error.lock() {
                    *slot = Some(error);
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| match event {
            WindowEvent::CloseRequested { api, .. } if window.label() == "main" => {
                api.prevent_close();
                let _ = window.hide();
            }
            WindowEvent::CloseRequested { api, .. } if window.label() == "float" => {
                api.prevent_close();
                persist_float_geometry_from_event(window);
                let _ = window.hide();
            }
            WindowEvent::Moved(_) | WindowEvent::Resized(_) if window.label() == "float" => {
                persist_float_geometry_from_event(window);
            }
            _ => {}
        })
        .build(tauri::generate_context!())
        .expect("failed to build Legado Desktop shell")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                if let Some(server) = app.try_state::<ServerProcess>() {
                    server.stop();
                }
            }
        });
}

fn toggle_main_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        if window.is_visible().unwrap_or(false) {
            let _ = window.hide();
        } else {
            let _ = window.show();
            let _ = window.unminimize();
            let _ = window.set_focus();
        }
    }
}

fn show_main_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn toggle_float(app: &tauri::AppHandle) {
    match app.get_webview_window("float") {
        Some(window) => {
            if window.is_visible().unwrap_or(false) {
                hide_float(app, &window, true);
            } else {
                let _ = window.show();
                let _ = window.set_focus();
                ensure_tool_window(&window);
            }
        }
        None => {
            if let Err(error) = open_float_window(app, None, None) {
                eprintln!("打开浮窗失败：{error}");
            }
        }
    }
}

fn hide_float(app: &tauri::AppHandle, window: &tauri::WebviewWindow, emergency: bool) {
    persist_float_geometry(window);
    let _ = window.hide();
    let emergency = emergency
        && float_runtime(app)
            .map(|runtime| runtime.snapshot().emergency_hide.unwrap_or(false))
            .unwrap_or(false);
    if emergency {
        if let Some(main) = app.get_webview_window("main") {
            let _ = main.hide();
        }
    }
}

fn setup_tray(app: &tauri::App) -> Result<(), Box<dyn Error>> {
    let show = MenuItem::with_id(app, "show", "显示主窗口", true, None::<&str>)?;
    let hide = MenuItem::with_id(app, "hide", "隐藏主窗口", true, None::<&str>)?;
    let float = MenuItem::with_id(app, "float", "浮窗显隐", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &hide, &float, &quit])?;
    let mut builder = TrayIconBuilder::new()
        .tooltip("墨读 Legado Desktop")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "show" => show_main_window(app),
            "hide" => {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.hide();
                }
            }
            "float" => toggle_float(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::DoubleClick {
                button: MouseButton::Left,
                ..
            } = event
            {
                show_main_window(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        builder = builder.icon(icon.clone());
    }
    builder.build(app)?;
    Ok(())
}

fn float_window_url(app: &tauri::AppHandle, query: Option<&str>) -> Result<url::Url, String> {
    let shell = app
        .try_state::<ShellState>()
        .ok_or_else(|| "JVM 服务尚未就绪".to_string())?;
    let base = format!(
        "http://127.0.0.1:{}/bootstrap?token={}",
        shell.server_port, shell.server_token
    );
    let mut url = url::Url::parse(&base).map_err(|e| e.to_string())?;
    let redirect = match query {
        Some(value) if !value.is_empty() => format!("/float?{value}"),
        _ => "/float".to_string(),
    };
    url.query_pairs_mut().append_pair("redirect", &redirect);
    Ok(url)
}

fn float_default_position(
    app: &tauri::AppHandle,
    width: f64,
    _height: f64,
) -> Option<(f64, f64)> {
    let monitor = app.primary_monitor().ok().flatten()?;
    let scale = if monitor.scale_factor() > 0.0 {
        monitor.scale_factor()
    } else {
        1.0
    };
    let position = monitor.position();
    let size = monitor.size();
    let screen_width = size.width as f64 / scale;
    let x = position.x as f64 / scale + screen_width - width - 48.0;
    let y = position.y as f64 / scale + 72.0;
    Some((x.max(0.0), y.max(0.0)))
}

fn build_float_window(
    app: &tauri::AppHandle,
    config: &FloatConfig,
    url: url::Url,
    top: bool,
) -> Result<(), String> {
    let width = config
        .width
        .unwrap_or(FLOAT_DEFAULT_WIDTH)
        .max(FLOAT_MIN_WIDTH as u32) as f64;
    let height = config
        .height
        .unwrap_or(FLOAT_DEFAULT_HEIGHT)
        .max(FLOAT_MIN_HEIGHT as u32) as f64;
    let mut builder = WebviewWindowBuilder::new(app, "float", WebviewUrl::External(url))
        .title("墨读浮窗")
        .inner_size(width, height)
        .min_inner_size(FLOAT_MIN_WIDTH, FLOAT_MIN_HEIGHT)
        .decorations(false)
        .transparent(true)
        .shadow(false)
        .skip_taskbar(true)
        .always_on_top(top)
        .resizable(true)
        .visible(false);
    if let (Some(x), Some(y)) = (config.x, config.y) {
        builder = builder.position(x as f64, y as f64);
    } else if let Some((x, y)) = float_default_position(app, width, height) {
        builder = builder.position(x, y);
    } else {
        builder = builder.center();
    }
    let window = builder
        .build()
        .map_err(|e| format!("创建浮窗失败：{e}"))?;
    let _ = window.show();
    let _ = window.set_focus();
    ensure_tool_window(&window);
    save_float_geometry(
        app,
        window.scale_factor().unwrap_or(1.0),
        window.outer_position().unwrap_or(tauri::PhysicalPosition::new(0, 0)),
        window.inner_size().unwrap_or(tauri::PhysicalSize::new(
            width as u32,
            height as u32,
        )),
        true,
    );
    Ok(())
}

fn open_float_window(
    app: &tauri::AppHandle,
    query: Option<&str>,
    top: Option<bool>,
) -> Result<(), String> {
    let runtime = float_runtime(app).ok_or_else(|| "浮窗配置未就绪".to_string())?;
    let config = runtime.snapshot();
    let top = top.or(config.always_on_top).unwrap_or(true);
    if let Some(window) = app.get_webview_window("float") {
        if let Some(query) = query.filter(|value| !value.is_empty()) {
            let url = float_window_url(app, Some(query))?;
            window
                .navigate(url)
                .map_err(|e| format!("加载浮窗内容失败：{e}"))?;
        }
        let _ = window.show();
        let _ = window.set_focus();
        let _ = window.set_always_on_top(top);
        ensure_tool_window(&window);
        return Ok(());
    }
    let url = float_window_url(app, query)?;
    let config_for_build = config.clone();
    let app_for_build = app.clone();
    let (tx, rx) = mpsc::channel::<Result<(), String>>();
    app.run_on_main_thread(move || {
        let result = build_float_window(&app_for_build, &config_for_build, url, top);
        let _ = tx.send(result);
    })
    .map_err(|e| format!("无法创建浮窗：{e}"))?;
    match rx.recv_timeout(Duration::from_secs(10)) {
        Ok(result) => result,
        Err(_) => Err("创建浮窗超时".to_string()),
    }
}

/// 让窗口不出现在任务栏与 Alt+Tab 中（WS_EX_TOOLWINDOW）。
#[cfg(windows)]
fn make_tool_window(window: &tauri::WebviewWindow) {
    if let Ok(handle) = window.hwnd() {
        win_style::make_tool(handle.0 as isize);
    }
}

#[cfg(not(windows))]
fn make_tool_window(_window: &tauri::WebviewWindow) {}

/// show()/set_always_on_top() 之后 Tauri 可能重设窗口扩展样式，延迟再补一次。
fn ensure_tool_window(window: &tauri::WebviewWindow) {
    make_tool_window(window);
    let window = window.clone();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(250));
        make_tool_window(&window);
    });
}

#[cfg(windows)]
mod win_style {
    const GWL_EXSTYLE: i32 = -20;
    const WS_EX_TOOLWINDOW: isize = 0x0000_0080;
    const WS_EX_APPWINDOW: isize = 0x0004_0000;
    const SWP_NOSIZE: u32 = 0x0001;
    const SWP_NOMOVE: u32 = 0x0002;
    const SWP_NOZORDER: u32 = 0x0004;
    const SWP_FRAMECHANGED: u32 = 0x0020;

    #[link(name = "user32")]
    extern "system" {
        fn GetWindowLongPtrW(hwnd: isize, index: i32) -> isize;
        fn SetWindowLongPtrW(hwnd: isize, index: i32, value: isize) -> isize;
        fn SetWindowPos(
            hwnd: isize,
            insert_after: isize,
            x: i32,
            y: i32,
            cx: i32,
            cy: i32,
            flags: u32,
        ) -> i32;
    }

    pub fn make_tool(hwnd: isize) {
        unsafe {
            let style = GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
            let next = (style | WS_EX_TOOLWINDOW) & !WS_EX_APPWINDOW;
            if next != style {
                SetWindowLongPtrW(hwnd, GWL_EXSTYLE, next);
                SetWindowPos(
                    hwnd,
                    0,
                    0,
                    0,
                    0,
                    0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED,
                );
            }
        }
    }
}

fn persist_float_geometry(window: &tauri::WebviewWindow) {
    let Ok(scale) = window.scale_factor() else { return };
    let Ok(position) = window.outer_position() else { return };
    let Ok(size) = window.inner_size() else { return };
    save_float_geometry(window.app_handle(), scale, position, size, true);
}

fn persist_float_geometry_from_event(window: &tauri::Window) {
    let Ok(scale) = window.scale_factor() else { return };
    let Ok(position) = window.outer_position() else { return };
    let Ok(size) = window.inner_size() else { return };
    save_float_geometry(window.app_handle(), scale, position, size, false);
}

fn save_float_geometry(
    app: &tauri::AppHandle,
    scale: f64,
    position: tauri::PhysicalPosition<i32>,
    size: tauri::PhysicalSize<u32>,
    force: bool,
) {
    let Some(runtime) = float_runtime(app) else { return };
    let scale = if scale > 0.0 { scale } else { 1.0 };
    let x = (position.x as f64 / scale).round() as i32;
    let y = (position.y as f64 / scale).round() as i32;
    let width = ((size.width as f64 / scale).round() as u32).max(FLOAT_MIN_WIDTH as u32);
    let height = ((size.height as f64 / scale).round() as u32).max(FLOAT_MIN_HEIGHT as u32);
    runtime.update_memory(|config| {
        config.x = Some(x);
        config.y = Some(y);
        config.width = Some(width);
        config.height = Some(height);
    });
    if force {
        runtime.flush();
        return;
    }
    let Ok(mut scheduled) = runtime.geometry_save_scheduled.lock() else {
        return;
    };
    if *scheduled {
        return;
    }
    *scheduled = true;
    drop(scheduled);
    let runtime = runtime.clone();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(350));
        if let Ok(mut scheduled) = runtime.geometry_save_scheduled.lock() {
            *scheduled = false;
        }
        runtime.flush();
    });
}

fn apply_float_hotkey(
    app: &tauri::AppHandle,
    runtime: &Arc<FloatRuntime>,
    hotkey: &str,
) -> Result<(), String> {
    let hotkey = hotkey.trim();
    if hotkey.is_empty() {
        return Err("快捷键不能为空".to_string());
    }
    let previous = runtime
        .registered_hotkey
        .lock()
        .ok()
        .and_then(|guard| guard.clone());
    if previous.as_deref() == Some(hotkey) {
        return Ok(());
    }
    let result = app
        .global_shortcut()
        .on_shortcut(hotkey, |app, _, event| {
            if event.state == ShortcutState::Pressed {
                toggle_float(app);
            }
        })
        .map_err(|e| format!("快捷键注册失败（可能被其他程序占用）：{e}"));
    match result {
        Ok(()) => {
            if let Some(previous) = previous {
                if let Ok(shortcut) = previous.parse::<tauri_plugin_global_shortcut::Shortcut>() {
                    let _ = app.global_shortcut().unregister(shortcut);
                }
            }
            if let Ok(mut slot) = runtime.registered_hotkey.lock() {
                *slot = Some(hotkey.to_string());
            }
            if let Ok(mut slot) = runtime.hotkey_error.lock() {
                *slot = None;
            }
            runtime.update(|config| config.hotkey = Some(hotkey.to_string()));
            Ok(())
        }
        Err(error) => {
            if let Ok(mut slot) = runtime.hotkey_error.lock() {
                *slot = Some(error.clone());
            }
            Err(error)
        }
    }
}

fn float_query_string(payload: &serde_json::Value) -> String {
    let mut serializer = url::form_urlencoded::Serializer::new(String::new());
    for key in ["bookUrl", "index", "page", "percent", "chapterTitle"] {
        let Some(value) = payload.get(key) else { continue };
        let text = match value {
            serde_json::Value::String(text) => text.clone(),
            serde_json::Value::Number(number) => number.to_string(),
            _ => continue,
        };
        if !text.is_empty() {
            serializer.append_pair(key, &text);
        }
    }
    serializer.finish()
}

fn float_state(app: &tauri::AppHandle) -> serde_json::Value {
    let runtime = float_runtime(app);
    let config = runtime
        .as_ref()
        .map(|runtime| runtime.snapshot())
        .unwrap_or_default();
    let window = app.get_webview_window("float");
    let visible = window
        .as_ref()
        .and_then(|window| window.is_visible().ok())
        .unwrap_or(false);
    let always_on_top = config.always_on_top.unwrap_or(true);
    let mut x = config.x;
    let mut y = config.y;
    let mut width = config.width.unwrap_or(FLOAT_DEFAULT_WIDTH);
    let mut height = config.height.unwrap_or(FLOAT_DEFAULT_HEIGHT);
    if let Some(window) = &window {
        if let Ok(scale) = window.scale_factor() {
            let scale = if scale > 0.0 { scale } else { 1.0 };
            if let Ok(position) = window.outer_position() {
                x = Some((position.x as f64 / scale).round() as i32);
                y = Some((position.y as f64 / scale).round() as i32);
            }
            if let Ok(size) = window.inner_size() {
                width = ((size.width as f64 / scale).round() as u32).max(FLOAT_MIN_WIDTH as u32);
                height = ((size.height as f64 / scale).round() as u32).max(FLOAT_MIN_HEIGHT as u32);
            }
        }
    }
    let hotkey = runtime
        .as_ref()
        .and_then(|runtime| runtime.registered_hotkey.lock().ok().and_then(|guard| guard.clone()))
        .or(config.hotkey);
    let hotkey_error = runtime
        .as_ref()
        .and_then(|runtime| runtime.hotkey_error.lock().ok().and_then(|guard| guard.clone()));
    serde_json::json!({
        "open": window.is_some(),
        "visible": visible,
        "alwaysOnTop": always_on_top,
        "hotkey": hotkey,
        "hotkeyError": hotkey_error,
        "emergencyHide": config.emergency_hide.unwrap_or(false),
        "x": x,
        "y": y,
        "width": width,
        "height": height,
        "minWidth": FLOAT_MIN_WIDTH as u32,
        "minHeight": FLOAT_MIN_HEIGHT as u32,
    })
}

fn resize_direction(value: &str) -> Option<tauri_runtime::ResizeDirection> {
    use tauri_runtime::ResizeDirection::*;
    match value.trim().to_ascii_lowercase().as_str() {
        "n" | "north" => Some(North),
        "s" | "south" => Some(South),
        "e" | "east" => Some(East),
        "w" | "west" => Some(West),
        "ne" | "northeast" => Some(NorthEast),
        "nw" | "northwest" => Some(NorthWest),
        "se" | "southeast" => Some(SouthEast),
        "sw" | "southwest" => Some(SouthWest),
        _ => None,
    }
}

fn execute_float_action(
    app: &tauri::AppHandle,
    action: &str,
    payload: &serde_json::Value,
) -> Result<serde_json::Value, String> {
    let runtime = float_runtime(app).ok_or_else(|| "浮窗配置未就绪".to_string())?;
    match action {
        "open" => {
            let top = payload.get("top").and_then(|value| value.as_bool());
            if let Some(top) = top {
                runtime.update(|config| config.always_on_top = Some(top));
            }
            let query = float_query_string(payload);
            open_float_window(app, Some(&query), top)?;
            Ok(float_state(app))
        }
        "show" | "focus" => {
            if let Some(window) = app.get_webview_window("float") {
                let _ = window.show();
                let _ = window.set_focus();
                ensure_tool_window(&window);
            } else {
                open_float_window(app, None, None)?;
            }
            Ok(float_state(app))
        }
        "hide" | "close" => {
            if let Some(window) = app.get_webview_window("float") {
                hide_float(app, &window, false);
            }
            Ok(float_state(app))
        }
        "toggle" => {
            toggle_float(app);
            Ok(float_state(app))
        }
        "back" => {
            if let Some(window) = app.get_webview_window("float") {
                hide_float(app, &window, false);
            }
            show_main_window(app);
            Ok(float_state(app))
        }
        "settings" => {
            if let Some(window) = app.get_webview_window("float") {
                hide_float(app, &window, false);
            }
            show_main_window(app);
            if let Ok(shell) = app.try_state::<ShellState>().ok_or("服务未就绪") {
                let url = format!(
                    "http://127.0.0.1:{}/settings?section=float",
                    shell.server_port
                );
                if let (Ok(parsed), Some(main)) = (url::Url::parse(&url), app.get_webview_window("main")) {
                    let _ = main.navigate(parsed);
                }
            }
            Ok(float_state(app))
        }
        "top" => {
            let top = payload
                .get("top")
                .and_then(|value| value.as_bool())
                .unwrap_or(true);
            runtime.update(|config| config.always_on_top = Some(top));
            if let Some(window) = app.get_webview_window("float") {
                let _ = window.set_always_on_top(top);
            }
            Ok(float_state(app))
        }
        "prefs" => {
            let width = payload
                .get("width")
                .and_then(|value| value.as_u64())
                .map(|value| (value as u32).max(FLOAT_MIN_WIDTH as u32));
            let height = payload
                .get("height")
                .and_then(|value| value.as_u64())
                .map(|value| (value as u32).max(FLOAT_MIN_HEIGHT as u32));
            let top = payload.get("top").and_then(|value| value.as_bool());
            let emergency = payload
                .get("emergencyHide")
                .and_then(|value| value.as_bool());
            let apply_size = payload
                .get("applySize")
                .and_then(|value| value.as_bool())
                .unwrap_or(false);
            if width.is_some() || height.is_some() || top.is_some() || emergency.is_some() {
                runtime.update(|config| {
                    if let Some(width) = width {
                        config.width = Some(width);
                    }
                    if let Some(height) = height {
                        config.height = Some(height);
                    }
                    if let Some(top) = top {
                        config.always_on_top = Some(top);
                    }
                    if let Some(emergency) = emergency {
                        config.emergency_hide = Some(emergency);
                    }
                });
            }
            if let Some(window) = app.get_webview_window("float") {
                if apply_size {
                    if let (Some(width), Some(height)) = (width, height) {
                        let _ = window.set_size(tauri::LogicalSize::new(width as f64, height as f64));
                    }
                }
                if let Some(top) = top {
                    let _ = window.set_always_on_top(top);
                }
            }
            Ok(float_state(app))
        }
        "hotkey" => {
            let hotkey = payload
                .get("hotkey")
                .and_then(|value| value.as_str())
                .ok_or_else(|| "缺少 hotkey".to_string())?;
            apply_float_hotkey(app, &runtime, hotkey)?;
            Ok(float_state(app))
        }
        "drag" => {
            let window = app
                .get_webview_window("float")
                .ok_or_else(|| "浮窗尚未打开".to_string())?;
            window
                .start_dragging()
                .map_err(|e| format!("拖动失败：{e}"))?;
            Ok(serde_json::json!({}))
        }
        "resize" => {
            let direction = payload
                .get("dir")
                .and_then(|value| value.as_str())
                .and_then(resize_direction)
                .ok_or_else(|| "未知的缩放方向".to_string())?;
            let window = app
                .get_webview_window("float")
                .ok_or_else(|| "浮窗尚未打开".to_string())?;
            let webview: &tauri::Webview = window.as_ref();
            webview
                .window()
                .start_resize_dragging(direction)
                .map_err(|e| format!("缩放失败：{e}"))?;
            Ok(serde_json::json!({}))
        }
        "reset" => {
            runtime.update(|config| {
                config.x = None;
                config.y = None;
                config.width = Some(FLOAT_DEFAULT_WIDTH);
                config.height = Some(FLOAT_DEFAULT_HEIGHT);
            });
            if let Some(window) = app.get_webview_window("float") {
                let _ = window.set_size(tauri::LogicalSize::new(
                    FLOAT_DEFAULT_WIDTH as f64,
                    FLOAT_DEFAULT_HEIGHT as f64,
                ));
                match float_default_position(
                    app,
                    FLOAT_DEFAULT_WIDTH as f64,
                    FLOAT_DEFAULT_HEIGHT as f64,
                ) {
                    Some((x, y)) => {
                        let _ = window.set_position(tauri::LogicalPosition::new(x, y));
                    }
                    None => {
                        let _ = window.center();
                    }
                }
            }
            Ok(float_state(app))
        }
        "state" => Ok(float_state(app)),
        other => Err(format!("未知浮窗操作：{other}")),
    }
}

fn handle_float_control(
    app: tauri::AppHandle,
    mut request: tiny_http::Request,
    token: &str,
    path: &str,
) {
    let authorized = request
        .headers()
        .iter()
        .any(|header| header.field.equiv("X-Bridge-Token") && header.value.as_str() == token);
    if !authorized {
        let _ = request.respond(tiny_http::Response::empty(401));
        return;
    }
    let action = path
        .trim_start_matches("/float/")
        .split('?')
        .next()
        .unwrap_or_default()
        .to_string();
    let mut body = String::new();
    let _ = Read::read_to_string(request.as_reader(), &mut body);
    let payload: serde_json::Value = if body.trim().is_empty() {
        serde_json::Value::Object(serde_json::Map::new())
    } else {
        serde_json::from_str(&body).unwrap_or_else(|_| serde_json::Value::Object(serde_json::Map::new()))
    };
    let response = match execute_float_action(&app, &action, &payload) {
        Ok(mut value) => {
            if let serde_json::Value::Object(ref mut map) = value {
                map.insert("ok".to_string(), serde_json::Value::Bool(true));
            }
            value
        }
        Err(error) => serde_json::json!({ "ok": false, "error": error }),
    };
    respond_json(request, serde_json::to_string(&response).unwrap_or_default());
}

fn respond_json(request: tiny_http::Request, body: String) {
    let header =
        tiny_http::Header::from_bytes(&b"Content-Type"[..], &b"application/json"[..]).unwrap();
    let _ = request.respond(
        tiny_http::Response::from_string(body)
            .with_status_code(200)
            .with_header(header),
    );
}

fn start_server(
    app: &tauri::AppHandle,
    bridge: Option<&WebViewBridge>,
) -> Result<ServerProcess, Box<dyn Error>> {
    let data_dir = resolve_data_dir(app)?;
    fs::create_dir_all(&data_dir)?;
    let resources = app.path().resource_dir()?;
    let java = locate_java(&resources)?;
    let server_dir = locate_server_dir(&resources)?;
    let static_dir = locate_static_dir(&resources)?;
    let log_dir = data_dir.join("logs");
    fs::create_dir_all(&log_dir)?;
    let log_path = log_dir.join("server.log");

    let listener = TcpListener::bind(("127.0.0.1", 0))?;
    let port = listener.local_addr()?.port();
    drop(listener);

    let token = Uuid::new_v4().simple().to_string();
    let log = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)?;
    let log_err = log.try_clone()?;
    let mut command = Command::new(&java);
    command
        .arg("-cp")
        .arg(format!("{}/*", clean_path(&server_dir.join("lib"))))
        .arg("io.legado.desktop.server.MainKt")
        .arg("--host")
        .arg("127.0.0.1")
        .arg("--port")
        .arg(port.to_string())
        .arg("--token")
        .arg(&token)
        .arg("--data-dir")
        .arg(clean_path(&data_dir))
        .arg("--static-dir")
        .arg(clean_path(&static_dir));
    if let Some(bridge) = bridge {
        command
            .env("LEGADO_WEBVIEW_BRIDGE", bridge.port().to_string())
            .env("LEGADO_WEBVIEW_TOKEN", bridge.token.clone());
    }
    command
        .current_dir(&server_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::from(log))
        .stderr(Stdio::from(log_err));
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x0800_0000);
    }

    let mut child = command
        .spawn()
        .map_err(|e| format!("无法启动 JVM 服务（{}）：{e}", java.display()))?;
    let job = assign_to_job(child.id());
    let deadline = Instant::now() + Duration::from_secs(40);
    while Instant::now() < deadline {
        if health(port) {
            return Ok(ServerProcess {
                child: Mutex::new(Some(child)),
                job: Mutex::new(job),
                port,
                token,
            });
        }
        if let Ok(Some(_)) = child.try_wait() {
            break;
        }
        std::thread::sleep(Duration::from_millis(150));
    }

    let _ = child.kill();
    let _ = child.wait();
    if let Some(job) = job {
        close_job(job);
    }
    Err(format!(
        "JVM 服务启动超时，请检查 Java 运行时与服务端文件。日志：{}",
        log_path.display()
    )
    .into())
}

fn clean_path(path: &Path) -> String {
    let text = path.to_string_lossy();
    text.strip_prefix(r"\\?\").unwrap_or(&text).to_string()
}

fn health(port: u16) -> bool {
    let address = SocketAddr::from(([127u8, 0, 0, 1], port));
    let Ok(mut stream) = TcpStream::connect_timeout(&address, Duration::from_millis(500)) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_millis(500)));
    if stream
        .write_all(b"GET /healthz HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
        .is_err()
    {
        return false;
    }
    let mut response = String::new();
    stream.read_to_string(&mut response).is_ok()
        && response.contains("200")
        && response.contains("\"status\":\"ok\"")
}

fn locate_java(resources: &Path) -> Result<PathBuf, Box<dyn Error>> {
    let packaged = resources.join("runtime").join("bin").join("javaw.exe");
    if packaged.is_file() {
        return Ok(packaged);
    }
    if let Ok(value) = std::env::var("LEGADO_JAVA") {
        let path = PathBuf::from(value);
        if path.is_file() {
            return Ok(path);
        }
    }
    if let Ok(home) = std::env::var("JAVA_HOME") {
        let path = Path::new(&home).join("bin").join("javaw.exe");
        if path.is_file() {
            return Ok(path);
        }
        let path = Path::new(&home).join("bin").join("java.exe");
        if path.is_file() {
            return Ok(path);
        }
    }
    Ok(PathBuf::from("java"))
}

fn resolve_data_dir(app: &tauri::AppHandle) -> Result<PathBuf, Box<dyn Error>> {
    #[cfg(debug_assertions)]
    if let Ok(value) = std::env::var("LEGADO_DATA_DIR") {
        if !value.trim().is_empty() {
            return Ok(PathBuf::from(value));
        }
    }
    Ok(app.path().app_data_dir()?)
}

fn repo_root() -> Option<PathBuf> {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(|path| path.parent())
        .map(|path| path.to_path_buf())
}

fn locate_server_dir(resources: &Path) -> Result<PathBuf, Box<dyn Error>> {
    if cfg!(debug_assertions) {
        if let Some(dev) = repo_root().map(|repo| {
            repo.join("server")
                .join("build")
                .join("install")
                .join("server")
        }) {
            if dev.join("lib").is_dir() {
                return Ok(dev);
            }
        }
    }
    let packaged = resources.join("server");
    if packaged.join("lib").is_dir() {
        return Ok(packaged);
    }
    let dev = repo_root()
        .ok_or_else(|| "无法定位仓库根目录".to_string())?
        .join("server")
        .join("build")
        .join("install")
        .join("server");
    if dev.join("lib").is_dir() {
        Ok(dev)
    } else {
        Err(
            "未找到 server/build/install/server，请先执行 ./gradlew :server:installDist"
                .to_string()
                .into(),
        )
    }
}

fn locate_static_dir(resources: &Path) -> Result<PathBuf, Box<dyn Error>> {
    if cfg!(debug_assertions) {
        if let Some(dev) = repo_root().map(|repo| repo.join("frontend").join("dist")) {
            if dev.join("index.html").is_file() {
                return Ok(dev);
            }
        }
    }
    let packaged = resources.join("frontend");
    if packaged.join("index.html").is_file() {
        return Ok(packaged);
    }
    let dev = repo_root()
        .ok_or_else(|| "无法定位仓库根目录".to_string())?
        .join("frontend")
        .join("dist");
    if dev.join("index.html").is_file() {
        Ok(dev)
    } else {
        Err("未找到 frontend/dist，请先执行 npm run build"
            .to_string()
            .into())
    }
}

#[cfg(windows)]
mod win_job {
    use std::ffi::c_void;

    type Handle = *mut c_void;

    const JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE: u32 = 0x0000_2000;
    const JOB_OBJECT_EXTENDED_LIMIT_INFORMATION: u32 = 9;
    const PROCESS_SET_QUOTA: u32 = 0x0100;
    const PROCESS_TERMINATE: u32 = 0x0001;

    #[repr(C)]
    struct BasicLimitInformation {
        per_process_user_time_limit: i64,
        per_job_user_time_limit: i64,
        limit_flags: u32,
        minimum_working_set_size: usize,
        maximum_working_set_size: usize,
        active_process_limit: u32,
        affinity: usize,
        priority_class: u32,
        scheduling_class: u32,
    }

    #[repr(C)]
    struct IoCounters {
        read_operation_count: u64,
        write_operation_count: u64,
        other_operation_count: u64,
        read_transfer_count: u64,
        write_transfer_count: u64,
        other_transfer_count: u64,
    }

    #[repr(C)]
    struct ExtendedLimitInformation {
        basic: BasicLimitInformation,
        io: IoCounters,
        process_memory_limit: usize,
        job_memory_limit: usize,
        peak_process_memory_used: usize,
        peak_job_memory_used: usize,
    }

    #[link(name = "kernel32")]
    extern "system" {
        fn CreateJobObjectW(attributes: *mut c_void, name: *const u16) -> Handle;
        fn SetInformationJobObject(job: Handle, class: u32, info: *mut c_void, len: u32) -> i32;
        fn AssignProcessToJobObject(job: Handle, process: Handle) -> i32;
        fn OpenProcess(access: u32, inherit: i32, pid: u32) -> Handle;
        fn CloseHandle(handle: Handle) -> i32;
    }

    pub fn assign(pid: u32) -> Option<usize> {
        unsafe {
            let job = CreateJobObjectW(std::ptr::null_mut(), std::ptr::null());
            if job.is_null() {
                return None;
            }
            let mut info: ExtendedLimitInformation = std::mem::zeroed();
            info.basic.limit_flags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            if SetInformationJobObject(
                job,
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                &mut info as *mut _ as *mut c_void,
                std::mem::size_of::<ExtendedLimitInformation>() as u32,
            ) == 0
            {
                CloseHandle(job);
                return None;
            }
            let process = OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, 0, pid);
            if process.is_null() {
                CloseHandle(job);
                return None;
            }
            let assigned = AssignProcessToJobObject(job, process);
            CloseHandle(process);
            if assigned == 0 {
                CloseHandle(job);
                return None;
            }
            Some(job as usize)
        }
    }

    pub fn close(handle: usize) {
        unsafe {
            CloseHandle(handle as Handle);
        }
    }
}

#[cfg(windows)]
fn assign_to_job(pid: u32) -> Option<usize> {
    win_job::assign(pid)
}

#[cfg(windows)]
fn close_job(handle: usize) {
    win_job::close(handle);
}

#[cfg(not(windows))]
fn assign_to_job(_pid: u32) -> Option<usize> {
    None
}

#[cfg(not(windows))]
fn close_job(_handle: usize) {}

struct WebViewBridge {
    server: Arc<tiny_http::Server>,
    token: String,
}

#[derive(Clone)]
struct BridgeState {
    port: u16,
    token: String,
}

impl WebViewBridge {
    fn port(&self) -> u16 {
        self.server
            .server_addr()
            .to_ip()
            .map(|addr| addr.port())
            .unwrap_or(0)
    }
}

#[derive(Deserialize)]
struct CookieBridgeRequest {
    url: String,
    #[serde(default = "default_bridge_mode")]
    mode: String,
    #[serde(default = "default_bridge_timeout")]
    timeout_ms: u64,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    user_agent: Option<String>,
    #[serde(default)]
    java_script: Option<String>,
    #[serde(default)]
    source_regex: Option<String>,
    #[serde(default)]
    override_regex: Option<String>,
    #[serde(default)]
    delay_ms: Option<u64>,
    #[serde(default)]
    report_fallback_ms: Option<u64>,
    #[serde(default)]
    reuse_window: bool,
    #[serde(default)]
    visible_window: bool,
}

fn default_bridge_mode() -> String {
    "challenge".to_string()
}

fn default_bridge_timeout() -> u64 {
    120_000
}

#[derive(Serialize)]
struct CookieItem {
    domain: Option<String>,
    name: String,
    value: String,
    path: Option<String>,
    secure: bool,
    #[serde(rename = "httpOnly")]
    http_only: bool,
}

#[derive(Serialize)]
struct BridgeResponse {
    cookies: Vec<CookieItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    value: Option<String>,
    #[serde(rename = "finalUrl", skip_serializing_if = "Option::is_none")]
    final_url: Option<String>,
    error: Option<String>,
}

impl BridgeResponse {
    fn error(message: impl Into<String>) -> Self {
        BridgeResponse {
            cookies: vec![],
            value: None,
            final_url: None,
            error: Some(message.into()),
        }
    }
}

struct WebviewOutcome {
    cookies: Vec<CookieItem>,
    value: Option<String>,
    final_url: Option<String>,
}

type ReportSender = mpsc::Sender<(String, String)>;

fn pending_reports() -> &'static Arc<Mutex<HashMap<String, ReportSender>>> {
    static PENDING: OnceLock<Arc<Mutex<HashMap<String, ReportSender>>>> = OnceLock::new();
    PENDING.get_or_init(|| Arc::new(Mutex::new(HashMap::new())))
}

fn start_webview_bridge(app: tauri::AppHandle) -> Result<WebViewBridge, Box<dyn Error>> {
    let server = Arc::new(tiny_http::Server::http(("127.0.0.1", 0)).map_err(|e| e.to_string())?);
    let token = Uuid::new_v4().simple().to_string();
    let bridge = WebViewBridge {
        server: server.clone(),
        token: token.clone(),
    };
    std::thread::spawn(move || loop {
        match server.recv() {
            Ok(request) => {
                let app = app.clone();
                let token = token.clone();
                std::thread::spawn(move || handle_bridge_request(app, token, request));
            }
            Err(_) => break,
        }
    });
    Ok(bridge)
}

fn handle_bridge_request(app: tauri::AppHandle, token: String, mut request: tiny_http::Request) {
    let path = request.url().to_string();
    if path.starts_with("/report") {
        handle_report(request, &token, &path);
        return;
    }
    if path.starts_with("/window-theme") {
        handle_window_theme(app, request, &token, &path);
        return;
    }
    if path.starts_with("/float/") {
        handle_float_control(app, request, &token, &path);
        return;
    }
    let authorized = request
        .headers()
        .iter()
        .any(|h| h.field.equiv("X-Bridge-Token") && h.value.as_str() == token);
    if !authorized {
        respond_bridge(request, 401, BridgeResponse::error("unauthorized"));
        return;
    }
    let mut body = String::new();
    if Read::read_to_string(request.as_reader(), &mut body).is_err() {
        respond_bridge(request, 400, BridgeResponse::error("无法读取请求体"));
        return;
    }
    let req: CookieBridgeRequest = match serde_json::from_str(&body) {
        Ok(req) => req,
        Err(e) => {
            respond_bridge(request, 400, BridgeResponse::error(format!("请求格式错误：{e}")));
            return;
        }
    };
    let timeout = Duration::from_millis(req.timeout_ms.clamp(5_000, 600_000));
    let response = match run_webview_task(&app, &req, timeout) {
        Ok(outcome) => BridgeResponse {
            cookies: outcome.cookies,
            value: outcome.value,
            final_url: outcome.final_url,
            error: None,
        },
        Err(err) => BridgeResponse {
            cookies: vec![],
            value: None,
            final_url: None,
            error: Some(err),
        },
    };
    respond_bridge(request, 200, response);
}

#[derive(Deserialize)]
struct WindowThemeRequest {
    theme: String,
}

/** 让 Windows 原生标题栏跟随应用主题（浅色/深色）。 */
fn handle_window_theme(
    app: tauri::AppHandle,
    mut request: tiny_http::Request,
    token: &str,
    path: &str,
) {
    let authorized = request
        .headers()
        .iter()
        .any(|h| h.field.equiv("X-Bridge-Token") && h.value.as_str() == token)
        || query_param(path, "token").as_deref() == Some(token);
    if !authorized {
        let _ = request.respond(tiny_http::Response::empty(401));
        return;
    }
    let mut body = String::new();
    let _ = Read::read_to_string(request.as_reader(), &mut body);
    let dark = serde_json::from_str::<WindowThemeRequest>(&body)
        .map(|req| !req.theme.eq_ignore_ascii_case("light"))
        .unwrap_or(true);
    let app_for_theme = app.clone();
    let _ = app.run_on_main_thread(move || {
        if let Some(window) = app_for_theme.get_webview_window("main") {
            let theme = if dark {
                tauri::Theme::Dark
            } else {
                tauri::Theme::Light
            };
            let _ = window.set_theme(Some(theme));
        }
    });
    let header =
        tiny_http::Header::from_bytes(&b"Content-Type"[..], &b"application/json"[..]).unwrap();
    let _ = request.respond(
        tiny_http::Response::from_string(r#"{"ok":true}"#)
            .with_status_code(200)
            .with_header(header),
    );
}

fn handle_report(mut request: tiny_http::Request, token: &str, path: &str) {    if query_param(path, "token").as_deref() != Some(token) {
        let _ = request.respond(tiny_http::Response::empty(401));
        return;
    }
    let Some(id) = query_param(path, "id") else {
        let _ = request.respond(tiny_http::Response::empty(400));
        return;
    };
    let final_url = query_param(path, "u").unwrap_or_default();
    let mut body = String::new();
    let _ = Read::read_to_string(request.as_reader(), &mut body);
    // text/plain 表单以 "d=" 开头，换行被编码为 CRLF
    let payload = if let Some(rest) = body.strip_prefix("d=") {
        rest.replace("\r\n", "\n")
    } else {
        body
    };
    let sender = pending_reports().lock().ok().and_then(|mut map| map.remove(&id));
    if let Some(sender) = sender {
        let _ = sender.send((final_url, payload));
        let _ = request.respond(tiny_http::Response::empty(204));
    } else {
        let _ = request.respond(tiny_http::Response::empty(404));
    }
}

fn query_param(url: &str, key: &str) -> Option<String> {
    let query = url.split_once('?')?.1;
    query.split('&').find_map(|pair| {
        let (k, v) = pair.split_once('=')?;
        if k == key {
            Some(percent_decode(v))
        } else {
            None
        }
    })
}

fn percent_decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("");
                if let Ok(byte) = u8::from_str_radix(hex, 16) {
                    out.push(byte);
                    i += 3;
                    continue;
                }
                out.push(bytes[i]);
                i += 1;
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            byte => {
                out.push(byte);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).to_string()
}

fn respond_bridge(request: tiny_http::Request, status: u16, payload: BridgeResponse) {
    let json = serde_json::to_string(&payload)
        .unwrap_or_else(|_| r#"{"cookies":[],"error":"serialize"}"#.to_string());
    let header =
        tiny_http::Header::from_bytes(&b"Content-Type"[..], &b"application/json"[..]).unwrap();
    let response = tiny_http::Response::from_string(json)
        .with_status_code(status)
        .with_header(header);
    let _ = request.respond(response);
}

/// 注入脚本：定义 __legadoReport，按模式安装嗅探钩子。
fn report_script(
    endpoint: &str,
    mode: &str,
    source_regex: Option<&str>,
    override_regex: Option<&str>,
) -> String {
    let hooks = match mode {
        "source" => {
            let regex = source_regex
                .map(|value| serde_json::to_string(value).unwrap_or_else(|_| "\"\"".into()))
                .unwrap_or_else(|| "null".into());
            SOURCE_HOOKS.replace("__REGEX__", &regex)
        }
        "override" => {
            let regex = override_regex
                .map(|value| serde_json::to_string(value).unwrap_or_else(|_| "\"\"".into()))
                .unwrap_or_else(|| "null".into());
            OVERRIDE_HOOKS.replace("__REGEX__", &regex)
        }
        _ => String::new(),
    };
    REPORT_TEMPLATE
        .replace("__EP__", endpoint)
        .replace("__HOOKS__", &hooks)
}

const REPORT_TEMPLATE: &str = r#"
(function(){
  if (window.top !== window.self) return;
  if (String(location.host).indexOf('challenges.cloudflare.com') >= 0) return;
  var EP = "__EP__";
  var sent = false;
  window.__legadoReport = function(v){
    if (sent) return;
    sent = true;
    var text = String(v == null ? '' : v);
    try {
      var frameName = '__legado_report_frame';
      var frame = document.createElement('iframe');
      frame.name = frameName;
      frame.style.display = 'none';
      var form = document.createElement('form');
      form.method = 'POST';
      form.action = EP + '&u=' + encodeURIComponent(location.href);
      form.target = frameName;
      form.enctype = 'text/plain';
      form.style.display = 'none';
      var input = document.createElement('input');
      input.type = 'hidden';
      input.name = 'd';
      input.value = text;
      form.appendChild(input);
      document.documentElement.appendChild(frame);
      document.documentElement.appendChild(form);
      form.submit();
      return;
    } catch (e) {}
    try {
      var x = new XMLHttpRequest();
      x.open('POST', EP, true);
      x.setRequestHeader('Content-Type', 'text/plain;charset=UTF-8');
      x.send(text);
    } catch (e) {}
  };
  __HOOKS__
})();
"#;

/// Rust 侧定时 eval：直接读取主 frame 的 DOM，用表单 POST 回传（不受页面 CSP 限制）。
fn poll_report_script(endpoint: &str, force: bool, host: &str, expected: &str) -> String {
    let ep = serde_json::to_string(endpoint).unwrap_or_else(|_| "\"\"".into());
    let host = serde_json::to_string(host).unwrap_or_else(|_| "\"\"".into());
    let expected = serde_json::to_string(expected).unwrap_or_else(|_| "\"\"".into());
    let force_literal = if force { "true" } else { "false" };
    format!(
        r#"(function(){{
  if (window.__legadoSent) return;
  var sameHost = !{host} || String(location.host) === {host};
  var sameUrl = !{expected} || String(location.href).indexOf({expected}) === 0;
  if (!(sameHost && sameUrl) && !{force}) return;
  var text = document.body ? document.body.innerText : '';
  var challenge = /just a moment|enable javascript|cf-chl|attention required/i.test(text);
  if (!((text && text.length > 200 && !challenge) || {force})) return;
  window.__legadoSent = true;
  var html = document.documentElement ? document.documentElement.outerHTML : '';
  try {{
    var ep = {ep} + '&u=' + encodeURIComponent(location.href);
    var frameName = '__legado_poll_frame';
    var frame = document.createElement('iframe');
    frame.name = frameName;
    frame.style.display = 'none';
    var form = document.createElement('form');
    form.method = 'POST';
    form.action = ep;
    form.target = frameName;
    form.enctype = 'text/plain';
    form.style.display = 'none';
    var input = document.createElement('input');
    input.type = 'hidden';
    input.name = 'd';
    input.value = html;
    form.appendChild(input);
    document.documentElement.appendChild(frame);
    document.documentElement.appendChild(form);
    form.submit();
  }} catch (e) {{}}
}})();"#,
        ep = ep,
        host = host,
        expected = expected,
        force = force_literal
    )
}

fn session_windows() -> &'static Arc<Mutex<HashMap<String, tauri::WebviewWindow>>> {
    static SESSIONS: OnceLock<Arc<Mutex<HashMap<String, tauri::WebviewWindow>>>> = OnceLock::new();
    SESSIONS.get_or_init(|| Arc::new(Mutex::new(HashMap::new())))
}

fn spawn_poll(
    window: tauri::WebviewWindow,
    endpoint: String,
    fallback_ms: u64,
    host: String,
    expected: String,
) {
    std::thread::spawn(move || {
        let started = Instant::now();
        loop {
            std::thread::sleep(Duration::from_millis(2_000));
            let force = started.elapsed().as_millis() as u64 >= fallback_ms;
            let _ = window.eval(&poll_report_script(&endpoint, force, &host, &expected));
            if force {
                break;
            }
        }
    });
}

const SOURCE_HOOKS: &str = r#"
  var re = null;
  try { if (__REGEX__) re = new RegExp(__REGEX__, 'i'); } catch (e) { re = null; }
  var mediaRe = /\.(m3u8|mp4|flv|m4s)(\?|#|$)/i;
  function add(u) {
    if (typeof u !== 'string' || !u) return;
    if (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
    if (re ? re.test(u) : mediaRe.test(u)) window.__legadoReport(u);
  }
  try {
    var of = window.fetch;
    if (of) {
      window.fetch = function(input) {
        try { add(typeof input === 'string' ? input : (input && input.url)); } catch (e) {}
        return of.apply(this, arguments);
      };
    }
  } catch (e) {}
  try {
    var oo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
      try { add(String(url)); } catch (e) {}
      return oo.apply(this, arguments);
    };
  } catch (e) {}
  setInterval(function(){
    try {
      var entries = performance.getEntriesByType('resource');
      for (var i = entries.length - 1; i >= 0 && i >= entries.length - 60; i--) add(entries[i].name);
      var nodes = document.querySelectorAll('video, source, audio');
      for (var j = 0; j < nodes.length; j++) {
        var s = nodes[j].currentSrc || nodes[j].src;
        if (s) add(s);
      }
    } catch (e) {}
  }, 500);
"#;

const OVERRIDE_HOOKS: &str = r#"
  var re = null;
  try { if (__REGEX__) re = new RegExp(__REGEX__, 'i'); } catch (e) { re = null; }
  setInterval(function(){
    try {
      var href = String(location.href);
      if (!re || re.test(href)) window.__legadoReport(href);
    } catch (e) {}
  }, 400);
"#;

fn run_webview_task(
    app: &tauri::AppHandle,
    req: &CookieBridgeRequest,
    timeout: Duration,
) -> Result<WebviewOutcome, String> {
    let parsed: url::Url = req.url.parse().map_err(|e| format!("无效 URL：{e}"))?;
    let label = format!("webview-{}", Uuid::new_v4().simple().to_string());
    let mode = req.mode.clone();
    let page_url = req.url.clone();
    let page_url_for_closure = page_url.clone();
    let report_mode = matches!(mode.as_str(), "html" | "source" | "override");
    let visible = matches!(mode.as_str(), "challenge" | "login") || req.visible_window;

    let (tx_result, rx_result) = mpsc::channel::<Result<Vec<CookieItem>, String>>();
    let tx_shared: Arc<Mutex<Option<mpsc::Sender<Result<Vec<CookieItem>, String>>>>> =
        Arc::new(Mutex::new(Some(tx_result)));
    let (tx_report, rx_report) = mpsc::channel::<(String, String)>();
    let report_id = format!("report-{}", Uuid::new_v4().simple());

    let (tx_built, rx_built) = mpsc::channel::<Result<(), String>>();
    let app_build = app.clone();
    let label_build = label.clone();
    let title = req.title.clone().unwrap_or_else(|| "Legado Desktop".into());
    let user_agent = req.user_agent.clone();
    let tx_page = tx_shared.clone();
    let mode_page = mode.clone();
    let url_page = page_url.clone();
    let delay = Duration::from_millis(req.delay_ms.unwrap_or(1_000).clamp(0, 30_000));
    let html_js = req.java_script.clone();
    let init_script = if report_mode {
        let state = app
            .try_state::<BridgeState>()
            .ok_or_else(|| "WebView 桥未初始化".to_string())?;
        let endpoint = format!(
            "http://127.0.0.1:{}/report?token={}&id={}",
            state.port, state.token, report_id
        );
        Some(report_script(
            &endpoint,
            &mode,
            req.source_regex.as_deref(),
            req.override_regex.as_deref(),
        ))
    } else {
        None
    };
    let report_endpoint = if report_mode {
        app.try_state::<BridgeState>().map(|state| {
            format!(
                "http://127.0.0.1:{}/report?token={}&id={}",
                state.port, state.token, report_id
            )
        })
    } else {
        None
    };
    let fallback_ms = req.report_fallback_ms.unwrap_or(0);
    let poll_endpoint = report_endpoint.clone();
    let reuse_window = req.reuse_window;
    let host_key = parsed.host_str().unwrap_or_default().to_string();
    let keep_session = (reuse_window || req.visible_window) && report_mode;
    let expected_url = {
        let mut value = parsed.clone();
        value.set_fragment(None);
        value.to_string()
    };
    let mut reused = false;
    if keep_session {
        let existing = session_windows()
            .lock()
            .ok()
            .and_then(|map| map.get(&host_key).cloned());
        if let Some(window) = existing {
            if window.url().is_ok() && window.navigate(parsed.clone()).is_ok() {
                reused = true;
                // 复用窗口默认是隐藏的；可见验证必须真正显示出来，否则用户永远无法完成挑战
                if req.visible_window {
                    let _ = window.set_title("完成安全验证");
                    let _ = window.show();
                    let _ = window.unminimize();
                    let _ = window.set_focus();
                    let _ = window.set_always_on_top(true);
                }
                if fallback_ms > 0 {
                    if let Some(endpoint) = poll_endpoint.clone() {
                        spawn_poll(
                            window,
                            endpoint,
                            fallback_ms,
                            host_key.clone(),
                            expected_url.clone(),
                        );
                    }
                }
            } else if let Ok(mut map) = session_windows().lock() {
                map.remove(&host_key);
            }
        }
    }

    if report_mode {
        if let Ok(mut map) = pending_reports().lock() {
            map.insert(report_id.clone(), tx_report);
        }
    }

    let session_host = host_key.clone();
    let expected_for_poll = expected_url.clone();
    if !reused {
        let _ = app.run_on_main_thread(move || {
            let mut builder = WebviewWindowBuilder::new(
                &app_build,
                label_build,
                WebviewUrl::External(parsed.clone()),
            )
        .title(title)
        .inner_size(1024.0, 760.0)
        .center()
        .visible(visible)
        .always_on_top(visible);
        if let Some(ua) = user_agent {
            if !ua.is_empty() {
                builder = builder.user_agent(&ua);
            }
        }
        if let Some(script) = init_script {
            builder = builder.initialization_script(&script);
        }
        let builder = builder.on_page_load(move |window, payload| {
            if payload.event() == tauri::webview::PageLoadEvent::Finished && mode_page == "challenge"
            {
                let tx = tx_page.clone();
                let url = url_page.clone();
                let challenge_window = window.clone();
                std::thread::spawn(move || {
                    std::thread::sleep(Duration::from_secs(2));
                    let result = cookies_from_window(&challenge_window, &url);
                    let _ = challenge_window.destroy();
                    if let Some(sender) = tx.lock().unwrap().take() {
                        let _ = sender.send(result);
                    }
                });
            }
            if payload.event() == tauri::webview::PageLoadEvent::Finished
                && matches!(mode_page.as_str(), "html")
            {
                let html_window = window.clone();
                let js = html_js.clone();
                std::thread::spawn(move || {
                    std::thread::sleep(delay);
                    let expr = js
                        .filter(|value| !value.trim().is_empty())
                        .unwrap_or_else(|| "document.documentElement.outerHTML".to_string());
                    let script = format!(
                        "try{{ var v=(function(){{ return ({}); }})(); window.__legadoReport(v == null ? '' : v); }}catch(e){{ window.__legadoReport('__LEGADO_ERROR__' + e); }}",
                        expr
                    );
                    let _ = html_window.eval(&script);
                });
            }
        });
        let window = match builder.build() {
            Ok(window) => window,
            Err(e) => {
                let _ = tx_built.send(Err(format!("创建窗口失败：{e}")));
                return;
            }
        };
        if visible {
            let _ = window.set_focus();
        }
            if keep_session {
                if let Ok(mut map) = session_windows().lock() {
                    map.insert(session_host.clone(), window.clone());
                }
            }
            if mode == "html" && fallback_ms > 0 {
                if let Some(endpoint) = poll_endpoint.clone() {
                    spawn_poll(
                        window.clone(),
                        endpoint,
                        fallback_ms,
                        session_host.clone(),
                        expected_for_poll.clone(),
                    );
                }
            }
        if mode == "login" {
            let tx_close = tx_shared.clone();
            let url_close = page_url_for_closure.clone();
            let window_close = window.clone();
            let once = Arc::new(Mutex::new(false));
            window.on_window_event(move |event| {
                if let WindowEvent::CloseRequested { api, .. } = event {
                    api.prevent_close();
                    let mut guard = once.lock().unwrap();
                    if *guard {
                        return;
                    }
                    *guard = true;
                    drop(guard);
                    let tx = tx_close.clone();
                    let url = url_close.clone();
                    let window = window_close.clone();
                    std::thread::spawn(move || {
                        let result = cookies_from_window(&window, &url);
                        if let Some(sender) = tx.lock().unwrap().take() {
                            let _ = sender.send(result);
                        }
                        let _ = window.destroy();
                    });
                }
            });
        }
            let _ = tx_built.send(Ok(()));
        });

        match rx_built.recv_timeout(Duration::from_secs(5)) {
            Ok(Ok(())) => {}
            Ok(Err(e)) => return Err(e),
            Err(_) => return Err("窗口创建超时".to_string()),
        }
    }

    if report_mode {
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                break;
            }
            match rx_report.recv_timeout(remaining.min(Duration::from_millis(500))) {
                Ok((final_url, payload)) => {
                    if let Ok(mut map) = pending_reports().lock() {
                        map.remove(&report_id);
                    }
                    let target_window = app.get_webview_window(&label).or_else(|| {
                        session_windows()
                            .lock()
                            .ok()
                            .and_then(|map| map.get(&host_key).cloned())
                    });
                    let cookies = target_window
                        .map(|window| {
                            let cookies = cookies_from_window(&window, &page_url).unwrap_or_default();
                            if req.visible_window {
                                let _ = window.hide();
                            } else if !keep_session {
                                let _ = window.destroy();
                            }
                            cookies
                        })
                        .unwrap_or_default();
                    if payload.starts_with("__LEGADO_ERROR__") {
                        return Err(format!("页面脚本执行失败：{}", payload.replace("__LEGADO_ERROR__", "")));
                    }
                    return Ok(WebviewOutcome {
                        cookies,
                        value: Some(payload),
                        final_url: Some(final_url),
                    });
                }
                Err(mpsc::RecvTimeoutError::Timeout) => continue,
                Err(mpsc::RecvTimeoutError::Disconnected) => break,
            }
        }
        if let Ok(mut map) = pending_reports().lock() {
            map.remove(&report_id);
        }
        if !keep_session {
            if let Some(window) = app.get_webview_window(&label) {
                let _ = app.run_on_main_thread(move || {
                    let _ = window.destroy();
                });
            }
        }
        return Err("等待网页嗅探结果超时".to_string());
    }

    match rx_result.recv_timeout(timeout) {
        Ok(result) => result.map(|cookies| WebviewOutcome {
            cookies,
            value: None,
            final_url: None,
        }),
        Err(_) => {
            if let Some(window) = app.get_webview_window(&label) {
                let _ = app.run_on_main_thread(move || {
                    let _ = window.destroy();
                });
            }
            Err("等待网页/用户完成超时".to_string())
        }
    }
}

fn cookies_from_window(window: &tauri::WebviewWindow, url: &str) -> Result<Vec<CookieItem>, String> {
    let parsed: url::Url = url.parse().map_err(|e| format!("无效 URL：{e}"))?;
    let cookies = window
        .cookies_for_url(parsed)
        .map_err(|e| format!("读取 Cookie 失败：{e}"))?;
    Ok(cookies
        .iter()
        .map(|cookie| CookieItem {
            domain: cookie.domain().map(|s| s.to_string()),
            name: cookie.name().to_string(),
            value: cookie.value().to_string(),
            path: cookie.path().map(|s| s.to_string()),
            secure: cookie.secure().unwrap_or(false),
            http_only: cookie.http_only().unwrap_or(false),
        })
        .collect())
}
