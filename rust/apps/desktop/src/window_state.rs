//! Window-state persistence and system-theme detection.
//!
//! Window size, position, and maximized state are stored as JSON in the
//! platform config directory. No conversation, prompt, key, or other sensitive
//! content is stored here.
//!
//! System theme detection is best-effort per OS. When the probe cannot read the
//! OS setting it reports light mode and motion enabled; the shell then follows
//! the user's explicit appearance setting.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

/// Default window width in logical pixels.
pub const DEFAULT_WIDTH: f32 = 1024.0;
/// Default window height in logical pixels.
pub const DEFAULT_HEIGHT: f32 = 720.0;
/// Minimum persisted width.
pub const MIN_WIDTH: f32 = 480.0;
/// Minimum persisted height.
pub const MIN_HEIGHT: f32 = 360.0;
/// Maximum persisted width.
pub const MAX_WIDTH: f32 = 7680.0;
/// Maximum persisted height.
pub const MAX_HEIGHT: f32 = 4320.0;
/// Maximum absolute window offset.
pub const MAX_OFFSET: i32 = 10_000;

/// Persisted window geometry.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WindowState {
    /// Logical width.
    pub width: f32,
    /// Logical height.
    pub height: f32,
    /// Logical x position.
    pub x: i32,
    /// Logical y position.
    pub y: i32,
    /// Whether the window was maximized.
    pub maximized: bool,
}

impl Default for WindowState {
    fn default() -> Self {
        Self {
            width: DEFAULT_WIDTH,
            height: DEFAULT_HEIGHT,
            x: 0,
            y: 0,
            maximized: false,
        }
    }
}

impl WindowState {
    /// Clamp values to sane bounds and replace non-finite sizes.
    pub fn sanitized(self) -> Self {
        let width = if self.width.is_finite() {
            self.width.clamp(MIN_WIDTH, MAX_WIDTH)
        } else {
            DEFAULT_WIDTH
        };
        let height = if self.height.is_finite() {
            self.height.clamp(MIN_HEIGHT, MAX_HEIGHT)
        } else {
            DEFAULT_HEIGHT
        };

        Self {
            width,
            height,
            x: self.x.clamp(-MAX_OFFSET, MAX_OFFSET),
            y: self.y.clamp(-MAX_OFFSET, MAX_OFFSET),
            maximized: self.maximized,
        }
    }

    /// Read the current geometry from a Slint window.
    pub fn from_window(window: &slint::Window) -> Self {
        let scale = window.scale_factor();
        let size = window.size().to_logical(scale);
        let position = window.position().to_logical(scale);

        Self {
            width: size.width,
            height: size.height,
            x: position.x.round() as i32,
            y: position.y.round() as i32,
            maximized: window.is_maximized(),
        }
        .sanitized()
    }

    /// Apply this geometry to a Slint window.
    pub fn apply_to_window(&self, window: &slint::Window) {
        let state = self.sanitized();
        window.set_size(slint::WindowSize::Logical(slint::LogicalSize::new(
            state.width,
            state.height,
        )));
        window.set_position(slint::WindowPosition::Logical(slint::LogicalPosition::new(
            state.x as f32,
            state.y as f32,
        )));
        window.set_maximized(state.maximized);
    }
}

/// A window-state persistence backend.
pub trait WindowStateStore {
    /// Load the saved state, or `None` when nothing has been saved.
    fn load(&self) -> Result<Option<WindowState>, WindowStateError>;
    /// Save the state.
    fn save(&self, state: &WindowState) -> Result<(), WindowStateError>;
}

/// A JSON file store.
#[derive(Debug, Clone)]
pub struct FileWindowStateStore {
    path: PathBuf,
}

impl FileWindowStateStore {
    /// Create a store at `path`.
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    /// The default per-user config path.
    pub fn default_path() -> PathBuf {
        config_dir().join("chatui").join("window-state.json")
    }

    /// The file path.
    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl WindowStateStore for FileWindowStateStore {
    fn load(&self) -> Result<Option<WindowState>, WindowStateError> {
        match std::fs::read_to_string(&self.path) {
            Ok(text) => {
                let state: WindowState = serde_json::from_str(&text)?;
                Ok(Some(state.sanitized()))
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(WindowStateError::Io(error)),
        }
    }

    fn save(&self, state: &WindowState) -> Result<(), WindowStateError> {
        if let Some(parent) = self.path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let text = serde_json::to_string_pretty(&state.sanitized())?;
        std::fs::write(&self.path, text)?;
        Ok(())
    }
}

/// An in-memory store for tests and headless runs.
#[derive(Debug, Default)]
pub struct MemoryWindowStateStore {
    state: std::cell::RefCell<Option<WindowState>>,
}

impl MemoryWindowStateStore {
    /// Create an empty store.
    pub fn new() -> Self {
        Self::default()
    }

    /// Create a store with an initial state.
    pub fn with_state(state: WindowState) -> Self {
        Self {
            state: std::cell::RefCell::new(Some(state)),
        }
    }

    /// The current stored state.
    pub fn state(&self) -> Option<WindowState> {
        *self.state.borrow()
    }
}

impl WindowStateStore for MemoryWindowStateStore {
    fn load(&self) -> Result<Option<WindowState>, WindowStateError> {
        Ok(self.state())
    }

    fn save(&self, state: &WindowState) -> Result<(), WindowStateError> {
        *self.state.borrow_mut() = Some(state.sanitized());
        Ok(())
    }
}

/// A window-state persistence error.
#[derive(Debug)]
pub enum WindowStateError {
    /// Filesystem error.
    Io(std::io::Error),
    /// JSON error.
    Json(serde_json::Error),
}

impl std::fmt::Display for WindowStateError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "window-state I/O error: {error}"),
            Self::Json(error) => write!(formatter, "window-state JSON error: {error}"),
        }
    }
}

impl std::error::Error for WindowStateError {}

impl From<std::io::Error> for WindowStateError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

impl From<serde_json::Error> for WindowStateError {
    fn from(error: serde_json::Error) -> Self {
        Self::Json(error)
    }
}

/// Load, apply, and save window geometry.
pub struct WindowStateController {
    store: Box<dyn WindowStateStore>,
}

impl WindowStateController {
    /// Create a controller over `store`.
    pub fn new(store: Box<dyn WindowStateStore>) -> Self {
        Self { store }
    }

    /// Load the saved state.
    pub fn load(&self) -> Option<WindowState> {
        self.store.load().ok().flatten()
    }

    /// Load and apply the saved state to `window`.
    pub fn apply(&self, window: &slint::Window) -> Option<WindowState> {
        let state = self.load()?;
        state.apply_to_window(window);
        Some(state)
    }

    /// Read the current geometry from `window` and save it.
    pub fn save(&self, window: &slint::Window) -> Result<(), WindowStateError> {
        self.store.save(&WindowState::from_window(window))
    }

    /// Save an explicit state.
    pub fn save_state(&self, state: &WindowState) -> Result<(), WindowStateError> {
        self.store.save(state)
    }
}

/// The OS theme and motion preference.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct SystemTheme {
    /// Whether the OS prefers a dark theme.
    pub dark: bool,
    /// Whether the OS requests reduced motion.
    pub reduced_motion: bool,
}

/// A system-theme probe.
pub trait SystemThemeProbe {
    /// Read the current OS preference.
    fn probe(&self) -> SystemTheme;
}

/// The native OS probe.
#[derive(Debug, Clone, Copy, Default)]
pub struct NativeSystemThemeProbe;

impl SystemThemeProbe for NativeSystemThemeProbe {
    fn probe(&self) -> SystemTheme {
        detect_system_theme()
    }
}

/// A fixed probe for tests.
#[derive(Debug, Clone, Copy, Default)]
pub struct MemorySystemThemeProbe {
    /// The value returned by [`SystemThemeProbe::probe`].
    pub theme: SystemTheme,
}

impl SystemThemeProbe for MemorySystemThemeProbe {
    fn probe(&self) -> SystemTheme {
        self.theme
    }
}

/// Detect the OS theme and motion preference.
pub fn detect_system_theme() -> SystemTheme {
    #[cfg(target_os = "linux")]
    {
        let dark = run_command(
            "gsettings",
            &["get", "org.gnome.desktop.interface", "color-scheme"],
        )
        .map(|output| parse_gsettings_color_scheme(&output))
        .unwrap_or_else(|| {
            std::env::var("GTK_THEME")
                .map(|theme| theme.to_ascii_lowercase().contains("dark"))
                .unwrap_or(false)
        });
        let reduced_motion = run_command(
            "gsettings",
            &["get", "org.gnome.desktop.interface", "enable-animations"],
        )
        .map(|output| parse_gsettings_animations(&output))
        .unwrap_or(false);
        return SystemTheme {
            dark,
            reduced_motion,
        };
    }

    #[cfg(target_os = "macos")]
    {
        let dark = run_command("defaults", &["read", "-g", "AppleInterfaceStyle"])
            .map(|output| parse_macos_dark(&output))
            .unwrap_or(false);
        let reduced_motion = run_command(
            "defaults",
            &["read", "com.apple.universalaccess", "reduceMotion"],
        )
        .map(|output| parse_macos_reduce_motion(&output))
        .unwrap_or(false);
        return SystemTheme {
            dark,
            reduced_motion,
        };
    }

    #[cfg(target_os = "windows")]
    {
        let dark = run_command(
            "reg",
            &[
                "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v",
                "AppsUseLightTheme",
            ],
        )
        .map(|output| parse_windows_light_theme(&output))
        .unwrap_or(false);
        return SystemTheme {
            dark,
            reduced_motion: false,
        };
    }

    #[allow(unreachable_code)]
    SystemTheme::default()
}

fn run_command(program: &str, args: &[&str]) -> Option<String> {
    let output = std::process::Command::new(program)
        .args(args)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// Parse `gsettings get org.gnome.desktop.interface color-scheme`.
pub fn parse_gsettings_color_scheme(output: &str) -> bool {
    let value = output.trim().to_ascii_lowercase();
    value.contains("prefer-dark") || value.contains("'dark'") || value == "dark"
}

/// Parse `gsettings get org.gnome.desktop.interface enable-animations`.
pub fn parse_gsettings_animations(output: &str) -> bool {
    output.trim().eq_ignore_ascii_case("false")
}

/// Parse `defaults read -g AppleInterfaceStyle`.
pub fn parse_macos_dark(output: &str) -> bool {
    output.to_ascii_lowercase().contains("dark")
}

/// Parse `defaults read com.apple.universalaccess reduceMotion`.
pub fn parse_macos_reduce_motion(output: &str) -> bool {
    output.trim() == "1"
}

/// Parse the Windows `AppsUseLightTheme` registry value.
pub fn parse_windows_light_theme(output: &str) -> bool {
    output.to_ascii_lowercase().contains("0x0")
}

fn config_dir() -> PathBuf {
    if let Ok(path) = std::env::var("XDG_CONFIG_HOME")
        && !path.is_empty()
    {
        return PathBuf::from(path);
    }
    if let Ok(home) = std::env::var("HOME")
        && !home.is_empty()
    {
        return PathBuf::from(home).join(".config");
    }
    std::env::temp_dir()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_state_is_sane() {
        let state = WindowState::default();
        assert_eq!(state.width, DEFAULT_WIDTH);
        assert_eq!(state.height, DEFAULT_HEIGHT);
        assert!(!state.maximized);
    }

    #[test]
    fn serialization_round_trips() {
        let state = WindowState {
            width: 1280.0,
            height: 800.0,
            x: 120,
            y: 80,
            maximized: true,
        };
        let text = serde_json::to_string(&state).expect("serialize");
        let decoded: WindowState = serde_json::from_str(&text).expect("deserialize");
        assert_eq!(decoded, state);
    }

    #[test]
    fn sanitizes_out_of_range_values() {
        let state = WindowState {
            width: f32::NAN,
            height: 10.0,
            x: 50_000,
            y: -50_000,
            maximized: false,
        }
        .sanitized();
        assert_eq!(state.width, DEFAULT_WIDTH);
        assert_eq!(state.height, MIN_HEIGHT);
        assert_eq!(state.x, MAX_OFFSET);
        assert_eq!(state.y, -MAX_OFFSET);
    }

    #[test]
    fn file_store_round_trips() {
        let directory = tempfile::tempdir().expect("tempdir");
        let store = FileWindowStateStore::new(directory.path().join("window-state.json"));
        assert_eq!(store.load().expect("load empty"), None);

        let state = WindowState {
            width: 1100.0,
            height: 700.0,
            x: 10,
            y: 20,
            maximized: false,
        };
        store.save(&state).expect("save");
        assert_eq!(store.load().expect("load"), Some(state));
    }

    #[test]
    fn memory_store_round_trips() {
        let store = MemoryWindowStateStore::new();
        assert_eq!(store.load().expect("load empty"), None);
        let state = WindowState {
            width: 900.0,
            height: 600.0,
            x: 1,
            y: 2,
            maximized: true,
        };
        store.save(&state).expect("save");
        assert_eq!(store.load().expect("load"), Some(state));
    }

    #[test]
    fn system_theme_parsers() {
        assert!(parse_gsettings_color_scheme("'prefer-dark'\n"));
        assert!(!parse_gsettings_color_scheme("'default'\n"));
        assert!(parse_gsettings_animations("false\n"));
        assert!(!parse_gsettings_animations("true\n"));
        assert!(parse_macos_dark("Dark\n"));
        assert!(!parse_macos_dark("Light\n"));
        assert!(parse_macos_reduce_motion("1\n"));
        assert!(!parse_macos_reduce_motion("0\n"));
        assert!(parse_windows_light_theme(
            "AppsUseLightTheme    REG_DWORD    0x0\n"
        ));
        assert!(!parse_windows_light_theme(
            "AppsUseLightTheme    REG_DWORD    0x1\n"
        ));
    }

    #[test]
    fn memory_probe_returns_the_configured_theme() {
        let probe = MemorySystemThemeProbe {
            theme: SystemTheme {
                dark: true,
                reduced_motion: true,
            },
        };
        assert_eq!(
            probe.probe(),
            SystemTheme {
                dark: true,
                reduced_motion: true
            }
        );
    }

    slint::slint! {
        export component WindowStateTestWindow inherits Window {
            width: 800px;
            height: 600px;
        }
    }

    #[test]
    fn window_state_reads_and_applies_to_a_slint_window() {
        i_slint_backend_testing::init_no_event_loop();
        let window = WindowStateTestWindow::new().expect("window");

        let state = WindowState {
            width: 900.0,
            height: 700.0,
            x: 12,
            y: 24,
            maximized: false,
        };
        state.apply_to_window(window.window());

        let read = WindowState::from_window(window.window());
        assert_eq!(read.width, 900.0);
        assert_eq!(read.height, 700.0);
        // The headless testing backend does not apply window positions, so the
        // x/y round-trip is covered by the serialization test instead.
        assert!(!read.maximized);
    }
}
