//! Data-screen sections and navigation state.
//!
//! The data screen mirrors the web `/settings/data` page. It is a single
//! settings destination with internal sections so the desktop shell keeps one
//! navigation model.

/// A section of the data screen.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum DataSection {
    /// Storage usage and refresh.
    Usage,
    /// Export options and per-conversation export.
    Export,
    /// Import with merge/replace.
    Import,
    /// Clear-all with typed confirmation.
    Clear,
    /// Stored API keys and per-key deletion.
    Keys,
    /// Backup placeholder link.
    Backup,
}

impl DataSection {
    /// Every section in navigation order.
    pub const ALL: [Self; 6] = [
        Self::Usage,
        Self::Export,
        Self::Import,
        Self::Clear,
        Self::Keys,
        Self::Backup,
    ];

    /// Visible label.
    pub const fn label(self) -> &'static str {
        match self {
            Self::Usage => "Storage usage",
            Self::Export => "Export",
            Self::Import => "Import",
            Self::Clear => "Clear data",
            Self::Keys => "Stored keys",
            Self::Backup => "Backup",
        }
    }

    /// Stable focus id.
    pub const fn id(self) -> &'static str {
        match self {
            Self::Usage => "data-usage",
            Self::Export => "data-export",
            Self::Import => "data-import",
            Self::Clear => "data-clear",
            Self::Keys => "data-keys",
            Self::Backup => "data-backup",
        }
    }

    /// Index in [`DataSection::ALL`].
    pub const fn index(self) -> usize {
        match self {
            Self::Usage => 0,
            Self::Export => 1,
            Self::Import => 2,
            Self::Clear => 3,
            Self::Keys => 4,
            Self::Backup => 5,
        }
    }

    /// Resolve a section from its index.
    pub const fn from_index(index: usize) -> Option<Self> {
        match index {
            0 => Some(Self::Usage),
            1 => Some(Self::Export),
            2 => Some(Self::Import),
            3 => Some(Self::Clear),
            4 => Some(Self::Keys),
            5 => Some(Self::Backup),
            _ => None,
        }
    }
}

/// Data-screen navigation state.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DataNavigation {
    section: DataSection,
}

impl DataNavigation {
    /// Start on storage usage.
    pub fn new() -> Self {
        Self {
            section: DataSection::Usage,
        }
    }

    /// Start on a specific section.
    pub fn with_section(section: DataSection) -> Self {
        Self { section }
    }

    /// Current section.
    pub fn section(&self) -> DataSection {
        self.section
    }

    /// Select a section.
    pub fn select(&mut self, section: DataSection) {
        self.section = section;
    }

    /// Index of the current section.
    pub fn index(&self) -> usize {
        self.section.index()
    }
}

impl Default for DataNavigation {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_section_has_a_label_id_and_index() {
        for section in DataSection::ALL {
            assert!(!section.label().is_empty());
            assert!(!section.id().is_empty());
            assert_eq!(DataSection::from_index(section.index()), Some(section));
        }
    }

    #[test]
    fn navigation_starts_on_usage_and_selects_sections() {
        let mut navigation = DataNavigation::new();
        assert_eq!(navigation.section(), DataSection::Usage);
        assert_eq!(navigation.index(), 0);

        navigation.select(DataSection::Clear);
        assert_eq!(navigation.section(), DataSection::Clear);
        assert_eq!(navigation.index(), 3);
    }
}
