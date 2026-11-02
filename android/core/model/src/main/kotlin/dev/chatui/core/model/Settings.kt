@file:UseSerializers(InstantIso8601Serializer::class)

package dev.chatui.core.model

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Theme preference from spec section 6.2. */
@Serializable
enum class Theme {
    @SerialName("system")
    SYSTEM,

    @SerialName("light")
    LIGHT,

    @SerialName("dark")
    DARK,
}

/** UI density preference from spec section 6.2. */
@Serializable
enum class Density {
    @SerialName("comfortable")
    COMFORTABLE,

    @SerialName("compact")
    COMPACT,
}

/** Chat font size preference from spec section 6.2. */
@Serializable
enum class ChatFontSize {
    @SerialName("sm")
    SM,

    @SerialName("md")
    MD,

    @SerialName("lg")
    LG,
}

/** Send-key preference from spec section 6.2. */
@Serializable
enum class SendKey {
    @SerialName("enter")
    ENTER,

    @SerialName("mod-enter")
    MOD_ENTER,
}

/** Future backup backend from spec section 6.2. */
@Serializable
enum class BackupBackend {
    @SerialName("s3")
    S3,

    @SerialName("azure-blob")
    AZURE_BLOB,

    @SerialName("gcs")
    GCS,

    @SerialName("webdav")
    WEBDAV,
}

/** Backup encryption algorithm from spec section 6.2. */
@Serializable
enum class BackupEncryption {
    @SerialName("aes-256-gcm")
    AES_256_GCM,
}

/** Backup key derivation function from spec section 6.2. */
@Serializable
enum class BackupKdf {
    @SerialName("pbkdf2-sha256")
    PBKDF2_SHA256,

    @SerialName("argon2id")
    ARGON2ID,
}

/** Future backup configuration from spec section 6.2. */
@Serializable
data class BackupConfig(
    val enabled: Boolean,
    val backend: BackupBackend,
    val endpoint: String?,
    val region: String?,
    val bucket: String,
    val prefix: String,
    val credentialSecretId: String?,
    val encryption: BackupEncryption,
    val kdf: BackupKdf,
    val kdfSalt: String?,
    val lastSyncAt: Instant?,
    val lastManifestEtag: String?,
    val deviceId: String,
)

/** Settings entity from spec section 6.2. */
@Serializable
data class Settings(
    val theme: Theme,
    val density: Density,
    val chatFontSize: ChatFontSize,
    val sendKey: SendKey,
    val defaultProviderId: String?,
    val defaultModelId: String?,
    val defaultSystemPrompt: String,
    val defaultParams: ChatParams,
    val rememberKeysOnWeb: Boolean,
    val showTokenUsage: Boolean,
    val loadRemoteImages: Boolean,
    val lastActiveConversationId: String?,
    val backup: BackupConfig?,
    val onboardingCompleted: Boolean,
)
