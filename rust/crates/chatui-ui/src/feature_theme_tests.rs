//! Light and dark palette checks for the embedded feature windows.

use slint::ComponentHandle;

use crate::chat::composer::ComposerWindow;
use crate::chat::message_list::MessageListWindow;
use crate::chat::view::ChatWindow;
use crate::conversations::view::HistoryWindow;
use crate::data::view::DataWindow;
use crate::models::view::ModelManagementWindow;
use crate::onboarding::view::OnboardingWindow;
use crate::providers::view::ProviderManagementWindow;
use crate::settings::view::SettingsWindow;

fn corner_color(window: &impl ComponentHandle) -> (u8, u8, u8) {
    let snapshot = window
        .window()
        .take_snapshot()
        .expect("snapshot feature window");
    let pixel = snapshot.as_slice()[0];
    (pixel.r, pixel.g, pixel.b)
}

macro_rules! assert_window_follows_theme {
    ($window:ty) => {{
        let window = <$window>::new().expect("create feature window");
        assert_eq!(corner_color(&window), (0xff, 0xff, 0xff));

        window.set_dark(true);
        assert_eq!(corner_color(&window), (0x0f, 0x11, 0x15));

        window.set_dark(false);
        assert_eq!(corner_color(&window), (0xff, 0xff, 0xff));
    }};
}

#[test]
fn every_feature_window_follows_the_dark_and_light_palette() {
    slint::platform::set_platform(Box::new(i_slint_backend_testing::TestingBackend::new(
        i_slint_backend_testing::TestingBackendOptions {
            mock_time: true,
            threading: false,
            renderer_name: Some("software".into()),
        },
    )))
    .expect("initialize software testing backend");

    assert_window_follows_theme!(ChatWindow);
    assert_window_follows_theme!(MessageListWindow);
    assert_window_follows_theme!(ComposerWindow);
    assert_window_follows_theme!(HistoryWindow);
    assert_window_follows_theme!(ProviderManagementWindow);
    assert_window_follows_theme!(SettingsWindow);
    assert_window_follows_theme!(DataWindow);
    assert_window_follows_theme!(ModelManagementWindow);
    assert_window_follows_theme!(OnboardingWindow);
}
