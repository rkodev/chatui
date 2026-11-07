package dev.chatui.feature.providers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.compose.runtime.staticCompositionLocalOf

/** Outcome of a provider-documentation open attempt. */
sealed interface DocumentationOpenResult {
    /** The documentation opened in a Chrome Custom Tab. */
    data object OpenedInCustomTab : DocumentationOpenResult

    /** Custom Tabs was unavailable, so the documentation opened in a browser. */
    data object OpenedInBrowser : DocumentationOpenResult

    /** The URL was not an `http`/`https` documentation link. */
    data class Refused(val reason: String) : DocumentationOpenResult

    /** No activity could handle the link. */
    data class Failed(val reason: String) : DocumentationOpenResult
}

/**
 * Opens provider documentation outside the app.
 *
 * Implementations must never render documentation in an in-app WebView.
 */
fun interface DocumentationLauncher {
    fun open(context: Context, url: String): DocumentationOpenResult
}

/** Validation shared by the launcher and its tests. */
object DocumentationUrls {
    /**
     * Parses [url] and accepts only absolute `http`/`https` links with a host.
     *
     * Provider API base URLs are never passed here; only the contract
     * `docsUrl` value is.
     */
    fun parse(url: String): Uri? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") {
            return null
        }
        if (uri.host.isNullOrBlank()) {
            return null
        }
        return uri
    }
}

/**
 * Android implementation that prefers a Custom Tab and falls back to
 * `Intent.ACTION_VIEW`.
 *
 * The launch hooks are injectable so tests can assert the fallback order
 * without starting a real browser.
 */
class AndroidDocumentationLauncher(
    private val customTabsAvailable: (Context) -> Boolean = ::hasCustomTabsHandler,
    private val launchCustomTab: (Context, Uri) -> Unit = { context, uri ->
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    },
    private val launchBrowser: (Context, Uri) -> Unit = { context, uri ->
        context.startActivity(browserIntent(uri))
    },
) : DocumentationLauncher {
    override fun open(context: Context, url: String): DocumentationOpenResult {
        val uri = DocumentationUrls.parse(url)
            ?: return DocumentationOpenResult.Refused(
                "Only http and https documentation links can be opened.",
            )

        if (customTabsAvailable(context)) {
            try {
                launchCustomTab(context, uri)
                return DocumentationOpenResult.OpenedInCustomTab
            } catch (_: ActivityNotFoundException) {
                // Fall through to the external browser.
            }
        }

        return try {
            launchBrowser(context, uri)
            DocumentationOpenResult.OpenedInBrowser
        } catch (_: ActivityNotFoundException) {
            DocumentationOpenResult.Failed("No browser is available to open this link.")
        }
    }
}

/** Composition-local launcher so screens and tests can swap implementations. */
val LocalDocumentationLauncher = staticCompositionLocalOf<DocumentationLauncher> {
    AndroidDocumentationLauncher()
}

private fun hasCustomTabsHandler(context: Context): Boolean {
    val intent = Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION)
    return context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
}

private fun browserIntent(uri: Uri): Intent =
    Intent(Intent.ACTION_VIEW, uri)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
