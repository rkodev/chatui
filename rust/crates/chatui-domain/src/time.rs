//! RFC 3339 UTC timestamp helpers.
//!
//! Shared JSON timestamps are RFC 3339 strings in UTC with a `Z` suffix. The
//! [`Timestamp`] newtype normalizes every value to UTC and always serializes
//! with millisecond precision, matching the web client's `toISOString` output.

use std::fmt;
use std::ops::Deref;

use serde::{Deserialize, Deserializer, Serialize, Serializer};
use time::OffsetDateTime;
use time::UtcOffset;
use time::format_description::BorrowedFormatItem;
use time::format_description::well_known::Rfc3339;
use time::macros::format_description;

const RFC3339_MILLIS: &[BorrowedFormatItem<'static>] =
    format_description!("[year]-[month]-[day]T[hour]:[minute]:[second].[subsecond digits:3]Z");

/// An RFC 3339 UTC timestamp.
#[derive(Clone, Copy, Debug, Eq, Hash, Ord, PartialEq, PartialOrd)]
pub struct Timestamp(OffsetDateTime);

impl Timestamp {
    /// Return the current UTC time.
    pub fn now() -> Self {
        Self(OffsetDateTime::now_utc())
    }

    /// Parse an RFC 3339 timestamp and normalize it to UTC.
    pub fn parse(value: &str) -> Result<Self, time::error::Parse> {
        let parsed = OffsetDateTime::parse(value, &Rfc3339)?;
        Ok(Self(parsed.to_offset(UtcOffset::UTC)))
    }

    /// Format the timestamp as RFC 3339 UTC with millisecond precision.
    pub fn to_rfc3339(self) -> String {
        self.0
            .format(&RFC3339_MILLIS)
            .expect("the RFC 3339 millisecond format is valid")
    }

    /// Return the underlying `OffsetDateTime`.
    pub fn as_offset_date_time(self) -> OffsetDateTime {
        self.0
    }
}

impl Deref for Timestamp {
    type Target = OffsetDateTime;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

impl From<OffsetDateTime> for Timestamp {
    fn from(value: OffsetDateTime) -> Self {
        Self(value.to_offset(UtcOffset::UTC))
    }
}

impl From<Timestamp> for OffsetDateTime {
    fn from(value: Timestamp) -> Self {
        value.0
    }
}

impl fmt::Display for Timestamp {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.to_rfc3339())
    }
}

impl Serialize for Timestamp {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_str(&self.to_rfc3339())
    }
}

impl<'de> Deserialize<'de> for Timestamp {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let value = String::deserialize(deserializer)?;
        Self::parse(&value).map_err(serde::de::Error::custom)
    }
}

/// Return the current time as an RFC 3339 UTC string with a `Z` suffix.
pub fn now_rfc3339() -> String {
    Timestamp::now().to_rfc3339()
}

/// Format a timestamp as RFC 3339 UTC with a `Z` suffix.
pub fn format_rfc3339(timestamp: Timestamp) -> String {
    timestamp.to_rfc3339()
}

/// Parse an RFC 3339 timestamp and normalize it to UTC.
pub fn parse_rfc3339(value: &str) -> Result<Timestamp, time::error::Parse> {
    Timestamp::parse(value)
}

/// Return true when `value` is an RFC 3339 UTC timestamp with a `Z` suffix.
pub fn is_rfc3339_utc(value: &str) -> bool {
    value.ends_with('Z') && Timestamp::parse(value).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixed() -> Timestamp {
        Timestamp::parse("2026-11-04T12:00:00.000Z").expect("valid timestamp")
    }

    #[test]
    fn serializes_with_z_suffix_and_milliseconds() {
        let json = serde_json::to_string(&fixed()).expect("serialize timestamp");
        assert_eq!(json, "\"2026-11-04T12:00:00.000Z\"");
    }

    #[test]
    fn round_trips_through_json() {
        let json = serde_json::to_string(&fixed()).expect("serialize timestamp");
        let parsed: Timestamp = serde_json::from_str(&json).expect("deserialize timestamp");
        assert_eq!(parsed, fixed());
    }

    #[test]
    fn normalizes_offsets_to_utc() {
        let parsed = Timestamp::parse("2026-11-04T13:00:00+01:00").expect("valid timestamp");
        assert_eq!(parsed.to_rfc3339(), "2026-11-04T12:00:00.000Z");
    }

    #[test]
    fn accepts_second_precision() {
        let parsed = Timestamp::parse("2026-11-04T12:00:00Z").expect("valid timestamp");
        assert_eq!(parsed.to_rfc3339(), "2026-11-04T12:00:00.000Z");
    }

    #[test]
    fn rejects_invalid_timestamps() {
        assert!(Timestamp::parse("not-a-timestamp").is_err());
        assert!(Timestamp::parse("2026-11-04T12:00:00").is_err());
    }

    #[test]
    fn now_ends_with_z() {
        assert!(now_rfc3339().ends_with('Z'));
    }

    #[test]
    fn detects_utc_timestamps() {
        assert!(is_rfc3339_utc("2026-11-04T12:00:00.000Z"));
        assert!(is_rfc3339_utc("2026-11-04T12:00:00Z"));
        assert!(!is_rfc3339_utc("2026-11-04T12:00:00+00:00"));
        assert!(!is_rfc3339_utc("not-a-timestamp"));
    }

    #[test]
    fn format_and_parse_helpers_agree() {
        let formatted = format_rfc3339(fixed());
        assert_eq!(formatted, "2026-11-04T12:00:00.000Z");
        assert_eq!(parse_rfc3339(&formatted).expect("parse"), fixed());
    }
}
