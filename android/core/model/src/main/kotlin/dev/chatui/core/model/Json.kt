package dev.chatui.core.model

import kotlinx.serialization.json.Json

/**
 * Shared JSON configuration for domain serialization.
 *
 * Unknown keys are ignored so newer exports remain readable. Defaults are not
 * encoded, which keeps optional pricing fields absent rather than `null`.
 * Explicit nulls are encoded for required nullable fields so the contract
 * shape is preserved.
 */
val ChatUiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = true
}
