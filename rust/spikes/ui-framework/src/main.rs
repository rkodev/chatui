slint::include_modules!();

use slint::Model;
use std::time::Duration;

fn main() -> Result<(), slint::PlatformError> {
    let app = AppWindow::new()?;

    let weak = app.as_weak();
    app.on_send(move |text| {
        if let Some(app) = weak.upgrade() {
            let mut items: Vec<slint::SharedString> = app.get_items().iter().collect();
            items.push(text);
            app.set_items(slint::ModelRc::new(slint::VecModel::from(items)));
            app.set_status("Sent".into());
        }
    });

    let weak = app.as_weak();
    app.on_pick_file(move || {
        if let Some(app) = weak.upgrade() {
            match rfd::FileDialog::new().pick_file() {
                Some(path) => app.set_status(format!("Picked: {}", path.display()).into()),
                None => app.set_status("File picker cancelled".into()),
            }
        }
    });

    // `--smoke` makes the prototype usable in an unattended verification run:
    // it opens the real window, exercises the event loop, then exits cleanly.
    if std::env::args().any(|arg| arg == "--smoke") {
        let weak = app.as_weak();
        slint::Timer::single_shot(Duration::from_millis(1500), move || {
            if let Some(app) = weak.upgrade() {
                app.set_status("Smoke run complete".into());
            }
            let _ = slint::quit_event_loop();
        });
    }

    app.run()
}
