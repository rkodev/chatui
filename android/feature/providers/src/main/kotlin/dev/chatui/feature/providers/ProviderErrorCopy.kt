package dev.chatui.feature.providers

import dev.chatui.core.network.OpenRouterRequestException
import dev.chatui.core.providers.ProviderServiceException

/** Map a management failure to plain-language copy. */
internal fun providerErrorMessage(error: Throwable): String = when (error) {
    is ProviderManagementException -> error.message ?: "Something went wrong."
    is ProviderServiceException -> error.message ?: "Something went wrong."
    is OpenRouterRequestException -> error.message
    else -> error.message ?: "Something went wrong."
}
