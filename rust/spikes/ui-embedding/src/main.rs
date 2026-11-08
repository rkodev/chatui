//! M3.6-01.1 embedding spike.
//!
//! Builds a host `Window` with two `ComponentContainer`s and embeds two
//! `EmbeddedWindow` instances (whose root is a `Window`) through
//! `ComponentFactory`. The factories return the same handles the controller
//! owns, so property setters and callbacks keep working after embedding.

slint::include_modules!();

use std::cell::Cell;
use std::rc::Rc;

use slint::ComponentHandle;

/// Create the host and embed two `EmbeddedWindow` instances.
///
/// The returned handles are the controller handles: the factories clone them,
/// so `set_*` and `on_*` calls on these handles affect the embedded UI.
fn build_host() -> Result<(HostWindow, EmbeddedWindow, EmbeddedWindow), slint::PlatformError> {
    let host = HostWindow::new()?;
    let left = EmbeddedWindow::new()?;
    let right = EmbeddedWindow::new()?;

    left.set_label("Left".into());
    right.set_label("Right".into());

    host.set_left_factory(slint::ComponentFactory::new({
        let handle = left.clone_strong();
        move |_context| Some(handle.clone_strong())
    }));
    host.set_right_factory(slint::ComponentFactory::new({
        let handle = right.clone_strong();
        move |_context| Some(handle.clone_strong())
    }));

    Ok((host, left, right))
}

fn main() -> Result<(), slint::PlatformError> {
    let (host, left, right) = build_host()?;

    let clicks = Rc::new(Cell::new(0));
    let clicks_for_left = Rc::clone(&clicks);
    left.on_clicked(move || clicks_for_left.set(clicks_for_left.get() + 1));

    // Update a property after embedding. The embedded `Text` follows the
    // controller handle because the factory returned that same handle.
    left.set_label("Left pane (updated)".into());

    println!(
        "embedded left={:?} right={:?} clicks={}",
        left.get_label(),
        right.get_label(),
        clicks.get()
    );

    // `--smoke` opens the real window, exercises the event loop briefly, then
    // exits cleanly so the spike can be run unattended.
    if std::env::args().any(|arg| arg == "--smoke") {
        let weak = host.as_weak();
        slint::Timer::single_shot(std::time::Duration::from_millis(500), move || {
            let _ = weak.upgrade();
            let _ = slint::quit_event_loop();
        });
    }

    host.run()
}

#[cfg(test)]
mod tests {
    use super::*;
    use i_slint_backend_testing::ElementRoot;

    fn init() {
        i_slint_backend_testing::init_no_event_loop();
    }

    fn accessible_labels(window: &HostWindow) -> Vec<String> {
        window
            .root_element()
            .query_descendants()
            .find_all()
            .into_iter()
            .filter_map(|element| element.accessible_label())
            .map(|label| label.to_string())
            .collect()
    }

    fn find_button(window: &HostWindow, label: &str) -> i_slint_backend_testing::ElementHandle {
        i_slint_backend_testing::ElementHandle::find_by_accessible_label(window, label)
            .find(|element| {
                element.accessible_role() == Some(i_slint_backend_testing::AccessibleRole::Button)
            })
            .unwrap_or_else(|| panic!("button {label:?} not found in the host tree"))
    }

    #[test]
    fn embedded_content_renders_inside_the_host() {
        init();
        let (host, left, right) = build_host().expect("host");

        left.set_label("Left pane".into());
        right.set_label("Right pane".into());

        let labels = accessible_labels(&host);
        assert!(
            labels.iter().any(|label| label == "Left pane"),
            "left embedded text missing from host tree: {labels:?}"
        );
        assert!(
            labels.iter().any(|label| label == "Right pane"),
            "right embedded text missing from host tree: {labels:?}"
        );

        // The embedded text is laid out inside the host, not just present in
        // the item tree.
        let left_text =
            i_slint_backend_testing::ElementHandle::find_by_accessible_label(&host, "Left pane")
                .next()
                .expect("left embedded text element");
        let size = left_text.size();
        assert!(
            size.width > 0.0 && size.height > 0.0,
            "embedded text has no layout size: {size:?}"
        );
    }

    #[test]
    fn embedded_callback_reaches_rust_while_embedded() {
        init();
        let (host, left, _right) = build_host().expect("host");

        let clicks = Rc::new(Cell::new(0));
        let clicks_for_left = Rc::clone(&clicks);
        left.on_clicked(move || clicks_for_left.set(clicks_for_left.get() + 1));

        // Drive the embedded button through the host's accessibility tree, not
        // through the controller handle, to prove the embedded content is live.
        find_button(&host, "Increment Left").invoke_accessible_default_action();

        assert_eq!(clicks.get(), 1, "embedded callback did not reach Rust");
        assert_eq!(left.get_count(), 1, "embedded button did not update count");
    }

    #[test]
    fn setter_on_controller_handle_updates_embedded_ui() {
        init();
        let (host, left, _right) = build_host().expect("host");

        left.set_label("Updated left".into());

        let labels = accessible_labels(&host);
        assert!(
            labels.iter().any(|label| label == "Updated left"),
            "setter did not update the embedded UI: {labels:?}"
        );
        assert!(
            !labels.iter().any(|label| label == "Left"),
            "stale embedded label still present: {labels:?}"
        );
    }

    #[test]
    fn visibility_toggle_keeps_embedded_handles_usable() {
        init();
        let (host, left, right) = build_host().expect("host");

        let clicks = Rc::new(Cell::new(0));
        let clicks_for_left = Rc::clone(&clicks);
        left.on_clicked(move || clicks_for_left.set(clicks_for_left.get() + 1));

        host.set_show_left(false);
        host.set_show_right(false);

        // Handles stay usable while the containers are hidden.
        left.set_label("Hidden left".into());
        right.set_label("Hidden right".into());
        left.invoke_clicked();
        assert_eq!(clicks.get(), 1);
        assert_eq!(left.get_label(), "Hidden left");
        assert_eq!(right.get_label(), "Hidden right");

        host.set_show_left(true);
        host.set_show_right(true);

        let labels = accessible_labels(&host);
        assert!(
            labels.iter().any(|label| label == "Hidden left"),
            "left handle lost after visibility toggle: {labels:?}"
        );
        assert!(
            labels.iter().any(|label| label == "Hidden right"),
            "right handle lost after visibility toggle: {labels:?}"
        );
    }

    #[test]
    fn dropping_the_host_does_not_panic() {
        init();
        let (host, left, right) = build_host().expect("host");

        drop(host);
        // The controller handles still own the embedded item trees.
        left.set_label("After host drop".into());
        right.set_label("After host drop".into());
        assert_eq!(left.get_label(), "After host drop");
        assert_eq!(right.get_label(), "After host drop");

        drop(left);
        drop(right);
    }
}
