import { useNavigate, useParams } from '@tanstack/react-router'
import { ProviderManagementProvider } from '../providers'
import { ConversationSettingsScreen } from './ConversationSettingsScreen'

function ConversationSettingsRouteContent() {
  const params = useParams({ strict: false }) as { conversationId?: string }
  const conversationId = params.conversationId ?? ''
  const navigate = useNavigate()

  return (
    <ConversationSettingsScreen
      conversationId={conversationId}
      onDeleted={() => {
        void navigate({ to: '/' })
      }}
    />
  )
}

/** `/chat/$conversationId/settings` route component. */
export function ConversationSettingsRoute() {
  return (
    <ProviderManagementProvider>
      <ConversationSettingsRouteContent />
    </ProviderManagementProvider>
  )
}
