//! Child process for the process-death conformance case.
//!
//! The parent test spawns this binary, waits for the `ready` line, and then
//! kills it with `SIGKILL` (or `TerminateProcess` on Windows). The child leaves
//! a committed `streaming` assistant message in the database, exactly as a real
//! client would when the process dies mid-stream.
//!
//! This binary is test support only. It never talks to a provider and never
//! contains a key, prompt, or personal data.

use std::io::Write;

use chatui_domain::{
    ChatParams, ContentPart, Conversation, Message, MessageRole, MessageStatus, Timestamp,
};
use chatui_storage::{
    ConversationRepository, MessageRepository, SqliteConversationRepository,
    SqliteMessageRepository, open_database,
};

const PARTIAL_TEXT: &str = "partial answer";
const PARTIAL_REASONING: &str = "partial reasoning";

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let path = std::env::args()
        .nth(1)
        .expect("usage: process_death_child <database-path>");

    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;

    runtime.block_on(run(path))
}

async fn run(path: String) -> Result<(), Box<dyn std::error::Error>> {
    let pool = open_database(&path).await?;
    let conversations = SqliteConversationRepository::new(pool.clone());
    let messages = SqliteMessageRepository::new(pool.clone());
    let now = Timestamp::parse("2026-11-08T12:00:00.000Z")?;

    conversations
        .create(&Conversation {
            id: "conversation-1".to_owned(),
            title: "Process death".to_owned(),
            system_prompt: String::new(),
            provider_id: "provider-1".to_owned(),
            model_id: "model-1".to_owned(),
            model_label: "Model One".to_owned(),
            params: ChatParams::default(),
            pinned: false,
            archived: false,
            temporary: false,
            created_at: now,
            updated_at: now,
            last_message_at: now,
            message_count: 0,
            last_error: None,
            sync: None,
        })
        .await?;

    messages
        .insert(&Message {
            id: "message-1".to_owned(),
            conversation_id: "conversation-1".to_owned(),
            sequence: 0,
            role: MessageRole::Assistant,
            parts: vec![
                ContentPart::Text {
                    text: PARTIAL_TEXT.to_owned(),
                },
                ContentPart::Reasoning {
                    text: PARTIAL_REASONING.to_owned(),
                },
            ],
            status: MessageStatus::Streaming,
            provider_id: Some("provider-1".to_owned()),
            model_id: Some("model-1".to_owned()),
            model_label: Some("Model One".to_owned()),
            usage: None,
            finish_reason: None,
            error: None,
            latency_ms: None,
            first_token_ms: None,
            created_at: now,
            updated_at: now,
            deleted_at: None,
        })
        .await?;

    // The parent kills this process after reading this line. Keep the pool
    // open so the kill happens while the database is in active use.
    println!("ready");
    std::io::stdout().flush()?;

    loop {
        std::thread::sleep(std::time::Duration::from_secs(60));
    }
}
