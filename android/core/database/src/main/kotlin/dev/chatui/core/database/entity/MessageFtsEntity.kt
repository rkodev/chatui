package dev.chatui.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * FTS4 virtual table over message text.
 *
 * FTS4 is used instead of FTS5 because it is available on every supported
 * Android SQLite build at minSdk 26. The table is standalone rather than a
 * `contentEntity` because message text is derived from `partsJson`; the
 * `MessageDao` write methods keep it synchronized in the same transaction as
 * the message row.
 *
 * `messageId` is stored but not indexed; only `text` is searchable.
 */
@Fts4(notIndexed = ["messageId"])
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0,
    @ColumnInfo(name = "messageId")
    val messageId: String,
    @ColumnInfo(name = "text")
    val text: String,
)
