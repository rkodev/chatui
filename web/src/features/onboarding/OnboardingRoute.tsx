import { useNavigate } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import {
  NoConversationState,
  useConversationHistoryService,
} from '../conversations'
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

type HomeState = 'checking' | 'onboarding' | 'error'

function HomeRouteContent() {
  const service = useOnboardingService()
  const history = useConversationHistoryService()
  const navigate = useNavigate()
  const openNewChat = useOpenNewChat()
  const [state, setState] = useState<HomeState>('checking')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    const run = async () => {
      try {
        const complete = await service.isOnboardingComplete()

        if (cancelled) {
          return
        }

        if (!complete) {
          setState('onboarding')
          return
        }

        const lastActive = await history.getLastActiveConversation()

        if (cancelled) {
          return
        }

        if (lastActive) {
          navigate({
            to: '/chat/$conversationId',
            params: { conversationId: lastActive.id },
            replace: true,
          })
          return
        }

        const created = await history.createConversation()

        if (cancelled) {
          return
        }

        if (!created.ok) {
          setError(created.message)
          setState('error')
          return
        }

        await history.setLastActiveConversation(created.conversation.id)

        if (cancelled) {
          return
        }

        navigate({
          to: '/chat/$conversationId',
          params: { conversationId: created.conversation.id },
          replace: true,
        })
      } catch (cause) {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : String(cause))
          setState('error')
        }
      }
    }

    void run()

    return () => {
      cancelled = true
    }
  }, [history, navigate, service])

  if (state === 'onboarding') {
    return <OnboardingScreen service={service} onConnected={openNewChat} />
  }

  if (state === 'error') {
    return <NoConversationState message={error ?? undefined} />
  }

  return <p role="status">Opening your last chat…</p>
}

/**
 * `/` route component.
 *
 * First run shows onboarding. Once onboarding is complete the route restores
 * the last-active conversation, or creates a safe new chat when there is no
 * valid last-active pointer. If no provider/model is configured, it shows the
 * no-conversation fallback with a link to provider setup.
 */
export function HomeRoute() {
  return (
    <OnboardingServiceProvider>
      <HomeRouteContent />
    </OnboardingServiceProvider>
  )
}
