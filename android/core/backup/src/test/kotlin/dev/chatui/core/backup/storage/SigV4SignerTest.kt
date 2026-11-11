package dev.chatui.core.backup.storage

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class SigV4SignerTest {
    private val credentials = SigV4Credentials(
        accessKeyId = "AKIAIOSFODNN7EXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    )
    private val date: Instant = Instant.parse("2013-05-24T00:00:00Z")

    @Test
    fun `matches the AWS documentation GET object known-answer vector`() {
        val signed = SigV4Signer.sign(
            SigV4SignOptions(
                method = SigV4Method.GET,
                url = "https://examplebucket.s3.amazonaws.com/test.txt".toHttpUrl(),
                region = "us-east-1",
                credentials = credentials,
                headers = mapOf("range" to "bytes=0-9"),
                date = date,
            ),
        )

        assertThat(signed.headers["x-amz-date"]).isEqualTo("20130524T000000Z")
        assertThat(signed.headers["x-amz-content-sha256"]).isEqualTo(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )
        assertThat(signed.headers["authorization"]).isEqualTo(
            "AWS4-HMAC-SHA256 " +
                "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
        )
    }

    @Test
    fun `matches the AWS documentation PUT object known-answer vector`() {
        val signed = SigV4Signer.sign(
            SigV4SignOptions(
                method = SigV4Method.PUT,
                url = "https://examplebucket.s3.amazonaws.com/test%24file.text".toHttpUrl(),
                region = "us-east-1",
                credentials = credentials,
                headers = mapOf(
                    "date" to "Fri, 24 May 2013 00:00:00 GMT",
                    "x-amz-storage-class" to "REDUCED_REDUNDANCY",
                ),
                payload = "Welcome to Amazon S3.".toByteArray(Charsets.UTF_8),
                date = date,
            ),
        )

        assertThat(signed.headers["x-amz-content-sha256"]).isEqualTo(
            "44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072",
        )
        assertThat(signed.headers["authorization"]).isEqualTo(
            "AWS4-HMAC-SHA256 " +
                "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class, " +
                "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
        )
    }

    @Test
    fun `sorts query parameters and encodes RFC 3986 characters`() {
        val canonical = SigV4Signer.buildCanonicalRequest(
            method = SigV4Method.GET,
            url = "https://examplebucket.s3.amazonaws.com/?prefix=a%2Fb&list-type=2&max-keys=10"
                .toHttpUrl(),
            headers = mapOf("host" to "examplebucket.s3.amazonaws.com"),
            payloadHash = SigV4Signer.sha256Hex(""),
        )

        assertThat(canonical.canonicalRequest.split("\n")[2])
            .isEqualTo("list-type=2&max-keys=10&prefix=a%2Fb")
        assertThat(SigV4Signer.encodeRfc3986("a b!'()*"))
            .isEqualTo("a%20b%21%27%28%29%2A")
    }

    @Test
    fun `encodes the canonical URI path segment by segment`() {
        val canonical = SigV4Signer.buildCanonicalRequest(
            method = SigV4Method.PUT,
            url = "https://examplebucket.s3.amazonaws.com/test%24file.text".toHttpUrl(),
            headers = mapOf("host" to "examplebucket.s3.amazonaws.com"),
            payloadHash = SigV4Signer.sha256Hex(""),
        )

        assertThat(canonical.canonicalRequest.split("\n")[1])
            .isEqualTo("/test%24file.text")
    }

    @Test
    fun `adds a session token header when credentials include one`() {
        val signed = SigV4Signer.sign(
            SigV4SignOptions(
                method = SigV4Method.HEAD,
                url = "https://examplebucket.s3.amazonaws.com/".toHttpUrl(),
                region = "us-east-1",
                credentials = credentials.copy(sessionToken = "session-token"),
                date = date,
            ),
        )

        assertThat(signed.headers["x-amz-security-token"]).isEqualTo("session-token")
        assertThat(signed.headers["authorization"]).contains("x-amz-security-token")
    }

    @Test
    fun `formats dates and derives a stable signing key`() {
        assertThat(SigV4Signer.formatAmzDate(date)).isEqualTo("20130524T000000Z")
        val key = SigV4Signer.deriveSigningKey(
            secretAccessKey = credentials.secretAccessKey,
            dateStamp = "20130524",
            region = "us-east-1",
            service = "s3",
        )
        assertThat(key.size).isEqualTo(32)
    }
}
