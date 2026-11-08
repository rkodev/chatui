//! Round-trip checks against the shared export fixture.

use chatui_domain::{Conversation, Message, Model, Provider, Settings};

const EXPORT: &str = include_str!("../../../../contracts/fixtures/expected/chatui-export.json");

#[test]
fn shared_export_fixture_deserializes_and_reserializes() {
    let value: serde_json::Value = serde_json::from_str(EXPORT).expect("parse export fixture");

    let conversation: Conversation =
        serde_json::from_value(value["conversations"][0].clone()).expect("conversation");
    let message: Message = serde_json::from_value(value["messages"][0].clone()).expect("message");
    let provider: Provider =
        serde_json::from_value(value["providers"][0].clone()).expect("provider");
    let model: Model = serde_json::from_value(value["models"][0].clone()).expect("model");
    let settings: Settings = serde_json::from_value(value["settings"].clone()).expect("settings");

    assert_eq!(conversation.id, "conv-1");
    assert_eq!(message.parts.len(), 3);
    assert_eq!(provider.preset_id, "openai");
    assert_eq!(model.remote_id, "gpt-4o-mini");
    assert_eq!(
        settings.default_model_id.as_deref(),
        Some("model-openai-gpt-4o-mini")
    );

    assert_eq!(
        serde_json::to_value(&conversation).expect("serialize conversation"),
        value["conversations"][0]
    );
    assert_eq!(
        serde_json::to_value(&message).expect("serialize message"),
        value["messages"][0]
    );
    assert_eq!(
        serde_json::to_value(&provider).expect("serialize provider"),
        value["providers"][0]
    );
    assert_eq!(
        serde_json::to_value(&model).expect("serialize model"),
        value["models"][0]
    );
    assert_eq!(
        serde_json::to_value(&settings).expect("serialize settings"),
        value["settings"]
    );
}
