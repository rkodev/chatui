import { useNavigate } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { NoConversationPlaceholder } from '../../app/routes/placeholders'
import { OnboardingScreen } from './OnboardingScreen'
import { OnboardingServiceProvider } from './OnboardingServiceProvider'
import { useOnboardingService } from './onboardingServiceContext'

function useOpenNewChat() {
  const navigate = useNavigate()

  return (conversationId: string) => {
    navigate({
      to: '/chat/$conversationId',
      params: { conversationId },
    })
  }
}

function OnboardingRouteContent() {
  const service = useOnboardingService()
  const openNewChat = useOpenNewChat()

  return <OnboardingScreen service={service} onConnected={openNewChat} />
}

/** `/settings/providers/new` route component. */
export function OnboardingRoute() {
  return (
    <OnboardingServiceProvider>
      <OnboardingRouteContent />
    </OnboardingServiceProvider>
  )
}

function HomeRouteContent() {
  const service = useOnboardingService()
  const openNewChat = useOpenNewChat()
  const [complete, setComplete] = useState<boolean | null>(null)

  useEffect(() => {
    let cancelled = false

    service
      .isOnboardingComplete()
      .then((value) => {
        if (!cancelled) {
          setComplete(value)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setComplete(false)
        }
      })

    return () => {
      cancelled = true
    }
  }, [service])

  if (complete === null) {
    return <p role="status">Checking setup…</p>
  }

  if (complete) {
    return <NoConversationPlaceholder />
  }

  return <OnboardingScreen service={service} onConnected={openNewChat} />
}

/**
 * `/` route component.
 *
 * First run shows onboarding. Once onboarding is complete the empty state
 * falls back to the existing no-conversation placeholder until the chat
 * screen lands in M1-04.
 */
export function HomeRoute() {
  return (
    <OnboardingServiceProvider>
      <HomeRouteContent />
    </OnboardingServiceProvider>
  )
}
