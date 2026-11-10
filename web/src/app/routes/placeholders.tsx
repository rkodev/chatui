interface RoutePlaceholderProps {
  title: string
  description: string
}

function RoutePlaceholder({ title, description }: RoutePlaceholderProps) {
  return (
    <section className="route-placeholder">
      <h2>{title}</h2>
      <p>{description}</p>
    </section>
  )
}

export function NoConversationPlaceholder() {
  return (
    <RoutePlaceholder
      title="No conversation selected"
      description="Conversation routing and the chat view arrive in M1."
    />
  )
}

export function ChatPlaceholder() {
  return (
    <RoutePlaceholder
      title="Chat placeholder"
      description="The chat view for this conversation arrives in M1."
    />
  )
}

export function NotFoundPlaceholder() {
  return (
    <section className="route-placeholder" role="alert">
      <h2>Page not found</h2>
      <p>The requested route does not exist.</p>
    </section>
  )
}
