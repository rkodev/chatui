package dev.chatui.core.network

import dev.chatui.core.model.ProviderAuthType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Raised when the provider contract is missing, malformed, or inconsistent. */
class ProviderRegistryException(
    message: String,
    val issues: List<String> = emptyList(),
) : IllegalArgumentException(message)

/**
 * Presets enabled in M2.
 *
 * Anthropic and Gemini have native adapters. Ollama stays in contracts but is
 * not selectable until its M2-06 adapter task lands. `custom` is enabled
 * because Custom providers use the OpenAI-compatible transport.
 */
val M2_ENABLED_PRESET_IDS: Set<String> = setOf(
    "openrouter",
    "openai",
    "anthropic",
    "google",
    "groq",
    "mistral",
    "deepseek",
    "xai",
    "together",
    "fireworks",
    "lmstudio",
    "llamacpp",
    "custom",
)

fun isPresetEnabledInM2(id: String): Boolean = id in M2_ENABLED_PRESET_IDS

enum class PresetAvailabilityStatus {
    AVAILABLE,
    COMING_LATER,
}

data class PresetAvailability(
    val preset: ProviderPreset,
    val enabled: Boolean,
    val selectable: Boolean,
    val status: PresetAvailabilityStatus,
)

/**
 * Validated provider presets loaded from `contracts/providers.json`.
 *
 * The registry never contains a hand-written copy of provider metadata. It
 * reads the contract resource copied into the module by Gradle and validates
 * required fields, duplicate IDs, and the single default/reference/required
 * preset invariants.
 */
class ProviderRegistry private constructor(
    val presets: List<ProviderPreset>,
) {
    private val presetsById = presets.associateBy { it.id }

    fun getPreset(id: String): ProviderPreset? = presetsById[id]

    fun getDefaultPreset(): ProviderPreset = presets.single { it.default }

    fun getReferencePreset(): ProviderPreset = presets.single { it.reference }

    fun getRequiredPreset(): ProviderPreset = presets.single { it.required }

    /** Presets usable in M2. Native-adapter presets remain coming later. */
    fun getEnabledPresets(): List<ProviderPreset> =
        presets.filter { isPresetEnabledInM2(it.id) }

    /** Describe every contract preset for UI code without hiding later presets. */
    fun getPresetAvailability(): List<PresetAvailability> = presets.map { preset ->
        val enabled = isPresetEnabledInM2(preset.id)
        PresetAvailability(
            preset = preset,
            enabled = enabled,
            selectable = enabled,
            status = if (enabled) {
                PresetAvailabilityStatus.AVAILABLE
            } else {
                PresetAvailabilityStatus.COMING_LATER
            },
        )
    }

    companion object {
        const val CONTRACT_RESOURCE = "contracts/providers.json"

        /** Load and validate the contract resource from the classpath. */
        fun load(resource: String = CONTRACT_RESOURCE): ProviderRegistry {
            val normalized = resource.removePrefix("/")
            val stream = ProviderRegistry::class.java.classLoader.getResourceAsStream(normalized)
                ?: throw ProviderRegistryException(
                    "Provider contract resource '$normalized' was not found on the classpath",
                )
            val json = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return fromJson(json)
        }

        /** Validate an explicit contract document. */
        fun fromJson(json: String): ProviderRegistry =
            ProviderRegistry(loadProviderPresets(json))

        /** Validate already-decoded presets, for tests and future importers. */
        fun fromPresets(presets: List<ProviderPreset>): ProviderRegistry =
            ProviderRegistry(validatePresets(presets))
    }
}

/** Decode and validate a provider contract document. */
fun loadProviderPresets(json: String): List<ProviderPreset> =
    validatePresets(decodeProviderPresets(json))

private val StrictJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = true
    coerceInputValues = false
    allowSpecialFloatingPointValues = false
}

private fun decodeProviderPresets(json: String): List<ProviderPreset> {
    return try {
        StrictJson.decodeFromString<List<ProviderPreset>>(json)
    } catch (error: SerializationException) {
        throw ProviderRegistryException(
            "Provider presets are invalid:\n${error.message}",
            listOf(error.message ?: "invalid provider preset document"),
        )
    } catch (error: IllegalArgumentException) {
        throw ProviderRegistryException(
            "Provider presets are invalid:\n${error.message}",
            listOf(error.message ?: "invalid provider preset document"),
        )
    }
}

private val presetIdPattern = Regex("^[a-z][a-z0-9-]*$")
private val httpUrlPattern = Regex("^https?://\\S+$")

private fun validatePresets(presets: List<ProviderPreset>): List<ProviderPreset> {
    val issues = mutableListOf<String>()

    presets.forEachIndexed { index, preset ->
        val path = "presets[$index]"

        if (!presetIdPattern.matches(preset.id)) {
            issues += "$path.id: must match ^[a-z][a-z0-9-]*$"
        }
        if (preset.label.isBlank()) {
            issues += "$path.label: must not be blank"
        }
        if (!httpUrlPattern.matches(preset.baseUrl)) {
            issues += "$path.baseUrl: must be an http(s) URL"
        }
        validateAuth(preset.auth, "$path.auth", issues)
        preset.keyValidation?.let { validateKeyValidation(it, "$path.keyValidation", issues) }
        validateModelDiscovery(preset.modelDiscovery, "$path.modelDiscovery", issues)
        if (preset.defaultModelId.isBlank()) {
            issues += "$path.defaultModelId: must not be blank"
        }
        preset.docsUrl?.let { docsUrl ->
            if (!httpUrlPattern.matches(docsUrl)) {
                issues += "$path.docsUrl: must be an http(s) URL"
            }
        }
    }

    val duplicateIds = presets
        .groupingBy { it.id }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .sorted()
    if (duplicateIds.isNotEmpty()) {
        issues += "id: duplicate provider preset id(s): ${duplicateIds.joinToString(", ")}"
    }

    validateSingleFlag(presets, "default", issues) { it.default }
    validateSingleFlag(presets, "reference", issues) { it.reference }
    validateSingleFlag(presets, "required", issues) { it.required }

    if (issues.isNotEmpty()) {
        throw ProviderRegistryException(
            "Provider presets are invalid:\n${issues.joinToString("\n")}",
            issues,
        )
    }

    return presets
}

private fun validateAuth(
    auth: ProviderAuth,
    path: String,
    issues: MutableList<String>,
) {
    when (auth.type) {
        ProviderAuthType.BEARER -> {
            if (auth.headerName == null) {
                issues += "$path.headerName: required for bearer auth"
            }
            if (auth.scheme == null) {
                issues += "$path.scheme: required for bearer auth"
            }
        }

        ProviderAuthType.X_API_KEY -> {
            if (auth.headerName == null) {
                issues += "$path.headerName: required for x-api-key auth"
            }
        }

        ProviderAuthType.QUERY_KEY -> {
            if (auth.queryParam == null) {
                issues += "$path.queryParam: required for query-key auth"
            }
        }

        ProviderAuthType.NONE -> Unit
    }

    if (auth.headerName != null && auth.headerName.isBlank()) {
        issues += "$path.headerName: must not be blank"
    }
    if (auth.scheme != null && auth.scheme.isBlank()) {
        issues += "$path.scheme: must not be blank"
    }
    if (auth.queryParam != null && auth.queryParam.isBlank()) {
        issues += "$path.queryParam: must not be blank"
    }
}

private fun validateKeyValidation(
    keyValidation: KeyValidation,
    path: String,
    issues: MutableList<String>,
) {
    if (keyValidation.path.isBlank()) {
        issues += "$path.path: must not be blank"
    }
}

private fun validateModelDiscovery(
    modelDiscovery: ModelDiscovery,
    path: String,
    issues: MutableList<String>,
) {
    if (modelDiscovery.path.isBlank()) {
        issues += "$path.path: must not be blank"
    }
    if (modelDiscovery.cacheTtlSeconds < 0) {
        issues += "$path.cacheTtlSeconds: must be >= 0"
    }
}

private fun validateSingleFlag(
    presets: List<ProviderPreset>,
    flag: String,
    issues: MutableList<String>,
    predicate: (ProviderPreset) -> Boolean,
) {
    val matches = presets.filter(predicate)
    when {
        matches.isEmpty() -> issues += "$flag: no provider preset is marked as $flag"
        matches.size > 1 -> issues +=
            "$flag: multiple provider presets are marked as $flag: " +
            matches.joinToString(", ") { it.id }
    }
}
