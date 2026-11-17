import { useDraftStore } from '../../app/stores'
import { Icon, type IconName } from '../../ui/Icon'

const starters: Array<{ icon: IconName; title: string; description: string; prompt: string }> = [
  {
    icon: 'pen',
    title: 'Find the right words',
    description: 'Turn a rough thought into something clear.',
    prompt: 'Help me put an idea into words. Ask me what I want to say and who it is for.',
  },
  {
    icon: 'sparkles',
    title: 'Explore an idea',
    description: 'A new perspective is a good place to start.',
    prompt: 'Help me brainstorm an idea. Start by asking what I am working on.',
  },
  {
    icon: 'code',
    title: 'Work through a problem',
    description: 'Break something complex into simple steps.',
    prompt: 'Help me work through a problem step by step. Ask me what I am trying to solve.',
  },
]

export function ChatWelcome({
  conversationId,
  modelLabel,
  canCompose,
}: {
  conversationId: string
  modelLabel: string
  canCompose: boolean
}) {
  function chooseStarter(prompt: string) {
    const { drafts, setDraft } = useDraftStore.getState()
    const draft = drafts[conversationId] ?? ''
    setDraft(conversationId, draft.trim() === '' ? prompt : `${draft}\n\n${prompt}`)
    document.getElementById('composer-input')?.focus()
  }

  return (
    <div className="chat-screen__empty">
      <div className="chat-welcome">
        <span className="chat-welcome__mark">
          <Icon name="sparkles" size={28} />
        </span>
        <p className="chat-welcome__eyebrow">A little space for your next big idea</p>
        <h3>Start the conversation</h3>
        <p className="chat-welcome__description">
          Ask a question, untangle a thought, or make something new with {modelLabel}.
        </p>
      </div>
      {canCompose && (
        <div className="chat-starters" aria-label="Conversation starters">
          {starters.map((starter) => (
            <button
              key={starter.title}
              type="button"
              className="chat-starter"
              onClick={() => chooseStarter(starter.prompt)}
            >
              <Icon name={starter.icon} size={20} />
              <strong>{starter.title}</strong>
              <span>{starter.description}</span>
              <Icon name="arrow-right" size={16} className="chat-starter__arrow" />
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
