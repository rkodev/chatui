package dev.chatui.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Vertical list of grouped sections. */
@Composable
fun GroupedList(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), content = content)
}

/**
 * A rounded card of rows with an optional uppercase header and footer.
 *
 * Rows are expected to be [SettingsRow]s; each row draws its own separator so
 * the section does not need to know how many children it has.
 */
@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding),
    ) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                style = Typography.footnote,
                color = colors.fgMuted,
                modifier = Modifier.padding(
                    start = dimens.cardPadding,
                    bottom = dimens.space2,
                ),
            )
        }
        Surface(
            shape = RoundedCornerShape(dimens.radiusMd),
            color = colors.bgSurfaceRaised,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
        if (footer != null) {
            Text(
                text = footer,
                style = Typography.footnote,
                color = colors.fgMuted,
                modifier = Modifier.padding(
                    start = dimens.cardPadding,
                    top = dimens.space2,
                ),
            )
        }
    }
}

/**
 * A settings-style row with an optional icon, value, chevron, and trailing slot.
 *
 * State is hoisted: the row only reports clicks through [onClick].
 */
@Composable
fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = null,
    showChevron: Boolean = false,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.rowMinHeight)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(
                    horizontal = dimens.cardPadding,
                    vertical = dimens.space2,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier
                        .size(24.dp)
                        .padding(end = dimens.space3),
                )
            }
            Text(
                text = label,
                style = Typography.body,
                color = colors.fgPrimary,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(
                    text = value,
                    style = Typography.body,
                    color = colors.fgSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(dimens.space2))
                trailing()
            }
            if (showChevron) {
                Spacer(Modifier.width(dimens.space2))
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = colors.fgMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = colors.separator,
                thickness = 0.5.dp,
                modifier = Modifier.padding(
                    start = if (icon != null) {
                        dimens.cardPadding + 24.dp + dimens.space3
                    } else {
                        dimens.cardPadding
                    },
                ),
            )
        }
    }
}
