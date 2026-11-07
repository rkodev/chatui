//! Minimal desktop entry point.
//!
//! This task only wires the shared shell. Feature screens arrive in later
//! M3.5 tasks. `--smoke` opens the real window, runs the event loop briefly,
//! and exits so unattended verification can exercise the shell.

use std::time::Duration;

use chatui_ui::shell::{ShellController, ShellState};
use slint::ComponentHandle;

fn main() -> Result<(), slint::PlatformError> {
    let state = ShellState::new(1024.0);
    let controller = ShellController::new(state)?;

    if std::env::args().any(|arg| arg == "--smoke") {
        let weak = controller.window().as_weak();
        slint::Timer::single_shot(Duration::from_millis(1500), move || {
            let _ = weak.upgrade();
            let _ = slint::quit_event_loop();
        });
    }

    controller.run()
}
