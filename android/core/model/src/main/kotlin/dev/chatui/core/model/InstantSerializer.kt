package dev.chatui.core.model

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes [Instant] as an ISO 8601 UTC string.
 *
 * `Instant.toString()` emits the canonical ISO 8601 representation with a `Z`
 * suffix and omits trailing zero fractional seconds, matching the contract
 * timestamps. `Instant.parse` accepts the same representation.
 */
object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.chatui.core.model.InstantIso8601", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
