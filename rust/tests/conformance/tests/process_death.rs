//! Process-death and restart conformance case.
//!
//! The child binary commits a `streaming` assistant message and then waits. The
//! test kills it with `SIGKILL` (or `TerminateProcess` on Windows), reopens the
//! same database, and runs the startup recovery pass. This is the desktop
//! equivalent of spec section 15.2 case 26: the message must become `cancelled`
//! and the partial text and reasoning must survive.

use std::io::{BufRead, BufReader};
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::sync::mpsc;
use std::time::Duration;

use chatui_domain::{ContentPart, FinishReason, MessageStatus};
use chatui_storage::{
    AttachmentStore, INTERRUPTION_NOTE, MessageRepository, RecoveryService,
    SqliteAttachmentRepository, SqliteMessageRepository, open_database,
};

const PARTIAL_TEXT: &str = "partial answer";
const PARTIAL_REASONING: &str = "partial reasoning";

#[test]
fn process_death_preserves_partial_content_and_marks_cancelled() {
    let directory = tempfile::tempdir().expect("create temporary directory");
    let database_path = directory.path().join("chatui.sqlite3");

    let mut child = Command::new(env!("CARGO_BIN_EXE_process_death_child"))
        .arg(&database_path)
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .expect("spawn process-death child");

    let stdout = child.stdout.take().expect("child stdout");
    let (ready_tx, ready_rx) = mpsc::channel();
    std::thread::spawn(move || {
        let mut reader = BufReader::new(stdout);
        let mut line = String::new();
        loop {
            line.clear();
            match reader.read_line(&mut line) {
                Ok(0) => {
                    let _ = ready_tx.send(false);
                    return;
                }
                Ok(_) if line.trim() == "ready" => {
                    let _ = ready_tx.send(true);
                    return;
                }
                Ok(_) => {}
                Err(_) => {
                    let _ = ready_tx.send(false);
                    return;
                }
            }
        }
    });

    match ready_rx.recv_timeout(Duration::from_secs(30)) {
        Ok(true) => {}
        _ => {
            let _ = child.kill();
            let _ = child.wait();
            panic!("process-death child did not become ready");
        }
    }

    // Kill the process while the streaming row is committed and the database
    // connection is still open. `kill` is SIGKILL on Unix and TerminateProcess
    // on Windows, so no shutdown or cleanup code runs.
    child.kill().expect("kill process-death child");
    let status = child.wait().expect("wait for process-death child");
    assert!(
        !status.success(),
        "the child must be terminated, not exit cleanly"
    );

    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .expect("build runtime");

    runtime.block_on(async {
        let pool = open_database(&database_path)
            .await
            .expect("reopen the database after process death");
        let messages = SqliteMessageRepository::new(pool.clone());
        let attachments = SqliteAttachmentRepository::new(pool.clone());
        let store = AttachmentStore::new(directory.path());

        let service =
            RecoveryService::new(Arc::new(messages.clone()), Arc::new(attachments), store);
        let summary = service.recover().await.expect("run startup recovery");

        assert_eq!(summary.recovered.len(), 1, "one streaming row recovered");
        assert!(summary.failed.is_empty(), "no recovery failures");

        let recovered = &summary.recovered[0];
        assert_eq!(recovered.status, MessageStatus::Cancelled);
        assert_eq!(recovered.finish_reason, Some(FinishReason::Cancelled));
        assert_eq!(
            recovered.parts,
            vec![
                ContentPart::Text {
                    text: PARTIAL_TEXT.to_owned(),
                },
                ContentPart::Reasoning {
                    text: PARTIAL_REASONING.to_owned(),
                },
            ],
            "partial text and reasoning survive the kill"
        );

        let error = recovered.error.as_ref().expect("interruption error");
        assert_eq!(error.message, INTERRUPTION_NOTE);
        assert!(!error.retryable);

        let loaded = messages
            .get("message-1")
            .await
            .expect("read the recovered message")
            .expect("the message still exists");
        assert_eq!(loaded.status, MessageStatus::Cancelled);
        assert_eq!(loaded.parts, recovered.parts);

        pool.close().await;
    });
}
