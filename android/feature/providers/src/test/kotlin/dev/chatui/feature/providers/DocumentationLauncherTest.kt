package dev.chatui.feature.providers

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentationLauncherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `https documentation opens in a custom tab`() {
        var customTabUri: Uri? = null
        var browserUri: Uri? = null
        val launcher = AndroidDocumentationLauncher(
            customTabsAvailable = { true },
            launchCustomTab = { _, uri -> customTabUri = uri },
            launchBrowser = { _, uri -> browserUri = uri },
        )

        val result = launcher.open(context, "https://openrouter.ai/docs")

        assertThat(result).isEqualTo(DocumentationOpenResult.OpenedInCustomTab)
        assertThat(customTabUri.toString()).isEqualTo("https://openrouter.ai/docs")
        assertThat(browserUri).isNull()
    }

    @Test
    fun `http documentation is accepted`() {
        var customTabUri: Uri? = null
        val launcher = AndroidDocumentationLauncher(
            customTabsAvailable = { true },
            launchCustomTab = { _, uri -> customTabUri = uri },
            launchBrowser = { _, _ -> },
        )

        val result = launcher.open(context, "http://localhost:11434/docs")

        assertThat(result).isEqualTo(DocumentationOpenResult.OpenedInCustomTab)
        assertThat(customTabUri.toString()).isEqualTo("http://localhost:11434/docs")
    }

    @Test
    fun `non http schemes are refused without launching anything`() {
        val refused = listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "ftp://example.com/docs",
            "content://dev.chatui/docs",
            "chatui://chat/1",
        )

        refused.forEach { url ->
            var launched = false
            val launcher = AndroidDocumentationLauncher(
                customTabsAvailable = { true },
                launchCustomTab = { _, _ -> launched = true },
                launchBrowser = { _, _ -> launched = true },
            )

            val result = launcher.open(context, url)

            assertThat(result).isInstanceOf(DocumentationOpenResult.Refused::class.java)
            assertThat(launched).isFalse()
        }
    }

    @Test
    fun `urls without a host are refused`() {
        listOf("https://", "http://", "https:///docs", "").forEach { url ->
            assertThat(DocumentationUrls.parse(url)).isNull()
        }
    }

    @Test
    fun `falls back to the browser when custom tabs is unavailable`() {
        var browserUri: Uri? = null
        val launcher = AndroidDocumentationLauncher(
            customTabsAvailable = { false },
            launchCustomTab = { _, _ -> error("Custom Tab must not launch") },
            launchBrowser = { _, uri -> browserUri = uri },
        )

        val result = launcher.open(context, "https://openrouter.ai/docs")

        assertThat(result).isEqualTo(DocumentationOpenResult.OpenedInBrowser)
        assertThat(browserUri.toString()).isEqualTo("https://openrouter.ai/docs")
    }

    @Test
    fun `falls back to the browser when the custom tab launch fails`() {
        var browserUri: Uri? = null
        val launcher = AndroidDocumentationLauncher(
            customTabsAvailable = { true },
            launchCustomTab = { _, _ -> throw ActivityNotFoundException("no custom tabs") },
            launchBrowser = { _, uri -> browserUri = uri },
        )

        val result = launcher.open(context, "https://openrouter.ai/docs")

        assertThat(result).isEqualTo(DocumentationOpenResult.OpenedInBrowser)
        assertThat(browserUri.toString()).isEqualTo("https://openrouter.ai/docs")
    }

    @Test
    fun `returns a safe failure when no browser handles the link`() {
        val launcher = AndroidDocumentationLauncher(
            customTabsAvailable = { false },
            launchCustomTab = { _, _ -> },
            launchBrowser = { _, _ -> throw ActivityNotFoundException("no browser") },
        )

        val result = launcher.open(context, "https://openrouter.ai/docs")

        assertThat(result).isInstanceOf(DocumentationOpenResult.Failed::class.java)
        assertThat((result as DocumentationOpenResult.Failed).reason)
            .isEqualTo("No browser is available to open this link.")
    }

    @Test
    fun `documentation is never rendered in an in-app webview`() {
        val launcherSource = File(
            "src/main/kotlin/dev/chatui/feature/providers/DocumentationLauncher.kt",
        ).readText()
        val screenSource = File(
            "src/main/kotlin/dev/chatui/feature/providers/ProviderDetailScreen.kt",
        ).readText()

        assertThat(launcherSource).doesNotContain("android.webkit.WebView")
        assertThat(launcherSource).doesNotContain("WebView(")
        assertThat(screenSource).doesNotContain("android.webkit.WebView")
        assertThat(screenSource).doesNotContain("WebView(")
    }
}
