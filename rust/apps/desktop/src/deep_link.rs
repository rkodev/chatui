//! `chatui://chat/<id>` deep-link parsing and routing.
//!
//! Only the `chatui` scheme, the `chat` host, and a single safe conversation id
//! are accepted. Invalid links never reach the chat view; they produce a safe
//! not-found route instead.

use url::Url;

/// Maximum accepted conversation id length.
pub const MAX_ID_LENGTH: usize = 128;

/// A validated deep link.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeepLink {
    /// Open a conversation.
    Chat {
        /// Conversation id.
        id: String,
    },
}

/// A deep-link parse error.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeepLinkError {
    /// The input is not a valid URL.
    Malformed,
    /// The scheme is not `chatui`.
    InvalidScheme,
    /// The host is not `chat`.
    InvalidHost,
    /// The path has no conversation id.
    MissingId,
    /// The path has more than one segment.
    InvalidPath,
    /// The id contains unsafe characters or is too long.
    InvalidId,
    /// Query strings and fragments are not supported.
    UnsupportedSuffix,
}

impl std::fmt::Display for DeepLinkError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let message = match self {
            Self::Malformed => "the link is not a valid URL",
            Self::InvalidScheme => "the link scheme must be chatui",
            Self::InvalidHost => "the link host must be chat",
            Self::MissingId => "the link is missing a conversation id",
            Self::InvalidPath => "the link path must contain exactly one conversation id",
            Self::InvalidId => "the conversation id is invalid",
            Self::UnsupportedSuffix => "query strings and fragments are not supported",
        };
        formatter.write_str(message)
    }
}

impl std::error::Error for DeepLinkError {}

/// Where a deep link should route.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeepLinkRoute {
    /// Open the conversation.
    Chat {
        /// Conversation id.
        id: String,
    },
    /// Onboarding must finish before the conversation can open.
    OnboardingRequired,
    /// The link is invalid or the conversation is unavailable.
    NotFound,
}

/// Parse a `chatui://chat/<id>` link.
pub fn parse(input: &str) -> Result<DeepLink, DeepLinkError> {
    let url = Url::parse(input).map_err(|_| DeepLinkError::Malformed)?;

    if url.scheme() != "chatui" {
        return Err(DeepLinkError::InvalidScheme);
    }
    if url.host_str() != Some("chat") {
        return Err(DeepLinkError::InvalidHost);
    }
    if url.query().is_some() || url.fragment().is_some() {
        return Err(DeepLinkError::UnsupportedSuffix);
    }

    let path = url.path();
    if path.is_empty() || path == "/" {
        return Err(DeepLinkError::MissingId);
    }

    let segments: Vec<&str> = url
        .path_segments()
        .ok_or(DeepLinkError::InvalidPath)?
        .collect();

    if segments.is_empty() || segments[0].is_empty() {
        return Err(DeepLinkError::MissingId);
    }
    if segments.len() != 1 {
        return Err(DeepLinkError::InvalidPath);
    }

    let id = segments[0];
    validate_id(id)?;
    Ok(DeepLink::Chat { id: id.to_owned() })
}

/// Validate a conversation id.
pub fn validate_id(id: &str) -> Result<(), DeepLinkError> {
    if id.is_empty() {
        return Err(DeepLinkError::MissingId);
    }
    if id.len() > MAX_ID_LENGTH {
        return Err(DeepLinkError::InvalidId);
    }
    if !id
        .chars()
        .all(|character| character.is_ascii_alphanumeric() || character == '-' || character == '_')
    {
        return Err(DeepLinkError::InvalidId);
    }
    Ok(())
}

/// Route a deep link, taking onboarding completion into account.
pub fn route(input: &str, onboarding_complete: bool) -> DeepLinkRoute {
    match parse(input) {
        Ok(DeepLink::Chat { id }) => {
            if onboarding_complete {
                DeepLinkRoute::Chat { id }
            } else {
                DeepLinkRoute::OnboardingRequired
            }
        }
        Err(_) => DeepLinkRoute::NotFound,
    }
}

/// Cold-start and warm-start deep-link state.
#[derive(Debug, Default)]
pub struct DeepLinkState {
    pending: Option<DeepLink>,
    last_error: Option<DeepLinkError>,
}

impl DeepLinkState {
    /// Create empty state.
    pub fn new() -> Self {
        Self::default()
    }

    /// Handle a cold-start argument list.
    ///
    /// The first argument that parses as a `chatui://` link wins. Invalid
    /// `chatui://` links are recorded as errors and route to not-found.
    pub fn handle_cold_start(
        &mut self,
        args: &[String],
        onboarding_complete: bool,
    ) -> DeepLinkRoute {
        for arg in args {
            if arg.starts_with("chatui://") {
                return self.handle(arg, onboarding_complete);
            }
        }
        DeepLinkRoute::NotFound
    }

    /// Handle a warm-start link forwarded by the single-instance listener.
    pub fn handle(&mut self, input: &str, onboarding_complete: bool) -> DeepLinkRoute {
        match parse(input) {
            Ok(link) => {
                self.last_error = None;
                if onboarding_complete {
                    self.pending = Some(link.clone());
                    match link {
                        DeepLink::Chat { id } => DeepLinkRoute::Chat { id },
                    }
                } else {
                    DeepLinkRoute::OnboardingRequired
                }
            }
            Err(error) => {
                self.last_error = Some(error);
                DeepLinkRoute::NotFound
            }
        }
    }

    /// Take the pending link, if any.
    pub fn take_pending(&mut self) -> Option<DeepLink> {
        self.pending.take()
    }

    /// The last parse error, if any.
    pub fn last_error(&self) -> Option<DeepLinkError> {
        self.last_error
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_valid_chat_link() {
        assert_eq!(
            parse("chatui://chat/abc-123_DEF"),
            Ok(DeepLink::Chat {
                id: "abc-123_DEF".to_owned()
            })
        );
    }

    #[test]
    fn rejects_invalid_scheme_and_host() {
        assert_eq!(parse("https://chat/abc"), Err(DeepLinkError::InvalidScheme));
        assert_eq!(
            parse("chatui://settings/abc"),
            Err(DeepLinkError::InvalidHost)
        );
    }

    #[test]
    fn rejects_missing_and_extra_path_segments() {
        assert_eq!(parse("chatui://chat"), Err(DeepLinkError::MissingId));
        assert_eq!(parse("chatui://chat/"), Err(DeepLinkError::MissingId));
        assert_eq!(
            parse("chatui://chat/abc/def"),
            Err(DeepLinkError::InvalidPath)
        );
    }

    #[test]
    fn rejects_query_and_fragment() {
        assert_eq!(
            parse("chatui://chat/abc?x=1"),
            Err(DeepLinkError::UnsupportedSuffix)
        );
        assert_eq!(
            parse("chatui://chat/abc#frag"),
            Err(DeepLinkError::UnsupportedSuffix)
        );
    }

    #[test]
    fn rejects_invalid_ids() {
        for input in [
            "chatui://chat/abc%20def",
            "chatui://chat/abc.def",
            "chatui://chat/abc/",
        ] {
            assert!(
                matches!(
                    parse(input),
                    Err(DeepLinkError::InvalidId)
                        | Err(DeepLinkError::InvalidPath)
                        | Err(DeepLinkError::MissingId)
                ),
                "{input} should be rejected"
            );
        }
    }

    #[test]
    fn rejects_overlong_ids() {
        let id = "a".repeat(MAX_ID_LENGTH + 1);
        assert_eq!(
            parse(&format!("chatui://chat/{id}")),
            Err(DeepLinkError::InvalidId)
        );
    }

    #[test]
    fn routes_to_onboarding_when_incomplete() {
        assert_eq!(
            route("chatui://chat/abc", false),
            DeepLinkRoute::OnboardingRequired
        );
        assert_eq!(
            route("chatui://chat/abc", true),
            DeepLinkRoute::Chat {
                id: "abc".to_owned()
            }
        );
    }

    #[test]
    fn invalid_links_route_to_not_found() {
        assert_eq!(route("not-a-link", true), DeepLinkRoute::NotFound);
        assert_eq!(
            route("chatui://chat/abc.def", true),
            DeepLinkRoute::NotFound
        );
    }

    #[test]
    fn cold_start_finds_the_first_chatui_argument() {
        let mut state = DeepLinkState::new();
        let args = vec![
            "--smoke".to_owned(),
            "chatui://chat/first".to_owned(),
            "chatui://chat/second".to_owned(),
        ];
        assert_eq!(
            state.handle_cold_start(&args, true),
            DeepLinkRoute::Chat {
                id: "first".to_owned()
            }
        );
        assert_eq!(
            state.take_pending(),
            Some(DeepLink::Chat {
                id: "first".to_owned()
            })
        );
    }

    #[test]
    fn warm_start_records_errors_and_pending_links() {
        let mut state = DeepLinkState::new();
        assert_eq!(
            state.handle("chatui://chat/warm", true),
            DeepLinkRoute::Chat {
                id: "warm".to_owned()
            }
        );
        assert_eq!(
            state.take_pending(),
            Some(DeepLink::Chat {
                id: "warm".to_owned()
            })
        );

        assert_eq!(
            state.handle("chatui://chat/abc.def", true),
            DeepLinkRoute::NotFound
        );
        assert_eq!(state.last_error(), Some(DeepLinkError::InvalidId));
    }
}
