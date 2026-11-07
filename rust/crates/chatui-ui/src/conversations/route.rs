//! Conversation route and navigation state.
//!
//! The shell owns the top-level destination. This module owns the conversation
//! part of that state: whether the history list or a specific chat is open, and
//! which conversation was last active. New chat and selection both navigate to
//! the chat route; opening history keeps the last-active ID so the shell can
//! return to it later.

use crate::shell::Destination;

/// The conversation surface currently shown.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConversationRoute {
    /// The conversation history list.
    History,
    /// A specific conversation.
    Chat {
        /// Active conversation ID.
        conversation_id: String,
    },
}

impl ConversationRoute {
    /// Shell destination for this route.
    pub fn destination(&self) -> Destination {
        match self {
            Self::History => Destination::History,
            Self::Chat { .. } => Destination::Chat,
        }
    }

    /// Active conversation ID, or `None` on the history route.
    pub fn conversation_id(&self) -> Option<&str> {
        match self {
            Self::History => None,
            Self::Chat { conversation_id } => Some(conversation_id),
        }
    }
}

/// Conversation navigation state used by the shell.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConversationNavigation {
    route: ConversationRoute,
    last_active: Option<String>,
}

impl ConversationNavigation {
    /// Start on the history route with no last-active conversation.
    pub fn new() -> Self {
        Self {
            route: ConversationRoute::History,
            last_active: None,
        }
    }

    /// Current route.
    pub fn route(&self) -> &ConversationRoute {
        &self.route
    }

    /// Shell destination for the current route.
    pub fn destination(&self) -> Destination {
        self.route.destination()
    }

    /// Last-active conversation ID.
    pub fn last_active(&self) -> Option<&str> {
        self.last_active.as_deref()
    }

    /// Active conversation ID, or `None` on the history route.
    pub fn conversation_id(&self) -> Option<&str> {
        self.route.conversation_id()
    }

    /// True when a chat is open.
    pub fn is_chat(&self) -> bool {
        matches!(self.route, ConversationRoute::Chat { .. })
    }

    /// Open the history list without forgetting the last-active conversation.
    pub fn open_history(&mut self) {
        self.route = ConversationRoute::History;
    }

    /// Open a conversation selected from the history list.
    pub fn select(&mut self, conversation_id: &str) {
        self.last_active = Some(conversation_id.to_owned());
        self.route = ConversationRoute::Chat {
            conversation_id: conversation_id.to_owned(),
        };
    }

    /// Open a newly created conversation.
    pub fn new_chat(&mut self, conversation_id: &str) {
        self.select(conversation_id);
    }

    /// Restore the startup route from the last-active conversation.
    ///
    /// `Some` opens that chat; `None` leaves the history list open.
    pub fn restore(&mut self, conversation_id: Option<&str>) {
        match conversation_id {
            Some(conversation_id) => self.select(conversation_id),
            None => {
                self.last_active = None;
                self.route = ConversationRoute::History;
            }
        }
    }
}

impl Default for ConversationNavigation {
    fn default() -> Self {
        Self::new()
    }
}
