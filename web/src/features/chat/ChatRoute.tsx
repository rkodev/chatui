import { useParams } from '@tanstack/react-router'
import { ChatScreen } from './ChatScreen'
import { ChatServiceProvider } from './ChatServiceProvider'
import { Composer } from './Composer'
import { useChatSession } from './useChatSession'

function ChatRouteContent() {
  const params = useParams({ strict: false }) as { conversationId?: string }
  const conversationId = params.conversationId ?? ''
  const session = useChatSession(conversationId)

  return <ChatScreen session={session} composer={<Composer session={session} />} />
}

/** `/chat/$conversationId` route component. */
export function ChatRoute() {
  return (
    <ChatServiceProvider>
      <ChatRouteContent />
    </ChatServiceProvider>
  )
}
