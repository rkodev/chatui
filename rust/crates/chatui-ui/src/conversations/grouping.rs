//! Conversation history grouping and timestamp formatting.
//!
//! The sidebar shows a pinned section first, then calendar buckets derived from
//! each conversation's last-message time. The bucket order follows spec section
//! 3.2 and the sidebar layout in section 11.1:
//!
//! 1. Pinned
//! 2. Today
//! 3. Yesterday
//! 4. Previous 7 days
//! 5. Previous 30 days
//! 6. Older
//!
//! Pinned conversations are removed from the date buckets so they appear once.
//! Within every bucket conversations are newest-first by `last_message_at`, with
//! the conversation ID as a deterministic tie-breaker. Empty buckets are
//! omitted. Future timestamps are treated as Today so clock skew cannot hide a
//! conversation.

use std::collections::BTreeMap;

use chatui_domain::{Conversation, Timestamp};

/// Display order of the history buckets.
pub const HISTORY_GROUP_ORDER: [HistoryGroupKey; 6] = [
    HistoryGroupKey::Pinned,
    HistoryGroupKey::Today,
    HistoryGroupKey::Yesterday,
    HistoryGroupKey::Previous7Days,
    HistoryGroupKey::Previous30Days,
    HistoryGroupKey::Older,
];

/// One bucket in the conversation history sidebar.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum HistoryGroupKey {
    /// Conversations the user pinned, regardless of age.
    Pinned,
    /// Conversations with a last message on the current calendar day.
    Today,
    /// Conversations with a last message on the previous calendar day.
    Yesterday,
    /// Conversations from 2 to 7 calendar days ago.
    Previous7Days,
    /// Conversations from 8 to 30 calendar days ago.
    Previous30Days,
    /// Conversations older than 30 calendar days.
    Older,
}

impl HistoryGroupKey {
    /// Visible section label.
    pub const fn label(self) -> &'static str {
        match self {
            Self::Pinned => "Pinned",
            Self::Today => "Today",
            Self::Yesterday => "Yesterday",
            Self::Previous7Days => "Previous 7 days",
            Self::Previous30Days => "Previous 30 days",
            Self::Older => "Older",
        }
    }
}

/// A non-empty history bucket.
#[derive(Debug, Clone, PartialEq)]
pub struct HistoryGroup {
    /// Bucket identity.
    pub key: HistoryGroupKey,
    /// Visible section label.
    pub label: &'static str,
    /// Conversations in display order.
    pub conversations: Vec<Conversation>,
}

/// Return the calendar bucket for a timestamp relative to `now`.
///
/// The comparison uses UTC calendar days because [`Timestamp`] is normalized to
/// UTC. Future timestamps are treated as Today.
pub fn history_group_for_date(timestamp: Timestamp, now: Timestamp) -> HistoryGroupKey {
    let day_difference = now.date().to_julian_day() - timestamp.date().to_julian_day();

    if day_difference <= 0 {
        HistoryGroupKey::Today
    } else if day_difference == 1 {
        HistoryGroupKey::Yesterday
    } else if day_difference <= 7 {
        HistoryGroupKey::Previous7Days
    } else if day_difference <= 30 {
        HistoryGroupKey::Previous30Days
    } else {
        HistoryGroupKey::Older
    }
}

/// Group conversations into the pinned and date buckets.
///
/// The input is not mutated. Empty buckets are omitted and the returned groups
/// are in [`HISTORY_GROUP_ORDER`].
pub fn group_conversations(conversations: &[Conversation], now: Timestamp) -> Vec<HistoryGroup> {
    let mut buckets: BTreeMap<HistoryGroupKey, Vec<Conversation>> = BTreeMap::new();

    for conversation in conversations {
        let key = if conversation.pinned {
            HistoryGroupKey::Pinned
        } else {
            history_group_for_date(conversation.last_message_at, now)
        };

        buckets.entry(key).or_default().push(conversation.clone());
    }

    for bucket in buckets.values_mut() {
        bucket.sort_by(|left, right| {
            right
                .last_message_at
                .cmp(&left.last_message_at)
                .then_with(|| left.id.cmp(&right.id))
        });
    }

    HISTORY_GROUP_ORDER
        .iter()
        .filter_map(|key| {
            let conversations = buckets.remove(key)?;

            if conversations.is_empty() {
                None
            } else {
                Some(HistoryGroup {
                    key: *key,
                    label: key.label(),
                    conversations,
                })
            }
        })
        .collect()
}

/// Format the timestamp shown on a conversation row.
///
/// Today shows the UTC time, the current year shows month and day, and older
/// conversations include the year.
pub fn format_conversation_timestamp(timestamp: Timestamp, now: Timestamp) -> String {
    let date = timestamp.date();
    let now_date = now.date();

    if date == now_date {
        format!("{:02}:{:02}", timestamp.hour(), timestamp.minute())
    } else if date.year() == now_date.year() {
        format!(
            "{} {}",
            month_abbreviation(u8::from(date.month())),
            date.day()
        )
    } else {
        format!(
            "{} {}, {}",
            month_abbreviation(u8::from(date.month())),
            date.day(),
            date.year()
        )
    }
}

fn month_abbreviation(month: u8) -> &'static str {
    match month {
        1 => "Jan",
        2 => "Feb",
        3 => "Mar",
        4 => "Apr",
        5 => "May",
        6 => "Jun",
        7 => "Jul",
        8 => "Aug",
        9 => "Sep",
        10 => "Oct",
        11 => "Nov",
        12 => "Dec",
        _ => "",
    }
}
