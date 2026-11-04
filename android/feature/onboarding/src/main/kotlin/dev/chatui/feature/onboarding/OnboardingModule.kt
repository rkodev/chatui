package dev.chatui.feature.onboarding

import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the onboarding orchestration service into the singleton graph. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OnboardingModule {
    @Binds
    @Singleton
    abstract fun bindOnboardingService(
        impl: DefaultOnboardingService,
    ): OnboardingService
}

/**
 * Entry point used by the shell to obtain the onboarding service without
 * depending on Hilt ViewModel navigation helpers.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface OnboardingEntryPoint {
    fun onboardingService(): OnboardingService
}
