package dev.chatui.core.backup

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Backup object types that are valid in a v1 envelope. */
enum class BackupObjectType(val wireValue: String) {
    MANIFEST("manifest"),
    CONVERSATION("conversation"),
    ATTACHMENT("attachment"),
    ;

    companion object {
        fun fromWire(value: String): BackupObjectType? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Canonical envelope header. Field order is frozen by `DEC-M5-06`. */
data class EnvelopeHeader(
    val v: Int,
    val formatVersion: Int,
    val alg: String,
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val objectType: BackupObjectType,
    val objectKey: String,
)

/** Parsed envelope parts before decryption. */
data class EnvelopeParts(
    val header: EnvelopeHeader,
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

/** Decrypted object and its authenticated header. */
data class DecryptedObject(
    val header: EnvelopeHeader,
    val plaintext: ByteArray,
)

/**
 * `CHATUI1\n` backup envelope codec.
 *
 * Byte layout frozen in `docs/operations/M5/M5-decisions.md` (`DEC-M5-05`
 * through `DEC-M5-08`):
 *
 * ```
 * magic (8) | uint32be headerLength | UTF-8 header JSON | IV (12) | ciphertext || tag (16)
 * ```
 *
 * The codec only produces and consumes bytes. It never writes to storage, so
 * callers cannot accidentally persist plaintext through it.
 */
object BackupEnvelope {
    const val MAGIC: String = "CHATUI1\n"
    val MAGIC_BYTES: ByteArray = MAGIC.toByteArray(Charsets.US_ASCII)

    const val ENVELOPE_VERSION: Int = 1
    const val FORMAT_VERSION: Int = 1
    const val ALG: String = "AES-256-GCM"
    const val KDF: String = "pbkdf2-sha256"

    const val HEADER_LENGTH_BYTES: Int = 4
    const val MAX_HEADER_BYTES: Int = 65_536
    const val MAX_OBJECT_KEY_LENGTH: Int = 4_096
    const val MAX_OBJECT_BYTES: Int = 512 * 1024 * 1024
    const val MAX_ITERATIONS: Int = 10_000_000

    /**
     * Small encrypted marker stored alongside the backup set. It lets a client
     * reject a wrong passphrase before touching local data. Mirrors the web
     * choice: a normal envelope with the reserved `verification.enc` key.
     */
    const val VERIFICATION_MARKER: String = "chatui-backup-verification-v1"
    const val VERIFICATION_OBJECT_KEY: String = "verification.enc"
    val VERIFICATION_OBJECT_TYPE: BackupObjectType = BackupObjectType.MANIFEST

    private val HEADER_FIELDS = setOf(
        "v",
        "formatVersion",
        "alg",
        "kdf",
        "iterations",
        "salt",
        "objectType",
        "objectKey",
    )

    /**
     * Builds the AAD exactly as frozen in `DEC-M5-07`:
     * `uint32be(len(objectType)) || objectType || uint32be(len(objectKey)) || objectKey`.
     */
    fun buildAad(objectType: String, objectKey: String): ByteArray {
        val typeBytes = objectType.toByteArray(Charsets.UTF_8)
        val keyBytes = objectKey.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + typeBytes.size + 4 + keyBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(typeBytes.size)
            .put(typeBytes)
            .putInt(keyBytes.size)
            .put(keyBytes)
            .array()
    }

    /** Serializes the frozen envelope layout. */
    fun encodeEnvelope(header: EnvelopeHeader, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val normalized = validateHeader(header)
        if (iv.size != BackupCrypto.IV_BYTES) {
            throw malformed("Envelope IV must be ${BackupCrypto.IV_BYTES} bytes")
        }
        if (ciphertext.size < BackupCrypto.TAG_BYTES) {
            throw malformed("Envelope ciphertext must include a ${BackupCrypto.TAG_BYTES}-byte tag")
        }

        val headerBytes = canonicalHeaderJson(normalized).toByteArray(Charsets.UTF_8)
        if (headerBytes.isEmpty() || headerBytes.size > MAX_HEADER_BYTES) {
            throw malformed("Envelope header length is out of range")
        }

        val total = MAGIC_BYTES.size + HEADER_LENGTH_BYTES + headerBytes.size + iv.size + ciphertext.size
        if (total > MAX_OBJECT_BYTES) {
            throw malformed("Envelope exceeds the maximum object size")
        }

        return ByteBuffer.allocate(total)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC_BYTES)
            .putInt(headerBytes.size)
            .put(headerBytes)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    /** Parses and validates the frozen envelope layout. */
    fun decodeEnvelope(bytes: ByteArray): EnvelopeParts {
        if (bytes.size > MAX_OBJECT_BYTES) {
            throw malformed("Envelope exceeds the maximum object size")
        }
        if (bytes.size < MAGIC_BYTES.size + HEADER_LENGTH_BYTES) {
            throw malformed("Envelope is too short")
        }
        if (!bytes.copyOfRange(0, MAGIC_BYTES.size).contentEquals(MAGIC_BYTES)) {
            throw malformed("Envelope magic does not match")
        }

        val headerLength = ByteBuffer.wrap(bytes, MAGIC_BYTES.size, HEADER_LENGTH_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .int
        if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES) {
            throw malformed("Envelope header length is out of range")
        }

        val headerStart = MAGIC_BYTES.size + HEADER_LENGTH_BYTES
        val headerEnd = headerStart + headerLength
        val ivEnd = headerEnd + BackupCrypto.IV_BYTES
        val minimumEnd = ivEnd + BackupCrypto.TAG_BYTES
        if (bytes.size < minimumEnd) {
            throw malformed("Envelope is shorter than its declared header, IV, and tag")
        }

        val headerJson = String(bytes, headerStart, headerLength, Charsets.UTF_8)
        val header = parseHeader(headerJson)

        return EnvelopeParts(
            header = header,
            iv = bytes.copyOfRange(headerEnd, ivEnd),
            ciphertext = bytes.copyOfRange(ivEnd, bytes.size),
        )
    }

    /**
     * Encrypts an object and returns the full envelope.
     *
     * @param compress defaults to `true` for conversations and `false` for
     *   manifests and attachments.
     * @param iv test-only deterministic IV. Production callers must omit it.
     * @param iterations test-only PBKDF2 override. Production callers must omit
     *   it; the default is 600,000.
     */
    fun encryptObject(
        passphrase: String,
        salt: ByteArray,
        objectType: BackupObjectType,
        objectKey: String,
        plaintext: ByteArray,
        compress: Boolean = objectType == BackupObjectType.CONVERSATION,
        iv: ByteArray? = null,
        iterations: Int = BackupCrypto.PBKDF2_ITERATIONS,
    ): ByteArray {
        if (salt.size != BackupCrypto.SALT_BYTES) {
            throw invalidHeader("Salt must be ${BackupCrypto.SALT_BYTES} bytes")
        }
        if (iterations <= 0 || iterations > MAX_ITERATIONS) {
            throw invalidHeader("PBKDF2 iterations is out of range")
        }

        val header = validateHeader(
            EnvelopeHeader(
                v = ENVELOPE_VERSION,
                formatVersion = FORMAT_VERSION,
                alg = ALG,
                kdf = KDF,
                iterations = iterations,
                salt = Base64.getEncoder().encodeToString(salt),
                objectType = objectType,
                objectKey = objectKey,
            ),
        )

        val key = BackupCrypto.deriveKey(passphrase, salt, iterations)
        val payload = if (compress) BackupCrypto.gzip(plaintext) else plaintext
        val actualIv = iv ?: BackupCrypto.randomBytes(BackupCrypto.IV_BYTES)
        if (actualIv.size != BackupCrypto.IV_BYTES) {
            throw malformed("Envelope IV must be ${BackupCrypto.IV_BYTES} bytes")
        }

        val aad = buildAad(header.objectType.wireValue, header.objectKey)
        val ciphertext = BackupCrypto.encrypt(key, actualIv, aad, payload)
        return encodeEnvelope(header, actualIv, ciphertext)
    }

    /** Decrypts an envelope and gunzips conversation payloads. */
    fun decryptObject(passphrase: String, bytes: ByteArray): DecryptedObject {
        val parts = decodeEnvelope(bytes)
        val salt = try {
            Base64.getDecoder().decode(parts.header.salt)
        } catch (exception: IllegalArgumentException) {
            throw invalidHeader("Envelope header salt is not valid base64")
        }
        val key = BackupCrypto.deriveKey(passphrase, salt, parts.header.iterations)
        val aad = buildAad(parts.header.objectType.wireValue, parts.header.objectKey)
        val decrypted = BackupCrypto.decrypt(key, parts.iv, aad, parts.ciphertext)
        val plaintext = if (parts.header.objectType == BackupObjectType.CONVERSATION) {
            BackupCrypto.gunzip(decrypted)
        } else {
            decrypted
        }
        return DecryptedObject(parts.header, plaintext)
    }

    /** Creates the encrypted passphrase verification token. */
    fun createVerificationToken(
        passphrase: String,
        salt: ByteArray,
        iterations: Int = BackupCrypto.PBKDF2_ITERATIONS,
        iv: ByteArray? = null,
    ): ByteArray {
        return encryptObject(
            passphrase = passphrase,
            salt = salt,
            objectType = VERIFICATION_OBJECT_TYPE,
            objectKey = VERIFICATION_OBJECT_KEY,
            plaintext = VERIFICATION_MARKER.toByteArray(Charsets.UTF_8),
            compress = false,
            iv = iv,
            iterations = iterations,
        )
    }

    /** Returns `true` only when the token decrypts to the verification marker. */
    fun verifyPassphrase(passphrase: String, token: ByteArray): Boolean {
        return try {
            val decrypted = decryptObject(passphrase, token)
            decrypted.header.objectType == VERIFICATION_OBJECT_TYPE &&
                decrypted.header.objectKey == VERIFICATION_OBJECT_KEY &&
                decrypted.plaintext.contentEquals(VERIFICATION_MARKER.toByteArray(Charsets.UTF_8))
        } catch (_: BackupEnvelopeException) {
            false
        }
    }

    private fun validateHeader(header: EnvelopeHeader): EnvelopeHeader {
        return parseHeader(canonicalHeaderJson(header))
    }

    private fun canonicalHeaderJson(header: EnvelopeHeader): String {
        return buildJsonObject {
            put("v", header.v)
            put("formatVersion", header.formatVersion)
            put("alg", header.alg)
            put("kdf", header.kdf)
            put("iterations", header.iterations)
            put("salt", header.salt)
            put("objectType", header.objectType.wireValue)
            put("objectKey", header.objectKey)
        }.toString()
    }

    private fun parseHeader(json: String): EnvelopeHeader {
        val element = try {
            Json.parseToJsonElement(json)
        } catch (exception: Exception) {
            throw invalidHeader("Envelope header is not valid JSON", exception)
        }
        val obj = element as? JsonObject
            ?: throw invalidHeader("Envelope header must be a JSON object")

        obj.keys.firstOrNull { it !in HEADER_FIELDS }?.let { field ->
            throw invalidHeader("Unknown envelope header field: $field")
        }

        val v = obj.intField("v")
            ?: throw invalidHeader("Envelope header v must be an integer")
        if (v != ENVELOPE_VERSION) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.UNSUPPORTED_VERSION,
                "Unsupported envelope version: $v",
            )
        }

        val formatVersion = obj.intField("formatVersion")
            ?: throw invalidHeader("Envelope header formatVersion must be an integer")
        if (formatVersion != FORMAT_VERSION) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.UNSUPPORTED_VERSION,
                "Unsupported backup format version: $formatVersion",
            )
        }

        val alg = obj.stringField("alg")
            ?: throw invalidHeader("Envelope header alg must be a string")
        if (alg != ALG) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.UNSUPPORTED_ALG,
                "Unsupported envelope algorithm: $alg",
            )
        }

        val kdf = obj.stringField("kdf")
            ?: throw invalidHeader("Envelope header kdf must be a string")
        if (kdf != KDF) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.UNSUPPORTED_KDF,
                "Unsupported envelope KDF: $kdf",
            )
        }

        val iterations = obj.intField("iterations")
            ?: throw invalidHeader("Envelope header iterations must be an integer")
        if (iterations <= 0 || iterations > MAX_ITERATIONS) {
            throw invalidHeader("Envelope header iterations is out of range")
        }

        val salt = obj.stringField("salt")
            ?: throw invalidHeader("Envelope header salt must be a string")
        val saltBytes = try {
            Base64.getDecoder().decode(salt)
        } catch (exception: IllegalArgumentException) {
            throw invalidHeader("Envelope header salt is not valid base64", exception)
        }
        if (saltBytes.size != BackupCrypto.SALT_BYTES) {
            throw invalidHeader("Envelope header salt must be ${BackupCrypto.SALT_BYTES} bytes")
        }

        val objectTypeValue = obj.stringField("objectType")
            ?: throw invalidHeader("Envelope header objectType must be a string")
        val objectType = BackupObjectType.fromWire(objectTypeValue)
            ?: throw invalidHeader("Envelope header objectType is not a v1 backup object type")

        val objectKey = obj.stringField("objectKey")
            ?: throw invalidHeader("Envelope header objectKey must be a string")
        if (objectKey.isEmpty() || objectKey.length > MAX_OBJECT_KEY_LENGTH) {
            throw invalidHeader("Envelope header objectKey is invalid")
        }
        for (character in objectKey) {
            val code = character.code
            if (code < 0x20 || code > 0x7e) {
                throw invalidHeader("Envelope header objectKey must be printable ASCII")
            }
        }

        return EnvelopeHeader(
            v = v,
            formatVersion = formatVersion,
            alg = alg,
            kdf = kdf,
            iterations = iterations,
            salt = Base64.getEncoder().encodeToString(saltBytes),
            objectType = objectType,
            objectKey = objectKey,
        )
    }

    private fun JsonObject.intField(name: String): Int? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.intOrNull
    }

    private fun JsonObject.stringField(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content
    }

    private fun malformed(message: String): BackupEnvelopeException {
        return BackupEnvelopeException(BackupEnvelopeErrorCode.MALFORMED_ENVELOPE, message)
    }

    private fun invalidHeader(
        message: String,
        cause: Throwable? = null,
    ): BackupEnvelopeException {
        return BackupEnvelopeException(BackupEnvelopeErrorCode.INVALID_HEADER, message, cause)
    }
}
