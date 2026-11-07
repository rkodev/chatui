//! Design tokens from spec section 11.12.
//!
//! The semantic names mirror the shared web and Android token names. The
//! values mirror `web/src/styles/tokens.css` and `web/src/styles/themes.css`
//! so the Rust desktop client and the web client stay visually consistent.
//!
//! The token names are exposed as string constants and as [`TOKEN_NAMES`] so
//! tests and review tooling can assert that every name from the spec exists in
//! Rust. The [`Tokens`] value carries the resolved values for one theme.

/// An sRGB color with 8-bit channels.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Rgba {
    /// Red channel.
    pub r: u8,
    /// Green channel.
    pub g: u8,
    /// Blue channel.
    pub b: u8,
    /// Alpha channel.
    pub a: u8,
}

impl Rgba {
    /// Fully transparent black.
    pub const TRANSPARENT: Self = Self::from_rgba(0, 0, 0, 0);

    /// Create an opaque color from red, green, and blue channels.
    pub const fn rgb(r: u8, g: u8, b: u8) -> Self {
        Self::from_rgba(r, g, b, 255)
    }

    /// Create a color from red, green, blue, and alpha channels.
    pub const fn from_rgba(r: u8, g: u8, b: u8, a: u8) -> Self {
        Self { r, g, b, a }
    }

    /// Parse `#rgb`, `#rrggbb`, or `#rrggbbaa` (the leading `#` is optional).
    pub fn from_hex(hex: &str) -> Option<Self> {
        let hex = hex.strip_prefix('#').unwrap_or(hex);
        let parse = |value: &str| u8::from_str_radix(value, 16).ok();

        match hex.len() {
            3 => {
                let r = parse(&hex[0..1])?;
                let g = parse(&hex[1..2])?;
                let b = parse(&hex[2..3])?;
                Some(Self::rgb(r * 17, g * 17, b * 17))
            }
            6 => Some(Self::rgb(
                parse(&hex[0..2])?,
                parse(&hex[2..4])?,
                parse(&hex[4..6])?,
            )),
            8 => Some(Self::from_rgba(
                parse(&hex[0..2])?,
                parse(&hex[2..4])?,
                parse(&hex[4..6])?,
                parse(&hex[6..8])?,
            )),
            _ => None,
        }
    }

    /// Format the color as `#rrggbb` or `#rrggbbaa`.
    pub fn to_hex(self) -> String {
        if self.a == 255 {
            format!("#{:02x}{:02x}{:02x}", self.r, self.g, self.b)
        } else {
            format!("#{:02x}{:02x}{:02x}{:02x}", self.r, self.g, self.b, self.a)
        }
    }

    /// Convert to a Slint color.
    pub fn to_slint(self) -> slint::Color {
        slint::Color::from_argb_u8(self.a, self.r, self.g, self.b)
    }

    /// WCAG relative luminance.
    pub fn relative_luminance(self) -> f64 {
        fn channel(value: u8) -> f64 {
            let value = f64::from(value) / 255.0;
            if value <= 0.039_28 {
                value / 12.92
            } else {
                ((value + 0.055) / 1.055).powf(2.4)
            }
        }

        0.2126 * channel(self.r) + 0.7152 * channel(self.g) + 0.0722 * channel(self.b)
    }

    /// WCAG contrast ratio between two opaque colors.
    pub fn contrast_ratio(self, other: Self) -> f64 {
        let a = self.relative_luminance();
        let b = other.relative_luminance();
        let (lighter, darker) = if a >= b { (a, b) } else { (b, a) };
        (lighter + 0.05) / (darker + 0.05)
    }
}

// Color token names.
/// `color.bg.canvas`
pub const COLOR_BG_CANVAS: &str = "color.bg.canvas";
/// `color.bg.surface`
pub const COLOR_BG_SURFACE: &str = "color.bg.surface";
/// `color.bg.surface-raised`
pub const COLOR_BG_SURFACE_RAISED: &str = "color.bg.surface-raised";
/// `color.bg.user-bubble`
pub const COLOR_BG_USER_BUBBLE: &str = "color.bg.user-bubble";
/// `color.bg.code`
pub const COLOR_BG_CODE: &str = "color.bg.code";
/// `color.fg.primary`
pub const COLOR_FG_PRIMARY: &str = "color.fg.primary";
/// `color.fg.secondary`
pub const COLOR_FG_SECONDARY: &str = "color.fg.secondary";
/// `color.fg.muted`
pub const COLOR_FG_MUTED: &str = "color.fg.muted";
/// `color.fg.on-accent`
pub const COLOR_FG_ON_ACCENT: &str = "color.fg.on-accent";
/// `color.accent`
pub const COLOR_ACCENT: &str = "color.accent";
/// `color.accent-hover`
pub const COLOR_ACCENT_HOVER: &str = "color.accent-hover";
/// `color.border.subtle`
pub const COLOR_BORDER_SUBTLE: &str = "color.border.subtle";
/// `color.border.strong`
pub const COLOR_BORDER_STRONG: &str = "color.border.strong";
/// `color.danger`
pub const COLOR_DANGER: &str = "color.danger";
/// `color.warning`
pub const COLOR_WARNING: &str = "color.warning";
/// `color.success`
pub const COLOR_SUCCESS: &str = "color.success";

// Spacing token names.
/// `space.1`
pub const SPACE_1: &str = "space.1";
/// `space.2`
pub const SPACE_2: &str = "space.2";
/// `space.3`
pub const SPACE_3: &str = "space.3";
/// `space.4`
pub const SPACE_4: &str = "space.4";
/// `space.5`
pub const SPACE_5: &str = "space.5";
/// `space.6`
pub const SPACE_6: &str = "space.6";
/// `space.7`
pub const SPACE_7: &str = "space.7";

// Radius token names.
/// `radius.sm`
pub const RADIUS_SM: &str = "radius.sm";
/// `radius.md`
pub const RADIUS_MD: &str = "radius.md";
/// `radius.lg`
pub const RADIUS_LG: &str = "radius.lg";
/// `radius.full`
pub const RADIUS_FULL: &str = "radius.full";

// Typography token names.
/// `font.body.family`
pub const FONT_BODY_FAMILY: &str = "font.body.family";
/// `font.body.size`
pub const FONT_BODY_SIZE: &str = "font.body.size";
/// `font.body.line-height`
pub const FONT_BODY_LINE_HEIGHT: &str = "font.body.line-height";
/// `font.code.family`
pub const FONT_CODE_FAMILY: &str = "font.code.family";
/// `font.code.size`
pub const FONT_CODE_SIZE: &str = "font.code.size";
/// `font.heading.family`
pub const FONT_HEADING_FAMILY: &str = "font.heading.family";
/// `font.heading.size`
pub const FONT_HEADING_SIZE: &str = "font.heading.size";
/// `font.heading.size-1`
pub const FONT_HEADING_SIZE_1: &str = "font.heading.size-1";
/// `font.heading.size-2`
pub const FONT_HEADING_SIZE_2: &str = "font.heading.size-2";
/// `font.heading.size-3`
pub const FONT_HEADING_SIZE_3: &str = "font.heading.size-3";
/// `font.heading.line-height`
pub const FONT_HEADING_LINE_HEIGHT: &str = "font.heading.line-height";
/// `font.heading.weight`
pub const FONT_HEADING_WEIGHT: &str = "font.heading.weight";

/// Every semantic token name from spec section 11.12.
pub const TOKEN_NAMES: &[&str] = &[
    COLOR_BG_CANVAS,
    COLOR_BG_SURFACE,
    COLOR_BG_SURFACE_RAISED,
    COLOR_BG_USER_BUBBLE,
    COLOR_BG_CODE,
    COLOR_FG_PRIMARY,
    COLOR_FG_SECONDARY,
    COLOR_FG_MUTED,
    COLOR_FG_ON_ACCENT,
    COLOR_ACCENT,
    COLOR_ACCENT_HOVER,
    COLOR_BORDER_SUBTLE,
    COLOR_BORDER_STRONG,
    COLOR_DANGER,
    COLOR_WARNING,
    COLOR_SUCCESS,
    SPACE_1,
    SPACE_2,
    SPACE_3,
    SPACE_4,
    SPACE_5,
    SPACE_6,
    SPACE_7,
    RADIUS_SM,
    RADIUS_MD,
    RADIUS_LG,
    RADIUS_FULL,
    FONT_BODY_FAMILY,
    FONT_BODY_SIZE,
    FONT_BODY_LINE_HEIGHT,
    FONT_CODE_FAMILY,
    FONT_CODE_SIZE,
    FONT_HEADING_FAMILY,
    FONT_HEADING_SIZE,
    FONT_HEADING_SIZE_1,
    FONT_HEADING_SIZE_2,
    FONT_HEADING_SIZE_3,
    FONT_HEADING_LINE_HEIGHT,
    FONT_HEADING_WEIGHT,
];

/// The default body font family.
pub const BODY_FONT_FAMILY: &str = "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
/// The default code font family.
pub const CODE_FONT_FAMILY: &str =
    "ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, 'Liberation Mono', monospace";

/// Resolved design tokens for one theme.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Tokens {
    /// `color.bg.canvas`
    pub color_bg_canvas: Rgba,
    /// `color.bg.surface`
    pub color_bg_surface: Rgba,
    /// `color.bg.surface-raised`
    pub color_bg_surface_raised: Rgba,
    /// `color.bg.user-bubble`
    pub color_bg_user_bubble: Rgba,
    /// `color.bg.code`
    pub color_bg_code: Rgba,
    /// `color.fg.primary`
    pub color_fg_primary: Rgba,
    /// `color.fg.secondary`
    pub color_fg_secondary: Rgba,
    /// `color.fg.muted`
    pub color_fg_muted: Rgba,
    /// `color.fg.on-accent`
    pub color_fg_on_accent: Rgba,
    /// `color.accent`
    pub color_accent: Rgba,
    /// `color.accent-hover`
    pub color_accent_hover: Rgba,
    /// `color.border.subtle`
    pub color_border_subtle: Rgba,
    /// `color.border.strong`
    pub color_border_strong: Rgba,
    /// `color.danger`
    pub color_danger: Rgba,
    /// `color.warning`
    pub color_warning: Rgba,
    /// `color.success`
    pub color_success: Rgba,
    /// `space.1`
    pub space_1: f32,
    /// `space.2`
    pub space_2: f32,
    /// `space.3`
    pub space_3: f32,
    /// `space.4`
    pub space_4: f32,
    /// `space.5`
    pub space_5: f32,
    /// `space.6`
    pub space_6: f32,
    /// `space.7`
    pub space_7: f32,
    /// `radius.sm`
    pub radius_sm: f32,
    /// `radius.md`
    pub radius_md: f32,
    /// `radius.lg`
    pub radius_lg: f32,
    /// `radius.full`
    pub radius_full: f32,
    /// `font.body.family`
    pub font_body_family: &'static str,
    /// `font.body.size`
    pub font_body_size: f32,
    /// `font.body.line-height`
    pub font_body_line_height: f32,
    /// `font.code.family`
    pub font_code_family: &'static str,
    /// `font.code.size`
    pub font_code_size: f32,
    /// `font.heading.family`
    pub font_heading_family: &'static str,
    /// `font.heading.size`
    pub font_heading_size: f32,
    /// `font.heading.size-1`
    pub font_heading_size_1: f32,
    /// `font.heading.size-2`
    pub font_heading_size_2: f32,
    /// `font.heading.size-3`
    pub font_heading_size_3: f32,
    /// `font.heading.line-height`
    pub font_heading_line_height: f32,
    /// `font.heading.weight`
    pub font_heading_weight: u16,
    /// Focus ring width in logical pixels.
    pub focus_ring_width: f32,
    /// Focus ring offset in logical pixels.
    pub focus_ring_offset: f32,
    /// Focus ring color.
    pub focus_ring_color: Rgba,
    /// Fast motion duration in milliseconds.
    pub motion_duration_fast: u64,
    /// Normal motion duration in milliseconds.
    pub motion_duration_normal: u64,
}

impl Tokens {
    /// Light theme tokens.
    pub const fn light() -> Self {
        Self {
            color_bg_canvas: Rgba::rgb(0xff, 0xff, 0xff),
            color_bg_surface: Rgba::rgb(0xf7, 0xf7, 0xf8),
            color_bg_surface_raised: Rgba::rgb(0xff, 0xff, 0xff),
            color_bg_user_bubble: Rgba::rgb(0xe8, 0xf0, 0xfe),
            color_bg_code: Rgba::rgb(0xf3, 0xf4, 0xf6),
            color_fg_primary: Rgba::rgb(0x1a, 0x1a, 0x1a),
            color_fg_secondary: Rgba::rgb(0x4a, 0x4a, 0x4a),
            color_fg_muted: Rgba::rgb(0x5f, 0x63, 0x68),
            color_fg_on_accent: Rgba::rgb(0xff, 0xff, 0xff),
            color_accent: Rgba::rgb(0x1a, 0x56, 0xdb),
            color_accent_hover: Rgba::rgb(0x1e, 0x40, 0xaf),
            color_border_subtle: Rgba::rgb(0xd1, 0xd5, 0xdb),
            color_border_strong: Rgba::rgb(0x6b, 0x72, 0x80),
            color_danger: Rgba::rgb(0xb9, 0x1c, 0x1c),
            color_warning: Rgba::rgb(0x92, 0x40, 0x0e),
            color_success: Rgba::rgb(0x16, 0x65, 0x34),
            space_1: 4.0,
            space_2: 8.0,
            space_3: 12.0,
            space_4: 16.0,
            space_5: 24.0,
            space_6: 32.0,
            space_7: 48.0,
            radius_sm: 6.0,
            radius_md: 10.0,
            radius_lg: 16.0,
            radius_full: 9999.0,
            font_body_family: BODY_FONT_FAMILY,
            font_body_size: 16.0,
            font_body_line_height: 24.0,
            font_code_family: CODE_FONT_FAMILY,
            font_code_size: 14.0,
            font_heading_family: BODY_FONT_FAMILY,
            font_heading_size: 20.0,
            font_heading_size_1: 32.0,
            font_heading_size_2: 24.0,
            font_heading_size_3: 20.0,
            font_heading_line_height: 25.0,
            font_heading_weight: 600,
            focus_ring_width: 2.0,
            focus_ring_offset: 2.0,
            focus_ring_color: Rgba::rgb(0x1a, 0x56, 0xdb),
            motion_duration_fast: 120,
            motion_duration_normal: 200,
        }
    }

    /// Dark theme tokens.
    pub const fn dark() -> Self {
        Self {
            color_bg_canvas: Rgba::rgb(0x0f, 0x11, 0x15),
            color_bg_surface: Rgba::rgb(0x17, 0x1a, 0x21),
            color_bg_surface_raised: Rgba::rgb(0x1f, 0x23, 0x2b),
            color_bg_user_bubble: Rgba::rgb(0x1e, 0x3a, 0x5f),
            color_bg_code: Rgba::rgb(0x11, 0x18, 0x27),
            color_fg_primary: Rgba::rgb(0xf3, 0xf4, 0xf6),
            color_fg_secondary: Rgba::rgb(0xc7, 0xcb, 0xd1),
            color_fg_muted: Rgba::rgb(0x9c, 0xa3, 0xaf),
            color_fg_on_accent: Rgba::rgb(0x0b, 0x10, 0x20),
            color_accent: Rgba::rgb(0x60, 0xa5, 0xfa),
            color_accent_hover: Rgba::rgb(0x93, 0xc5, 0xfd),
            color_border_subtle: Rgba::rgb(0x2f, 0x35, 0x42),
            color_border_strong: Rgba::rgb(0x6b, 0x72, 0x80),
            color_danger: Rgba::rgb(0xf8, 0x71, 0x71),
            color_warning: Rgba::rgb(0xfb, 0xbf, 0x24),
            color_success: Rgba::rgb(0x4a, 0xde, 0x80),
            space_1: 4.0,
            space_2: 8.0,
            space_3: 12.0,
            space_4: 16.0,
            space_5: 24.0,
            space_6: 32.0,
            space_7: 48.0,
            radius_sm: 6.0,
            radius_md: 10.0,
            radius_lg: 16.0,
            radius_full: 9999.0,
            font_body_family: BODY_FONT_FAMILY,
            font_body_size: 16.0,
            font_body_line_height: 24.0,
            font_code_family: CODE_FONT_FAMILY,
            font_code_size: 14.0,
            font_heading_family: BODY_FONT_FAMILY,
            font_heading_size: 20.0,
            font_heading_size_1: 32.0,
            font_heading_size_2: 24.0,
            font_heading_size_3: 20.0,
            font_heading_line_height: 25.0,
            font_heading_weight: 600,
            focus_ring_width: 2.0,
            focus_ring_offset: 2.0,
            focus_ring_color: Rgba::rgb(0x60, 0xa5, 0xfa),
            motion_duration_fast: 120,
            motion_duration_normal: 200,
        }
    }
}

impl Default for Tokens {
    fn default() -> Self {
        Self::light()
    }
}

/// Density-adjusted spacing values.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DensityScale {
    /// Density-adjusted `space.1`.
    pub space_1: f32,
    /// Density-adjusted `space.2`.
    pub space_2: f32,
    /// Density-adjusted `space.3`.
    pub space_3: f32,
    /// Density-adjusted `space.4`.
    pub space_4: f32,
    /// Density-adjusted `space.5`.
    pub space_5: f32,
    /// Density-adjusted `space.6`.
    pub space_6: f32,
    /// Density-adjusted `space.7`.
    pub space_7: f32,
    /// Minimum control height.
    pub control_height: f32,
    /// Default gap between controls.
    pub gap: f32,
    /// Default container padding.
    pub padding: f32,
}

impl DensityScale {
    /// Comfortable density, using the semantic spacing scale unchanged.
    pub const fn comfortable(tokens: &Tokens) -> Self {
        Self {
            space_1: tokens.space_1,
            space_2: tokens.space_2,
            space_3: tokens.space_3,
            space_4: tokens.space_4,
            space_5: tokens.space_5,
            space_6: tokens.space_6,
            space_7: tokens.space_7,
            control_height: 40.0,
            gap: tokens.space_3,
            padding: tokens.space_4,
        }
    }

    /// Compact density, mirroring `web/src/styles/layout.css`.
    pub const fn compact() -> Self {
        Self {
            space_1: 2.0,
            space_2: 4.0,
            space_3: 8.0,
            space_4: 12.0,
            space_5: 16.0,
            space_6: 24.0,
            space_7: 32.0,
            control_height: 32.0,
            gap: 8.0,
            padding: 12.0,
        }
    }
}

/// Chat text size and line height.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ChatTypography {
    /// Chat text size in logical pixels.
    pub size: f32,
    /// Chat line height in logical pixels.
    pub line_height: f32,
}

impl ChatTypography {
    /// Small chat text.
    pub const fn sm() -> Self {
        Self {
            size: 14.0,
            line_height: 20.3,
        }
    }

    /// Medium chat text.
    pub const fn md() -> Self {
        Self {
            size: 16.0,
            line_height: 24.0,
        }
    }

    /// Large chat text.
    pub const fn lg() -> Self {
        Self {
            size: 18.0,
            line_height: 28.8,
        }
    }
}

/// Motion durations after applying the reduced-motion preference.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Motion {
    /// Fast transition duration in milliseconds.
    pub fast_ms: u64,
    /// Normal transition duration in milliseconds.
    pub normal_ms: u64,
    /// Whether reduced motion is active.
    pub reduced: bool,
}

impl Motion {
    /// Standard motion.
    pub const fn standard() -> Self {
        Self {
            fast_ms: 120,
            normal_ms: 200,
            reduced: false,
        }
    }

    /// Reduced motion: transitions are effectively disabled.
    pub const fn reduced() -> Self {
        Self {
            fast_ms: 0,
            normal_ms: 0,
            reduced: true,
        }
    }

    /// Resolve motion from a reduced-motion preference.
    pub const fn for_preference(reduced: bool) -> Self {
        if reduced {
            Self::reduced()
        } else {
            Self::standard()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_spec_token_name_exists() {
        let required = [
            "color.bg.canvas",
            "color.bg.surface",
            "color.bg.surface-raised",
            "color.bg.user-bubble",
            "color.bg.code",
            "color.fg.primary",
            "color.fg.secondary",
            "color.fg.muted",
            "color.fg.on-accent",
            "color.accent",
            "color.accent-hover",
            "color.border.subtle",
            "color.border.strong",
            "color.danger",
            "color.warning",
            "color.success",
            "space.1",
            "space.2",
            "space.3",
            "space.4",
            "space.5",
            "space.6",
            "space.7",
            "radius.sm",
            "radius.md",
            "radius.lg",
            "radius.full",
            "font.body.family",
            "font.body.size",
            "font.body.line-height",
            "font.code.family",
            "font.code.size",
            "font.heading.family",
            "font.heading.size",
            "font.heading.size-1",
            "font.heading.size-2",
            "font.heading.size-3",
            "font.heading.line-height",
            "font.heading.weight",
        ];

        for name in required {
            assert!(TOKEN_NAMES.contains(&name), "missing token name {name}");
        }
    }

    #[test]
    fn token_names_are_unique() {
        let mut names = TOKEN_NAMES.to_vec();
        names.sort_unstable();
        names.dedup();
        assert_eq!(names.len(), TOKEN_NAMES.len());
    }

    #[test]
    fn spacing_and_radius_match_the_spec() {
        let tokens = Tokens::light();
        assert_eq!(
            [
                tokens.space_1,
                tokens.space_2,
                tokens.space_3,
                tokens.space_4,
                tokens.space_5,
                tokens.space_6,
                tokens.space_7,
            ],
            [4.0, 8.0, 12.0, 16.0, 24.0, 32.0, 48.0]
        );
        assert_eq!(
            [
                tokens.radius_sm,
                tokens.radius_md,
                tokens.radius_lg,
                tokens.radius_full,
            ],
            [6.0, 10.0, 16.0, 9999.0]
        );
    }

    #[test]
    fn light_and_dark_palettes_differ() {
        let light = Tokens::light();
        let dark = Tokens::dark();
        assert_ne!(light.color_bg_canvas, dark.color_bg_canvas);
        assert_ne!(light.color_fg_primary, dark.color_fg_primary);
        assert_ne!(light.color_accent, dark.color_accent);
    }

    #[test]
    fn hex_round_trip() {
        assert_eq!(Rgba::from_hex("#1a56db"), Some(Rgba::rgb(0x1a, 0x56, 0xdb)));
        assert_eq!(Rgba::from_hex("fff"), Some(Rgba::rgb(255, 255, 255)));
        assert_eq!(
            Rgba::from_hex("#00000080"),
            Some(Rgba::from_rgba(0, 0, 0, 0x80))
        );
        assert_eq!(Rgba::rgb(0x1a, 0x56, 0xdb).to_hex(), "#1a56db");
        assert_eq!(Rgba::from_rgba(0, 0, 0, 0x80).to_hex(), "#00000080");
        assert_eq!(Rgba::from_hex("nope"), None);
    }

    #[test]
    fn body_text_meets_wcag_contrast_in_both_themes() {
        for tokens in [Tokens::light(), Tokens::dark()] {
            let backgrounds = [
                tokens.color_bg_canvas,
                tokens.color_bg_surface,
                tokens.color_bg_surface_raised,
                tokens.color_bg_user_bubble,
                tokens.color_bg_code,
            ];
            let foregrounds = [
                tokens.color_fg_primary,
                tokens.color_fg_secondary,
                tokens.color_fg_muted,
            ];

            for background in backgrounds {
                for foreground in foregrounds {
                    let ratio = foreground.contrast_ratio(background);
                    assert!(
                        ratio >= 4.5,
                        "contrast {ratio:.2} below 4.5 for {foreground:?} on {background:?}"
                    );
                }
            }

            for accent in [tokens.color_accent, tokens.color_accent_hover] {
                let ratio = tokens.color_fg_on_accent.contrast_ratio(accent);
                assert!(
                    ratio >= 4.5,
                    "on-accent contrast {ratio:.2} below 4.5 for {accent:?}"
                );
            }

            for status in [
                tokens.color_danger,
                tokens.color_warning,
                tokens.color_success,
            ] {
                let ratio = status.contrast_ratio(tokens.color_bg_canvas);
                assert!(
                    ratio >= 4.5,
                    "status contrast {ratio:.2} below 4.5 for {status:?}"
                );
            }
        }
    }

    #[test]
    fn density_scales_match_the_web_contract() {
        let tokens = Tokens::light();
        let comfortable = DensityScale::comfortable(&tokens);
        assert_eq!(comfortable.space_1, 4.0);
        assert_eq!(comfortable.control_height, 40.0);
        assert_eq!(comfortable.gap, 12.0);
        assert_eq!(comfortable.padding, 16.0);

        let compact = DensityScale::compact();
        assert_eq!(compact.space_1, 2.0);
        assert_eq!(compact.space_7, 32.0);
        assert_eq!(compact.control_height, 32.0);
        assert_eq!(compact.gap, 8.0);
        assert_eq!(compact.padding, 12.0);
    }

    #[test]
    fn chat_typography_matches_the_web_contract() {
        assert_eq!(ChatTypography::sm().size, 14.0);
        assert_eq!(ChatTypography::md().size, 16.0);
        assert_eq!(ChatTypography::lg().size, 18.0);
        assert!(ChatTypography::sm().line_height < ChatTypography::md().line_height);
        assert!(ChatTypography::md().line_height < ChatTypography::lg().line_height);
    }

    #[test]
    fn reduced_motion_disables_transitions() {
        assert_eq!(Motion::for_preference(false), Motion::standard());
        assert_eq!(Motion::for_preference(true), Motion::reduced());
        assert_eq!(Motion::reduced().fast_ms, 0);
        assert_eq!(Motion::reduced().normal_ms, 0);
    }
}
