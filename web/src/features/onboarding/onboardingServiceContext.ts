import { createContext, useContext } from 'react'
import type { OnboardingServiceLike } from './types'

export const OnboardingServiceContext = createContext<OnboardingServiceLike | null>(null)

export function useOnboardingService(): OnboardingServiceLike {
  const service = useContext(OnboardingServiceContext)

  if (service === null) {
    throw new Error('useOnboardingService must be used inside OnboardingServiceProvider')
  }

  return service
}
