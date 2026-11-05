package dev.chatui.shell

import android.net.Uri

/** Provider detail route nested under the provider management destination. */
object ProviderDestination {
    const val ARG_PROVIDER_ID = "providerId"
    const val ROUTE = "providers/{$ARG_PROVIDER_ID}"

    fun createRoute(providerId: String): String = "providers/${Uri.encode(providerId)}"
}
