//! Message list windowing, pagination, scroll anchoring, and row tests.

use std::cell::RefCell;
use std::rc::Rc;

use chatui_domain::{
    ChatError, ChatErrorCode, ContentPart, Message, MessageRole, MessageStatus, Timestamp, Usage,
};
use chatui_storage::MessagePage;
use slint::Model;

use super::message_item::{MessageAction, MessageRow, MessageRowOptions};
use super::message_list::{
    DEFAULT_ESTIMATE_HEIGHT, MAX_WINDOW_ROWS, MessageListConfig, MessageListController,
    MessageListError, MessageListState,
};
use super::state::StreamingState;

fn fixed() -> Timestamp {
    Timestamp::parse("2026-01-01T00:00:00.000Z").expect("valid timestamp")
}

fn message(id: &str, sequence: u64, role: MessageRole, text: &str) -> Message {
    Message {
        id: id.to_owned(),
        conversation_id: "conversation-1".to_owned(),
        sequence,
        role,
        parts: vec![ContentPart::Text {
            text: text.to_owned(),
        }],
        status: MessageStatus::Complete,
        provider_id: Some("provider-1".to_owned()),
        model_id: Some("model-1".to_owned()),
        model_label: Some("OpenRouter: Auto".to_owned()),
        usage: None,
        finish_reason: None,
        error: None,
        latency_ms: None,
        first_token_ms: None,
        created_at: fixed(),
        updated_at: fixed(),
        deleted_at: None,
    }
}

fn page(messages: Vec<Message>, has_more: bool) -> MessagePage {
    MessagePage { messages, has_more }
}

fn chat_error(code: ChatErrorCode) -> ChatError {
    ChatError {
        code,
        message: "error".to_owned(),
        provider_id: Some("openrouter".to_owned()),
        status: None,
        retryable: true,
        retry_after_seconds: None,
        request_id: None,
        details: None,
        at: fixed(),
    }
}

#[test]
fn ten_thousand_messages_render_a_bounded_window_in_order() {
    let config = MessageListConfig {
        viewport_height: 800.0,
        ..MessageListConfig::default()
    };
    let mut state = MessageListState::new("conversation-1", config);
    let all: Vec<Message> = (0..10_000)
        .map(|index| {
            let role = if index % 2 == 0 {
                MessageRole::User
            } else {
                MessageRole::Assistant
            };
            message(
                &format!("m{index}"),
                index as u64,
                role,
                &format!("message {index}"),
            )
        })
        .collect();

    let newest_start = all.len() - 50;
    state.apply_newest_page(page(all[newest_start..].to_vec(), true));

    while state.has_older() {
        let cursor = state.begin_load_older().expect("older cursor");
        let start = cursor as usize;
        let page_start = start.saturating_sub(50);
        state.apply_older_page(page(all[page_start..start].to_vec(), page_start > 0));
    }

    assert_eq!(state.message_count(), 10_000);
    let rows = state.visible_rows();
    assert!(!rows.is_empty());
    assert!(
        rows.len() <= MAX_WINDOW_ROWS,
        "composed {} rows, cap is {MAX_WINDOW_ROWS}",
        rows.len()
    );
    assert!(
        rows.len() < 100,
        "the window must be much smaller than the history"
    );

    let sequences: Vec<u64> = rows
        .iter()
        .map(|row| row.id.trim_start_matches('m').parse::<u64>().expect("id"))
        .collect();
    assert!(
        sequences.windows(2).all(|pair| pair[0] < pair[1]),
        "rows must be in ascending sequence order"
    );
    assert_eq!(rows.last().expect("last row").id, "m9999");
}

#[test]
fn scroll_anchoring_preserves_the_visible_message_when_older_pages_load() {
    let config = MessageListConfig {
        viewport_height: 400.0,
        ..MessageListConfig::default()
    };
    let mut state = MessageListState::new("conversation-1", config);
    let newest: Vec<Message> = (50..100)
        .map(|index| message(&format!("m{index}"), index, MessageRole::User, "message"))
        .collect();
    state.apply_newest_page(page(newest, true));
    state.handle_scroll(0.0);

    let before_top = state.viewport_top_id().expect("top row").to_owned();
    let before_scroll = state.scroll_top();
    let before_height = state.total_height();

    let cursor = state.begin_load_older().expect("older cursor");
    assert_eq!(cursor, 50);
    let older: Vec<Message> = (0..50)
        .map(|index| message(&format!("m{index}"), index, MessageRole::User, "message"))
        .collect();
    state.apply_older_page(page(older, false));

    let after_top = state.viewport_top_id().expect("top row").to_owned();
    assert_eq!(
        after_top, before_top,
        "the message at the top of the viewport must not move"
    );
    assert!(
        state.scroll_top() > before_scroll,
        "the scroll offset must grow by the prepended height"
    );
    assert!(
        (state.total_height() - before_height - 50.0 * DEFAULT_ESTIMATE_HEIGHT).abs() < 1.0,
        "the prepended page must add its estimated height"
    );
    assert!(
        !state.near_bottom(),
        "the user was scrolled up, so the list must not jump to the bottom"
    );
}

#[test]
fn auto_scroll_only_when_near_the_bottom() {
    let config = MessageListConfig {
        viewport_height: 400.0,
        ..MessageListConfig::default()
    };
    let mut state = MessageListState::new("conversation-1", config);
    let messages: Vec<Message> = (0..50)
        .map(|index| message(&format!("m{index}"), index, MessageRole::User, "message"))
        .collect();
    state.apply_newest_page(page(messages, false));

    assert!(state.near_bottom());
    assert!(state.should_auto_scroll());
    assert_eq!(state.scroll_top(), state.max_scroll_top());

    state.handle_scroll(0.0);
    assert!(!state.near_bottom());
    assert!(!state.should_auto_scroll());
    let scrolled_up = state.scroll_top();

    let mut streaming = StreamingState::new("conversation-1", "stream-1", fixed());
    streaming.text = "partial".to_owned();
    state.set_streaming(Some(streaming));
    assert_eq!(
        state.scroll_top(),
        scrolled_up,
        "streaming must not force scroll when the user is scrolled up"
    );

    state.scroll_to_bottom();
    assert!(state.near_bottom());
    let mut streaming = state.streaming().expect("streaming").clone();
    streaming.text.push_str(" more");
    state.set_streaming(Some(streaming));
    assert_eq!(
        state.scroll_top(),
        state.max_scroll_top(),
        "streaming follows the bottom when the user is near it"
    );
}

#[test]
fn streaming_row_is_isolated_from_completed_history() {
    let config = MessageListConfig {
        viewport_height: 800.0,
        ..MessageListConfig::default()
    };
    let mut state = MessageListState::new("conversation-1", config);
    let messages: Vec<Message> = (0..20)
        .map(|index| message(&format!("m{index}"), index, MessageRole::User, "message"))
        .collect();
    state.apply_newest_page(page(messages, false));
    state.handle_scroll(0.0);
    let history_ids: Vec<String> = state
        .visible_rows()
        .iter()
        .map(|row| row.id.clone())
        .collect();

    let mut streaming = StreamingState::new("conversation-1", "stream-1", fixed());
    streaming.text = "first".to_owned();
    state.set_streaming(Some(streaming));
    let after_first: Vec<String> = state
        .visible_rows()
        .iter()
        .map(|row| row.id.clone())
        .collect();
    assert_eq!(
        after_first, history_ids,
        "history rows must not be rebuilt by a token update"
    );
    assert_eq!(
        state.streaming_row().expect("streaming row").markdown,
        "first"
    );

    let mut streaming = state.streaming().expect("streaming").clone();
    streaming.text.push_str(" token");
    state.set_streaming(Some(streaming));
    let after_second: Vec<String> = state
        .visible_rows()
        .iter()
        .map(|row| row.id.clone())
        .collect();
    assert_eq!(after_second, history_ids);
    assert_eq!(
        state.streaming_row().expect("streaming row").markdown,
        "first token"
    );
}

#[test]
fn persisted_streaming_message_is_not_rendered_twice() {
    let config = MessageListConfig {
        viewport_height: 800.0,
        ..MessageListConfig::default()
    };
    let mut state = MessageListState::new("conversation-1", config);
    let messages = vec![
        message("m0", 0, MessageRole::User, "hello"),
        message("stream-1", 1, MessageRole::Assistant, "partial"),
    ];
    state.apply_newest_page(page(messages, false));

    let mut streaming = StreamingState::new("conversation-1", "stream-1", fixed());
    streaming.text = "partial".to_owned();
    state.set_streaming(Some(streaming));

    let ids: Vec<String> = state
        .visible_rows()
        .iter()
        .map(|row| row.id.clone())
        .collect();
    assert_eq!(ids, vec!["m0"]);
    assert_eq!(state.streaming_row().expect("streaming row").id, "stream-1");
}

#[test]
fn pagination_uses_newest_and_older_pages() {
    let config = MessageListConfig {
        page_size: 3,
        viewport_height: 400.0,
        ..MessageListConfig::default()
    };
    let mut state = MessageListState::new("conversation-1", config);
    let newest: Vec<Message> = (7..10)
        .map(|index| message(&format!("m{index}"), index, MessageRole::User, "message"))
        .collect();
    state.apply_newest_page(page(newest, true));

    assert_eq!(state.message_count(), 3);
    assert!(state.has_older());
    assert_eq!(state.oldest_sequence(), Some(7));

    let cursor = state.begin_load_older().expect("older cursor");
    assert_eq!(cursor, 7);
    assert!(state.loading_older());
    assert!(
        state.begin_load_older().is_none(),
        "a second load must not start while one is in flight"
    );

    let older: Vec<Message> = (4..7)
        .map(|index| message(&format!("m{index}"), index, MessageRole::User, "message"))
        .collect();
    state.apply_older_page(page(older, true));

    assert_eq!(state.message_count(), 6);
    assert!(!state.loading_older());
    assert_eq!(state.oldest_sequence(), Some(4));
    let ids: Vec<String> = state
        .messages()
        .iter()
        .map(|message| message.id.clone())
        .collect();
    assert_eq!(ids, vec!["m4", "m5", "m6", "m7", "m8", "m9"]);
}

#[test]
fn loading_empty_and_error_states() {
    let mut state = MessageListState::new("conversation-1", MessageListConfig::default());
    assert!(state.is_loading());
    assert!(state.is_empty());
    assert!(state.error().is_none());

    state.set_error(MessageListError::new("offline"));
    assert!(!state.is_loading());
    assert!(state.error().is_some());
    assert_eq!(state.error().expect("error").message, "offline");

    state.clear_error();
    assert!(state.error().is_none());

    state.apply_newest_page(page(Vec::new(), false));
    assert!(!state.is_loading());
    assert!(state.is_empty());
}

#[test]
fn message_row_renders_role_metadata_status_and_error() {
    let mut message = message("m1", 1, MessageRole::Assistant, "hello");
    message.usage = Some(Usage {
        input_tokens: Some(10),
        output_tokens: Some(20),
        total_tokens: Some(30),
        cost_usd: Some(0.001),
        estimated: false,
    });
    message.latency_ms = Some(1_500);
    message.first_token_ms = Some(250);

    let row = MessageRow::from_message(
        &message,
        MessageRowOptions {
            show_token_usage: true,
            can_regenerate: true,
            can_retry: false,
            is_active_stream: false,
        },
    );

    assert_eq!(row.role_label, "Assistant");
    assert_eq!(row.markdown, "hello");
    assert!(row.metadata.summary().contains("OpenRouter: Auto"));
    assert!(row.metadata.summary().contains("10 in"));
    assert!(row.metadata.summary().contains("1.5 s"));
    assert!(row.metadata.summary().contains("first token 250 ms"));
    assert!(row.has_action(MessageAction::Copy));
    assert!(row.has_action(MessageAction::Regenerate));
    assert!(!row.has_action(MessageAction::Retry));
}

#[test]
fn error_and_cancelled_rows_show_actionable_copy() {
    let mut failed = message("m1", 1, MessageRole::Assistant, "partial");
    failed.status = MessageStatus::Error;
    failed.error = Some(chat_error(ChatErrorCode::RateLimit));

    let row = MessageRow::from_message(
        &failed,
        MessageRowOptions {
            can_retry: true,
            ..MessageRowOptions::default()
        },
    );

    assert_eq!(row.error.as_ref().expect("error").title, "Rate limited");
    assert!(row.has_action(MessageAction::Retry));

    let mut cancelled = message("m2", 2, MessageRole::Assistant, "partial");
    cancelled.status = MessageStatus::Cancelled;
    let row = MessageRow::from_message(&cancelled, MessageRowOptions::default());
    assert!(
        row.status_label
            .as_deref()
            .unwrap_or_default()
            .contains("cancelled")
    );
}

#[test]
fn slint_window_renders_windowed_rows_and_streaming_row() {
    i_slint_backend_testing::init_no_event_loop();

    let config = MessageListConfig {
        viewport_height: 400.0,
        ..MessageListConfig::default()
    };
    let mut state = MessageListState::new("conversation-1", config);
    let messages: Vec<Message> = (0..100)
        .map(|index| message(&format!("m{index}"), index, MessageRole::User, "message"))
        .collect();
    state.apply_newest_page(page(messages, false));

    let controller = MessageListController::new(state).expect("message list window");
    let window = controller.window();

    assert!(window.get_rows().row_count() <= MAX_WINDOW_ROWS);
    assert!(window.get_rows().row_count() > 0);
    assert!(!window.get_empty());
    assert!(!window.get_loading());

    let mut streaming = StreamingState::new("conversation-1", "stream-1", fixed());
    streaming.text = "live".to_owned();
    controller.set_streaming(Some(streaming));

    assert!(window.get_streaming_visible());
    assert_eq!(window.get_streaming_row().markdown, "live");
    let rows_after_first = window.get_rows();

    let mut streaming = controller.state().streaming().expect("streaming").clone();
    streaming.text.push_str(" token");
    controller.set_streaming(Some(streaming));

    assert_eq!(window.get_streaming_row().markdown, "live token");
    assert_eq!(
        window.get_rows(),
        rows_after_first,
        "token updates must not rebuild the completed history model"
    );
    assert!(window.get_rows().row_count() <= MAX_WINDOW_ROWS);
}

#[test]
fn streaming_announcements_are_bounded_and_exposed_as_a_live_region() {
    use i_slint_backend_testing::{AccessibleLiveness, AccessibleRole, ElementRoot};

    i_slint_backend_testing::init_no_event_loop();

    let state = MessageListState::new("conversation-1", MessageListConfig::default());
    let controller = MessageListController::new(state).expect("message list window");
    let window = controller.window();

    let mut streaming = StreamingState::new("conversation-1", "stream-1", fixed());
    streaming.started = true;
    streaming.text = "Hello".to_owned();
    controller.set_streaming(Some(streaming));
    assert_eq!(window.get_announcement(), "Response started");
    assert!(!window.get_announcement_assertive());

    let mut streaming = controller.state().streaming().expect("streaming").clone();
    streaming.text.push_str(" world");
    controller.set_streaming(Some(streaming));
    assert_eq!(
        window.get_announcement(),
        "Response started",
        "token deltas must not change the announcement"
    );

    let mut streaming = controller.state().streaming().expect("streaming").clone();
    streaming.done = true;
    streaming.status = MessageStatus::Complete;
    controller.set_streaming(Some(streaming));
    assert_eq!(window.get_announcement(), "Response complete");

    let announcement = window
        .root_element()
        .query_descendants()
        .match_accessible_role(AccessibleRole::Text)
        .find_all()
        .into_iter()
        .find(|element| element.accessible_label().as_deref() == Some("Response complete"))
        .expect("announcement text node");
    assert_eq!(
        announcement.accessible_live_region(),
        Some(AccessibleLiveness::Polite)
    );
}

#[test]
fn slint_window_shows_loading_empty_and_error_states() {
    i_slint_backend_testing::init_no_event_loop();

    let state = MessageListState::new("conversation-1", MessageListConfig::default());
    let controller = MessageListController::new(state).expect("message list window");
    let window = controller.window();

    assert!(window.get_loading());
    assert!(window.get_empty());
    assert!(!window.get_error_visible());

    controller.set_error(MessageListError::new("offline"));
    assert!(window.get_error_visible());
    assert_eq!(window.get_error_message(), "offline");
    assert!(!window.get_loading());

    controller.clear_error();
    controller.apply_newest_page(page(Vec::new(), false));
    assert!(!window.get_error_visible());
    assert!(window.get_empty());
    assert!(!window.get_loading());
}

#[test]
fn slint_window_invokes_load_older_and_copy_handlers() {
    i_slint_backend_testing::init_no_event_loop();

    let config = MessageListConfig {
        viewport_height: 400.0,
        ..MessageListConfig::default()
    };
    let mut state = MessageListState::new("conversation-1", config);
    let messages: Vec<Message> = (50..100)
        .map(|index| {
            message(
                &format!("m{index}"),
                index,
                MessageRole::User,
                &format!("message {index}"),
            )
        })
        .collect();
    state.apply_newest_page(page(messages, true));

    let mut controller = MessageListController::new(state).expect("message list window");
    let loaded = Rc::new(RefCell::new(Vec::new()));
    let loaded_clone = Rc::clone(&loaded);
    controller.set_on_load_older(move |cursor| loaded_clone.borrow_mut().push(cursor));
    let copied = Rc::new(RefCell::new(Vec::new()));
    let copied_clone = Rc::clone(&copied);
    controller.set_on_copy(move |text| copied_clone.borrow_mut().push(text));

    controller.window().invoke_load_older();
    assert_eq!(*loaded.borrow(), vec![50]);
    assert!(controller.state().loading_older());

    controller.window().invoke_copy("m99".into());
    assert_eq!(*copied.borrow(), vec!["message 99".to_owned()]);
}
