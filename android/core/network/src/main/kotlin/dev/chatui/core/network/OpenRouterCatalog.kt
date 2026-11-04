package dev.chatui.core.network

import dev.chatui.core.model.InputModality
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.ModelPricing
import dev.chatui.core.model.OutputModality
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Raised when an OpenRouter catalog or key payload cannot be normalized. */
class OpenRouterCatalogException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A successful catalog snapshot, including when it was fetched. */
data class CatalogSnapshot(
    val fetchedAt: Long,
    val models: List<ModelInfo>,
)

/** Result of a cache-aware catalog load. */
data class CatalogResult(
    val models: List<ModelInfo>,
    val fetchedAt: Long?,
    val fromCache: Boolean,
    val stale: Boolean,
    val refreshError: Throwable?,
)

/** Injectable cache so tests and callers can choose their own storage. */
interface CatalogCache {
    suspend fun read(key: String): CatalogSnapshot?

    suspend fun write(key: String, snapshot: CatalogSnapshot)
}

const val OPENROUTER_CATALOG_TTL_MS: Long = 24L * 60L * 60L * 1000L

/** Cache key for the OpenRouter catalog. */
fun openRouterCatalogCacheKey(providerId: String = "openrouter"): String =
    "chatui.catalog.$providerId.v1"

/** True when a snapshot is still inside the 24-hour TTL. */
fun isCatalogFresh(
    snapshot: CatalogSnapshot,
    now: Long,
    ttlMs: Long = OPENROUTER_CATALOG_TTL_MS,
): Boolean = now - snapshot.fetchedAt < ttlMs

private fun cloneSnapshot(snapshot: CatalogSnapshot): CatalogSnapshot =
    snapshot.copy(models = snapshot.models.toList())

/** In-memory cache used by tests and as a fallback when storage is absent. */
class MemoryCatalogCache : CatalogCache {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, CatalogSnapshot>()

    override suspend fun read(key: String): CatalogSnapshot? = mutex.withLock {
        entries[key]?.let(::cloneSnapshot)
    }

    override suspend fun write(key: String, snapshot: CatalogSnapshot) {
        mutex.withLock {
            entries[key] = cloneSnapshot(snapshot)
        }
    }
}

private val defaultCatalogCacheInstance: CatalogCache by lazy { MemoryCatalogCache() }

/** Return the process-wide default catalog cache. */
fun getDefaultCatalogCache(): CatalogCache = defaultCatalogCacheInstance

private val INPUT_MODALITIES: List<InputModality> = listOf(
    InputModality.TEXT,
    InputModality.IMAGE,
    InputModality.FILE,
    InputModality.AUDIO,
    InputModality.VIDEO,
)

private val OUTPUT_MODALITIES: List<OutputModality> = listOf(
    OutputModality.TEXT,
    OutputModality.IMAGE,
    OutputModality.AUDIO,
)

private val CAPABILITY_ORDER: List<ModelCapability> = listOf(
    ModelCapability.TEXT,
    ModelCapability.VISION,
    ModelCapability.FILES,
    ModelCapability.TOOLS,
    ModelCapability.REASONING,
    ModelCapability.STREAMING,
)

private val KNOWN_MODEL_FIELDS: Set<String> = setOf(
    "id",
    "canonical_slug",
    "name",
    "created",
    "description",
    "context_length",
    "architecture",
    "pricing",
    "top_provider",
    "per_request_limits",
    "supported_parameters",
    "default_parameters",
)

private val SECRET_LIKE_FIELD = Regex("key|token|secret|auth|password|credential", RegexOption.IGNORE_CASE)

private val DECIMAL_PATTERN = Regex("^([+-]?)(\\d+)(?:\\.(\\d+))?$")
private val USD_AMOUNT_PATTERN = Regex("^[+-]?\\d+(?:\\.\\d+)?$")

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonElement?.finiteNumberOrNull(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    val value = if (primitive.isString) {
        primitive.content.toDoubleOrNull()
    } else {
        primitive.doubleOrNull
    }
    return value?.takeIf { it.isFinite() }
}

private fun JsonElement?.positiveIntOrNull(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) {
        return null
    }
    val value = primitive.doubleOrNull ?: return null
    if (!value.isFinite()) {
        return null
    }
    val integer = value.toLong()
    return if (integer > 0 && integer <= Int.MAX_VALUE) integer.toInt() else null
}

/**
 * Multiply a non-negative decimal string by one million without going through
 * binary floating point.
 *
 * The result is a canonical decimal string. Invalid input returns `null`.
 */
fun multiplyDecimalByMillion(value: String): String? {
    val match = DECIMAL_PATTERN.matchEntire(value.trim()) ?: return null

    val sign = if (match.groupValues[1] == "-") "-" else ""
    val integerPart = match.groupValues[2]
    val fractionPart = match.groupValues[3]
    val digits = integerPart + fractionPart
    val pointIndex = integerPart.length + 6

    val result = if (pointIndex >= digits.length) {
        digits + "0".repeat(pointIndex - digits.length)
    } else {
        digits.substring(0, pointIndex) + "." + digits.substring(pointIndex)
    }

    val rawInteger = result.substringBefore('.')
    val rawFraction = result.substringAfter('.', "")
    val normalizedInteger = rawInteger.replace(Regex("^0+(?=\\d)")) { "" }.ifEmpty { "0" }
    val normalizedFraction = rawFraction.trimEnd('0')
    val normalized = if (normalizedFraction.isNotEmpty()) {
        "$normalizedInteger.$normalizedFraction"
    } else {
        normalizedInteger
    }

    if (normalized == "0") {
        return "0"
    }

    return sign + normalized
}

/** Convert a USD-per-token decimal string to USD per million tokens. */
fun usdPerMillion(value: String?): Double? {
    if (value == null) {
        return null
    }
    val multiplied = multiplyDecimalByMillion(value) ?: return null
    return multiplied.toDoubleOrNull()
}

/** Convert a USD decimal string to a number, preserving the raw value. */
fun usdAmount(value: String?): Double? {
    if (value == null || value.trim().isEmpty()) {
        return null
    }
    if (!USD_AMOUNT_PATTERN.matches(value.trim())) {
        return null
    }
    return value.toDoubleOrNull()
}

private fun inputModality(value: String): InputModality? = when (value) {
    "text" -> InputModality.TEXT
    "image" -> InputModality.IMAGE
    "file" -> InputModality.FILE
    "audio" -> InputModality.AUDIO
    "video" -> InputModality.VIDEO
    else -> null
}

private fun outputModality(value: String): OutputModality? = when (value) {
    "text" -> OutputModality.TEXT
    "image" -> OutputModality.IMAGE
    "audio" -> OutputModality.AUDIO
    else -> null
}

private data class ParsedModality(
    val input: List<InputModality>,
    val output: List<OutputModality>,
)

private fun parseModality(value: String): ParsedModality {
    if (value.isEmpty()) {
        return ParsedModality(emptyList(), emptyList())
    }

    val parts = value.split("->", limit = 2)
    val rawInput = parts.getOrElse(0) { "" }
    val rawOutput = parts.getOrElse(1) { "" }

    val input = rawInput
        .split("+")
        .map { it.trim() }
        .mapNotNull(::inputModality)
        .distinct()
    val output = rawOutput
        .split("+")
        .map { it.trim() }
        .mapNotNull(::outputModality)
        .distinct()

    return ParsedModality(input, output)
}

private fun uniqueStrings(element: JsonElement?): List<String> {
    val array = element as? JsonArray ?: return emptyList()
    return array
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        .distinct()
}

private fun uniqueInputModalities(element: JsonElement?): List<InputModality> {
    val array = element as? JsonArray ?: return emptyList()
    return array
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        .mapNotNull(::inputModality)
        .distinct()
}

private fun uniqueOutputModalities(element: JsonElement?): List<OutputModality> {
    val array = element as? JsonArray ?: return emptyList()
    return array
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        .mapNotNull(::outputModality)
        .distinct()
}

private fun inferCapabilities(
    inputModalities: List<InputModality>,
    outputModalities: List<OutputModality>,
    supportedParameters: List<String>,
    modality: String,
): List<ModelCapability> {
    val capabilities = mutableSetOf<ModelCapability>()

    if (
        InputModality.TEXT in inputModalities ||
        OutputModality.TEXT in outputModalities ||
        modality.contains("text")
    ) {
        capabilities += ModelCapability.TEXT
    }

    if (InputModality.IMAGE in inputModalities || modality.contains("image")) {
        capabilities += ModelCapability.VISION
    }

    if (InputModality.FILE in inputModalities || modality.contains("file")) {
        capabilities += ModelCapability.FILES
    }

    if ("tools" in supportedParameters || "tool_choice" in supportedParameters) {
        capabilities += ModelCapability.TOOLS
    }

    if ("reasoning" in supportedParameters || "include_reasoning" in supportedParameters) {
        capabilities += ModelCapability.REASONING
    }

    if ("stream" in supportedParameters) {
        capabilities += ModelCapability.STREAMING
    }

    return CAPABILITY_ORDER.filter { it in capabilities }
}

private fun mapPricing(rawPricing: JsonObject?): ModelPricing? {
    if (rawPricing == null) {
        return null
    }

    val inputPerMTok = usdPerMillion(rawPricing.stringOrNull("prompt"))
    val outputPerMTok = usdPerMillion(rawPricing.stringOrNull("completion"))
    val perRequest = usdAmount(rawPricing.stringOrNull("request"))

    if (inputPerMTok == null && outputPerMTok == null && perRequest == null) {
        return null
    }

    return ModelPricing(
        inputPerMTok = inputPerMTok,
        outputPerMTok = outputPerMTok,
        perRequest = perRequest,
        currency = "USD",
    )
}

private fun isFreeModel(
    remoteId: String,
    rawPricing: JsonObject?,
    pricing: ModelPricing?,
): Boolean {
    if (remoteId.endsWith(":free")) {
        return true
    }

    if (rawPricing == null || pricing == null) {
        return false
    }

    val prompt = usdAmount(rawPricing.stringOrNull("prompt"))
    val completion = usdAmount(rawPricing.stringOrNull("completion"))

    if (prompt != 0.0 || completion != 0.0) {
        return false
    }

    val otherCharges = listOf("request", "image", "internal_reasoning")
        .map { usdAmount(rawPricing.stringOrNull(it)) }

    return otherCharges.all { charge -> charge == null || charge == 0.0 }
}

private fun copySmallUnknownFields(
    raw: JsonObject,
    metadata: MutableMap<String, JsonElement>,
) {
    for ((key, value) in raw) {
        if (key in KNOWN_MODEL_FIELDS || SECRET_LIKE_FIELD.containsMatchIn(key)) {
            continue
        }

        val serialized = value.toString()
        if (serialized.length > 512) {
            continue
        }

        metadata[key] = value
    }
}

private fun buildMetadata(raw: JsonObject): JsonObject? {
    val metadata = mutableMapOf<String, JsonElement>()

    val architecture = raw["architecture"].asObjectOrNull()
    if (architecture != null) {
        val mapped = mutableMapOf<String, JsonElement>()
        architecture.stringOrNull("modality")?.let { mapped["modality"] = JsonPrimitive(it) }
        architecture.stringOrNull("tokenizer")?.let { mapped["tokenizer"] = JsonPrimitive(it) }
        if (architecture.containsKey("instruct_type")) {
            mapped["instructType"] = architecture.getValue("instruct_type")
        }
        if (mapped.isNotEmpty()) {
            metadata["architecture"] = JsonObject(mapped)
        }
    }

    raw["pricing"].asObjectOrNull()?.let { metadata["pricing"] = it }
    raw["default_parameters"].asObjectOrNull()?.let { metadata["defaultParameters"] = it }

    val topProvider = raw["top_provider"].asObjectOrNull()
    if (topProvider != null) {
        val mapped = mutableMapOf<String, JsonElement>()
        topProvider.booleanOrNull("is_moderated")?.let { mapped["isModerated"] = JsonPrimitive(it) }
        if (topProvider.containsKey("context_length")) {
            mapped["contextLength"] = topProvider.getValue("context_length")
        }
        if (topProvider.containsKey("max_completion_tokens")) {
            mapped["maxCompletionTokens"] = topProvider.getValue("max_completion_tokens")
        }
        if (mapped.isNotEmpty()) {
            metadata["topProvider"] = JsonObject(mapped)
        }
    }

    if (raw.containsKey("per_request_limits")) {
        metadata["perRequestLimits"] = raw.getValue("per_request_limits")
    }

    raw.stringOrNull("description")?.takeIf { it.length <= 512 }?.let {
        metadata["description"] = JsonPrimitive(it)
    }

    (raw["created"] as? JsonPrimitive)
        ?.takeIf { !it.isString && it.doubleOrNull != null }
        ?.let { metadata["created"] = it }

    copySmallUnknownFields(raw, metadata)

    return if (metadata.isEmpty()) null else JsonObject(metadata)
}

/**
 * Map one raw OpenRouter model entry.
 *
 * Unknown fields are ignored unless they are small and non-secret, in which
 * case they are copied into `metadata`. Entries without a usable `id` are
 * skipped by [mapOpenRouterModels] rather than failing the whole catalog.
 */
fun mapOpenRouterModel(raw: JsonElement?): ModelInfo? {
    val obj = raw.asObjectOrNull() ?: return null

    val remoteId = obj.stringOrNull("id")?.trim().orEmpty()
    if (remoteId.isEmpty()) {
        return null
    }

    val architecture = obj["architecture"].asObjectOrNull()
    val topProvider = obj["top_provider"].asObjectOrNull()
    val modality = architecture?.stringOrNull("modality").orEmpty()
    val parsedModality = parseModality(modality)

    val declaredInputModalities = uniqueInputModalities(architecture?.get("input_modalities"))
    val declaredOutputModalities = uniqueOutputModalities(architecture?.get("output_modalities"))
    val inputModalities = declaredInputModalities.ifEmpty { parsedModality.input }
    val outputModalities = declaredOutputModalities.ifEmpty { parsedModality.output }

    val supportedParameters = uniqueStrings(obj["supported_parameters"])
    val contextWindow = obj["context_length"].positiveIntOrNull()
        ?: topProvider?.get("context_length").positiveIntOrNull()
    val maxOutputTokens = topProvider?.get("max_completion_tokens").positiveIntOrNull()
        ?: contextWindow
    val rawPricing = obj["pricing"].asObjectOrNull()
    val pricing = mapPricing(rawPricing)
    val label = obj.stringOrNull("name")?.takeIf { it.trim().isNotEmpty() } ?: remoteId

    return ModelInfo(
        remoteId = remoteId,
        label = label,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        capabilities = inferCapabilities(
            inputModalities,
            outputModalities,
            supportedParameters,
            modality,
        ),
        pricing = pricing,
        canonicalSlug = obj.stringOrNull("canonical_slug")?.takeIf { it.trim().isNotEmpty() },
        inputModalities = inputModalities,
        outputModalities = outputModalities,
        supportedParameters = supportedParameters,
        metadata = buildMetadata(obj),
        isFree = isFreeModel(remoteId, rawPricing, pricing),
    )
}

/**
 * Map an OpenRouter `/models` envelope to normalized entries.
 *
 * The envelope may be `{ data: [...] }`, `{ models: [...] }`, or a bare array.
 * Malformed entries are skipped; a malformed envelope throws.
 */
fun mapOpenRouterModels(payload: JsonElement?): List<ModelInfo> {
    val entries = when {
        payload is JsonArray -> payload
        payload is JsonObject && payload["data"] is JsonArray -> payload.getValue("data") as JsonArray
        payload is JsonObject && payload["models"] is JsonArray ->
            payload.getValue("models") as JsonArray
        else -> throw OpenRouterCatalogException(
            "OpenRouter /models response must contain a data array",
        )
    }

    return entries.mapNotNull(::mapOpenRouterModel)
}

/** Map an OpenRouter `/key` envelope to normalized key metadata. */
fun mapOpenRouterKey(payload: JsonElement?): ProviderKeyInfo {
    val obj = payload.asObjectOrNull()
        ?: throw OpenRouterCatalogException("OpenRouter /key response must contain a data object")
    val data = obj["data"].asObjectOrNull()
        ?: throw OpenRouterCatalogException("OpenRouter /key response must contain a data object")

    return ProviderKeyInfo(
        label = data.stringOrNull("label"),
        usageUsd = data["usage"].finiteNumberOrNull(),
        limitUsd = data["limit"].finiteNumberOrNull(),
        limitRemainingUsd = data["limit_remaining"].finiteNumberOrNull(),
        isFreeTier = data.booleanOrNull("is_free_tier"),
        rateLimit = data["rate_limit"].asObjectOrNull(),
    )
}
