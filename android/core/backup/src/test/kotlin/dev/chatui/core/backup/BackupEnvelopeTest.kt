package dev.chatui.core.backup

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class BackupEnvelopeTest {
    private val vectors: JsonObject by lazy {
        Json.parseToJsonElement(readContract("fixtures/backup/vectors.json")).jsonObject
    }

    private val envelopeVector: JsonObject by lazy {
        vectors.getValue("envelopeVectors").jsonArray.first().jsonObject
    }

    @Test
    fun `shared vector reproduces the frozen envelope bytes`() {
        val actual = BackupEnvelope.encryptObject(
            passphrase = envelopeVector.string("passphrase"),
            salt = envelopeVector.bytes("saltBase64"),
            objectType = BackupObjectType.CONVERSATION,
            objectKey = envelopeVector.string("objectKey"),
            plaintext = envelopeVector.bytes("plaintextBase64"),
            compress = false,
            iv = envelopeVector.bytes("ivBase64"),
            iterations = envelopeVector.int("iterations"),
        )

        assertThat(actual).isEqualTo(envelopeVector.bytes("envelopeBase64"))
    }

    @Test
    fun `shared vector decodes and decrypts`() {
        val envelope = envelopeVector.bytes("envelopeBase64")
        val parts = BackupEnvelope.decodeEnvelope(envelope)

        assertThat(parts.header.v).isEqualTo(1)
        assertThat(parts.header.formatVersion).isEqualTo(1)
        assertThat(parts.header.alg).isEqualTo("AES-256-GCM")
        assertThat(parts.header.kdf).isEqualTo("pbkdf2-sha256")
        assertThat(parts.header.iterations).isEqualTo(600_000)
        assertThat(parts.header.salt).isEqualTo(envelopeVector.string("saltBase64"))
        assertThat(parts.header.objectType).isEqualTo(BackupObjectType.CONVERSATION)
        assertThat(parts.header.objectKey).isEqualTo(envelopeVector.string("objectKey"))
        assertThat(parts.iv).isEqualTo(envelopeVector.bytes("ivBase64"))
        assertThat(parts.ciphertext).isEqualTo(
            envelopeVector.bytes("ciphertextBase64") + envelopeVector.bytes("tagBase64"),
        )

        val decrypted = BackupEnvelope.decryptObject(
            envelopeVector.string("passphrase"),
            envelope,
        )
        assertThat(decrypted.plaintext).isEqualTo(envelopeVector.bytes("uncompressedBase64"))

        val key = BackupCrypto.deriveKey(
            envelopeVector.string("passphrase"),
            envelopeVector.bytes("saltBase64"),
            parts.header.iterations,
        )
        val rawPlaintext = BackupCrypto.decrypt(
            key,
            parts.iv,
            BackupEnvelope.buildAad(parts.header.objectType.wireValue, parts.header.objectKey),
            parts.ciphertext,
        )
        assertThat(rawPlaintext).isEqualTo(envelopeVector.bytes("plaintextBase64"))
    }

    @Test
    fun `header contains every required field`() {
        val header = BackupEnvelope.decodeEnvelope(envelopeVector.bytes("envelopeBase64")).header

        assertThat(header.v).isEqualTo(1)
        assertThat(header.formatVersion).isEqualTo(1)
        assertThat(header.alg).isEqualTo("AES-256-GCM")
        assertThat(header.kdf).isEqualTo("pbkdf2-sha256")
        assertThat(header.iterations).isEqualTo(600_000)
        assertThat(header.salt).isNotEmpty()
        assertThat(header.objectType).isEqualTo(BackupObjectType.CONVERSATION)
        assertThat(header.objectKey).isNotEmpty()
    }

    @Test
    fun `aad matches the shared vectors`() {
        for (element in vectors.getValue("aadVectors").jsonArray) {
            val vector = element.jsonObject
            val actual = BackupEnvelope.buildAad(
                vector.string("objectType"),
                vector.string("objectKey"),
            )
            assertThat(actual).isEqualTo(vector.bytes("aadBase64"))
        }
    }

    @Test
    fun `round trips manifest conversation and attachment`() {
        val passphrase = "synthetic test passphrase"
        val salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES)
        val cases = listOf(
            BackupObjectType.MANIFEST to "{\"format\":\"chatui.backup.manifest\"}".toByteArray(),
            BackupObjectType.CONVERSATION to "{\"id\":\"synthetic\",\"messages\":[]}".toByteArray(),
            BackupObjectType.ATTACHMENT to ByteArray(256) { (it % 251).toByte() },
        )

        for ((objectType, plaintext) in cases) {
            val envelope = BackupEnvelope.encryptObject(
                passphrase = passphrase,
                salt = salt,
                objectType = objectType,
                objectKey = "synthetic/${objectType.wireValue}.enc",
                plaintext = plaintext,
                iterations = TEST_ITERATIONS,
            )
            val decrypted = BackupEnvelope.decryptObject(passphrase, envelope)

            assertThat(decrypted.header.objectType).isEqualTo(objectType)
            assertThat(decrypted.plaintext).isEqualTo(plaintext)
        }
    }

    @Test
    fun `fresh iv is used for every write`() {
        val salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES)
        val first = BackupEnvelope.encryptObject(
            passphrase = "synthetic test passphrase",
            salt = salt,
            objectType = BackupObjectType.MANIFEST,
            objectKey = "manifest.enc",
            plaintext = "{}".toByteArray(),
            iterations = TEST_ITERATIONS,
        )
        val second = BackupEnvelope.encryptObject(
            passphrase = "synthetic test passphrase",
            salt = salt,
            objectType = BackupObjectType.MANIFEST,
            objectKey = "manifest.enc",
            plaintext = "{}".toByteArray(),
            iterations = TEST_ITERATIONS,
        )

        val firstIv = BackupEnvelope.decodeEnvelope(first).iv
        val secondIv = BackupEnvelope.decodeEnvelope(second).iv

        assertThat(firstIv.size).isEqualTo(BackupCrypto.IV_BYTES)
        assertThat(secondIv.size).isEqualTo(BackupCrypto.IV_BYTES)
        assertThat(firstIv).isNotEqualTo(secondIv)
    }

    @Test
    fun `wrong passphrase fails safely`() {
        val envelope = encryptSynthetic(BackupObjectType.MANIFEST, "{}".toByteArray())

        assertFailsWithCode(BackupEnvelopeErrorCode.AUTHENTICATION_FAILED) {
            BackupEnvelope.decryptObject("wrong synthetic passphrase", envelope)
        }
    }

    @Test
    fun `tampered magic fails`() {
        val envelope = encryptSynthetic(BackupObjectType.MANIFEST, "{}".toByteArray())
        envelope[0] = 'X'.code.toByte()

        assertFailsWithCode(BackupEnvelopeErrorCode.MALFORMED_ENVELOPE) {
            BackupEnvelope.decodeEnvelope(envelope)
        }
    }

    @Test
    fun `tampered header length fails`() {
        val envelope = encryptSynthetic(BackupObjectType.MANIFEST, "{}".toByteArray())
        for (index in 8 until 12) {
            envelope[index] = 0xff.toByte()
        }

        assertFailsWithCode(BackupEnvelopeErrorCode.MALFORMED_ENVELOPE) {
            BackupEnvelope.decodeEnvelope(envelope)
        }
    }

    @Test
    fun `tampered header json fails`() {
        val envelope = encryptSynthetic(BackupObjectType.MANIFEST, "{}".toByteArray())
        envelope[12] = '['.code.toByte()

        assertFailsWithCode(BackupEnvelopeErrorCode.INVALID_HEADER) {
            BackupEnvelope.decodeEnvelope(envelope)
        }
    }

    @Test
    fun `tampered iv fails`() {
        val envelope = encryptSynthetic(BackupObjectType.MANIFEST, "{}".toByteArray())
        val headerLength = ByteBuffer.wrap(envelope, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        val ivStart = 12 + headerLength
        envelope[ivStart] = (envelope[ivStart].toInt() xor 0x01).toByte()

        assertFailsWithCode(BackupEnvelopeErrorCode.AUTHENTICATION_FAILED) {
            BackupEnvelope.decryptObject(SYNTHETIC_PASSPHRASE, envelope)
        }
    }

    @Test
    fun `tampered ciphertext fails`() {
        val envelope = encryptSynthetic(BackupObjectType.MANIFEST, "{}".toByteArray())
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 0x01).toByte()

        assertFailsWithCode(BackupEnvelopeErrorCode.AUTHENTICATION_FAILED) {
            BackupEnvelope.decryptObject(SYNTHETIC_PASSPHRASE, envelope)
        }
    }

    @Test
    fun `tampered aad fails`() {
        val envelope = encryptSynthetic(BackupObjectType.MANIFEST, "{}".toByteArray())
        val parts = BackupEnvelope.decodeEnvelope(envelope)
        val tampered = BackupEnvelope.encodeEnvelope(
            parts.header.copy(objectKey = "other/manifest.enc"),
            parts.iv,
            parts.ciphertext,
        )

        assertFailsWithCode(BackupEnvelopeErrorCode.AUTHENTICATION_FAILED) {
            BackupEnvelope.decryptObject(SYNTHETIC_PASSPHRASE, tampered)
        }
    }

    @Test
    fun `unknown envelope version fails`() {
        val headerJson = envelopeVector.string("headerJson").replace("\"v\":1", "\"v\":2")
        val envelope = rawEnvelope(
            headerJson,
            ByteArray(BackupCrypto.IV_BYTES),
            ByteArray(BackupCrypto.TAG_BYTES),
        )

        assertFailsWithCode(BackupEnvelopeErrorCode.UNSUPPORTED_VERSION) {
            BackupEnvelope.decodeEnvelope(envelope)
        }
    }

    @Test
    fun `unknown format version fails`() {
        val headerJson = envelopeVector.string("headerJson")
            .replace("\"formatVersion\":1", "\"formatVersion\":2")
        val envelope = rawEnvelope(
            headerJson,
            ByteArray(BackupCrypto.IV_BYTES),
            ByteArray(BackupCrypto.TAG_BYTES),
        )

        assertFailsWithCode(BackupEnvelopeErrorCode.UNSUPPORTED_VERSION) {
            BackupEnvelope.decodeEnvelope(envelope)
        }
    }

    @Test
    fun `conversation payloads are gzipped and attachments are not`() {
        val salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES)
        val conversation = "{\"messages\":[${"\"synthetic\",".repeat(64)}]}".toByteArray()
        val attachment = ByteArray(512) { (it % 251).toByte() }

        val conversationEnvelope = BackupEnvelope.encryptObject(
            passphrase = SYNTHETIC_PASSPHRASE,
            salt = salt,
            objectType = BackupObjectType.CONVERSATION,
            objectKey = "conversations/synthetic.enc",
            plaintext = conversation,
            iterations = TEST_ITERATIONS,
        )
        val attachmentEnvelope = BackupEnvelope.encryptObject(
            passphrase = SYNTHETIC_PASSPHRASE,
            salt = salt,
            objectType = BackupObjectType.ATTACHMENT,
            objectKey = "attachments/synthetic.enc",
            plaintext = attachment,
            iterations = TEST_ITERATIONS,
        )

        val conversationRaw = rawPlaintext(conversationEnvelope)
        val attachmentRaw = rawPlaintext(attachmentEnvelope)

        assertThat(conversationRaw[0]).isEqualTo(0x1f.toByte())
        assertThat(conversationRaw[1]).isEqualTo(0x8b.toByte())
        assertThat(BackupCrypto.gunzip(conversationRaw)).isEqualTo(conversation)

        assertThat(attachmentRaw).isEqualTo(attachment)
        assertThat(attachmentRaw[0]).isNotEqualTo(0x1f.toByte())

        assertThat(
            BackupEnvelope.decryptObject(SYNTHETIC_PASSPHRASE, conversationEnvelope).plaintext,
        ).isEqualTo(conversation)
        assertThat(
            BackupEnvelope.decryptObject(SYNTHETIC_PASSPHRASE, attachmentEnvelope).plaintext,
        ).isEqualTo(attachment)
    }

    @Test
    fun `failure vectors produce the documented codes`() {
        val baseEnvelope = envelopeVector.bytes("envelopeBase64")
        val passphrase = envelopeVector.string("passphrase")

        for (element in vectors.getValue("failureVectors").jsonArray) {
            val vector = element.jsonObject
            val expected = BackupEnvelopeErrorCode.entries.first {
                it.wireValue == vector.string("expected")
            }

            val result = when {
                vector.containsKey("wrongPassphrase") -> runCatching {
                    BackupEnvelope.decryptObject(vector.string("wrongPassphrase"), baseEnvelope)
                }

                vector.containsKey("tamperedEnvelopeBase64") -> runCatching {
                    BackupEnvelope.decryptObject(
                        passphrase,
                        vector.bytes("tamperedEnvelopeBase64"),
                    )
                }

                vector.containsKey("aadObjectKey") -> {
                    val parts = BackupEnvelope.decodeEnvelope(baseEnvelope)
                    val tampered = BackupEnvelope.encodeEnvelope(
                        parts.header.copy(objectKey = vector.string("aadObjectKey")),
                        parts.iv,
                        parts.ciphertext,
                    )
                    runCatching { BackupEnvelope.decryptObject(passphrase, tampered) }
                }

                else -> error("Unhandled failure vector: ${vector.string("name")}")
            }

            val exception = result.exceptionOrNull()
            assertThat(exception).isInstanceOf(BackupEnvelopeException::class.java)
            assertThat((exception as BackupEnvelopeException).code).isEqualTo(expected)
        }
    }

    @Test
    fun `verification token accepts the correct passphrase and rejects others`() {
        val salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES)
        val token = BackupEnvelope.createVerificationToken(
            passphrase = SYNTHETIC_PASSPHRASE,
            salt = salt,
            iterations = TEST_ITERATIONS,
        )

        assertThat(BackupEnvelope.verifyPassphrase(SYNTHETIC_PASSPHRASE, token)).isTrue()
        assertThat(BackupEnvelope.verifyPassphrase("wrong synthetic passphrase", token)).isFalse()

        token[token.lastIndex] = (token.last().toInt() xor 0x01).toByte()
        assertThat(BackupEnvelope.verifyPassphrase(SYNTHETIC_PASSPHRASE, token)).isFalse()
    }

    @Test
    fun `pbkdf2 parameters are frozen`() {
        assertThat(BackupCrypto.PBKDF2_ITERATIONS).isEqualTo(600_000)
        assertThat(BackupCrypto.SALT_BYTES).isEqualTo(16)
        assertThat(BackupCrypto.KEY_BYTES).isEqualTo(32)
        assertThat(BackupCrypto.IV_BYTES).isEqualTo(12)
        assertThat(BackupCrypto.TAG_BYTES).isEqualTo(16)

        val key = BackupCrypto.deriveKey(
            SYNTHETIC_PASSPHRASE,
            ByteArray(BackupCrypto.SALT_BYTES),
            TEST_ITERATIONS,
        )
        assertThat(key.size).isEqualTo(BackupCrypto.KEY_BYTES)
    }

    private fun encryptSynthetic(objectType: BackupObjectType, plaintext: ByteArray): ByteArray {
        return BackupEnvelope.encryptObject(
            passphrase = SYNTHETIC_PASSPHRASE,
            salt = BackupCrypto.randomBytes(BackupCrypto.SALT_BYTES),
            objectType = objectType,
            objectKey = "synthetic/${objectType.wireValue}.enc",
            plaintext = plaintext,
            iterations = TEST_ITERATIONS,
        )
    }

    private fun rawPlaintext(envelope: ByteArray): ByteArray {
        val parts = BackupEnvelope.decodeEnvelope(envelope)
        val key = BackupCrypto.deriveKey(
            SYNTHETIC_PASSPHRASE,
            Base64.getDecoder().decode(parts.header.salt),
            parts.header.iterations,
        )
        return BackupCrypto.decrypt(
            key,
            parts.iv,
            BackupEnvelope.buildAad(parts.header.objectType.wireValue, parts.header.objectKey),
            parts.ciphertext,
        )
    }

    private fun rawEnvelope(
        headerJson: String,
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val headerBytes = headerJson.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(
            BackupEnvelope.MAGIC_BYTES.size + 4 + headerBytes.size + iv.size + ciphertext.size,
        )
            .order(ByteOrder.BIG_ENDIAN)
            .put(BackupEnvelope.MAGIC_BYTES)
            .putInt(headerBytes.size)
            .put(headerBytes)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun assertFailsWithCode(
        expected: BackupEnvelopeErrorCode,
        block: () -> Unit,
    ) {
        val exception = runCatching(block).exceptionOrNull()
        assertThat(exception).isInstanceOf(BackupEnvelopeException::class.java)
        assertThat((exception as BackupEnvelopeException).code).isEqualTo(expected)
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int

    private fun JsonObject.bytes(name: String): ByteArray =
        Base64.getDecoder().decode(string(name))

    private companion object {
        const val SYNTHETIC_PASSPHRASE = "synthetic test passphrase"
        const val TEST_ITERATIONS = 1_000

        fun readContract(relativePath: String): String {
            var directory: File? = File(System.getProperty("user.dir") ?: ".")
            while (directory != null) {
                val candidate = File(directory, "contracts/$relativePath")
                if (candidate.isFile) return candidate.readText()
                directory = directory.parentFile
            }
            error("Could not find contracts/$relativePath from ${System.getProperty("user.dir")}")
        }
    }
}
