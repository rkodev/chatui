package dev.chatui.shell

import android.net.Uri

/** Provider detail route nested under the provider management destination. */
object ProviderDestination {
    const val ARG_PROVIDER_ID = "providerId"
    const val ROUTE = "providers/{$ARG_PROVIDER_ID}"

    fun createRoute(providerId: String): String = "providers/${Uri.encode(providerId)}"
}

/** Catalog management stays scoped to the provider that opened it. */
object ProviderModelsDestination {
    const val ARG_PROVIDER_ID = ProviderDestination.ARG_PROVIDER_ID
    const val ROUTE = "providers/{$ARG_PROVIDER_ID}/models"

    fun createRoute(providerId: String): String =
        "${ProviderDestination.createRoute(providerId)}/models"
}
