package dev.chatui.core.model

import java.security.SecureRandom
import java.util.Random

/**
 * RFC 9562 UUIDv7 generator.
 *
 * The first 48 bits are the Unix epoch timestamp in milliseconds, the next 4
 * bits are the version (`7`), and the remaining 74 bits are random. The
 * variant bits are set to `10` as required by RFC 9562. IDs therefore sort
 * lexicographically by creation time while remaining unique without a
 * platform UUIDv7 API (minSdk 26 does not provide one).
 *
 * [random] and [clock] are injectable so tests can make generation
 * deterministic.
 */
class UuidV7Generator(
    private val random: Random = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun generate(): String {
        val timestamp = clock() and TIMESTAMP_MASK
        val randA = random.nextInt(1 shl 12)
        val randB1 = random.nextInt(1 shl 30)
        val randB2 = random.nextInt()

        val bytes = ByteArray(16)
        bytes[0] = (timestamp ushr 40).toByte()
        bytes[1] = (timestamp ushr 32).toByte()
        bytes[2] = (timestamp ushr 24).toByte()
        bytes[3] = (timestamp ushr 16).toByte()
        bytes[4] = (timestamp ushr 8).toByte()
        bytes[5] = timestamp.toByte()

        // Version 7 in the high nibble, rand_a in the remaining 12 bits.
        bytes[6] = ((VERSION_7 shl 4) or (randA ushr 8)).toByte()
        bytes[7] = randA.toByte()

        // Variant 10 in the high two bits, then 62 bits of rand_b.
        bytes[8] = (VARIANT_10 or (randB1 ushr 24)).toByte()
        bytes[9] = (randB1 ushr 16).toByte()
        bytes[10] = (randB1 ushr 8).toByte()
        bytes[11] = randB1.toByte()
        bytes[12] = (randB2 ushr 24).toByte()
        bytes[13] = (randB2 ushr 16).toByte()
        bytes[14] = (randB2 ushr 8).toByte()
        bytes[15] = randB2.toByte()

        return format(bytes)
    }

    private fun format(bytes: ByteArray): String {
        val hex = CharArray(32)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            hex[index * 2] = HEX_DIGITS[value ushr 4]
            hex[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
        }
        return buildString(36) {
            append(hex, 0, 8)
            append('-')
            append(hex, 8, 4)
            append('-')
            append(hex, 12, 4)
            append('-')
            append(hex, 16, 4)
            append('-')
            append(hex, 20, 12)
        }
    }

    private companion object {
        const val TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL
        const val VERSION_7 = 0x7
        const val VARIANT_10 = 0x80
        const val HEX_DIGITS = "0123456789abcdef"
    }
}

/** Process-wide UUIDv7 generator used by [newId]. */
object UuidV7 {
    private val generator = UuidV7Generator()

    fun generate(): String = generator.generate()
}

private val UUID_V7_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE,
)

/**
 * Generate a time-ordered UUIDv7 entity ID.
 *
 * IDs are generated client-side; provider IDs are never used for local
 * identity.
 */
fun newId(): String = UuidV7.generate()

/** Return true when [value] is a canonical UUIDv7 string. */
fun isUuidV7(value: String): Boolean = UUID_V7_PATTERN.matches(value)

private val STORAGE_KEY_PATTERN = Regex("^[0-9a-f]{32}$")

/**
 * Generate a random 128-bit backup storage key as 32 lowercase hex characters.
 *
 * The key is independent of the conversation UUID so backup object names do
 * not leak conversation counts or creation times (spec 6.1 and 13.3).
 */
fun newStorageKey(random: Random = SecureRandom()): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    val hex = CharArray(32)
    for (index in bytes.indices) {
        val value = bytes[index].toInt() and 0xFF
        hex[index * 2] = HEX_DIGITS[value ushr 4]
        hex[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
    }
    return String(hex)
}

/** Return true when [value] is a 32-character lowercase hex storage key. */
fun isStorageKey(value: String): Boolean = STORAGE_KEY_PATTERN.matches(value)

private const val HEX_DIGITS = "0123456789abcdef"
