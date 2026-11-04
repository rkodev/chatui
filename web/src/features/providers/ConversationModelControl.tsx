import { useCallback, useEffect, useState } from 'react'
import type { Conversation, Model, Provider } from '../../domain'
import { ModelChip } from './ModelChip'
import { ModelPickerDialog } from './ModelPickerDialog'
import { modelAvailability } from './modelPicker'
import { useProviderManagement } from './providerManagementContext'
import type { ManualModelInput } from './types'
import './providers.css'

export interface ConversationModelControlProps {
  conversationId: string
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/**
 * Per-conversation model override.
 *
 * Selecting a model updates the conversation record immediately. The next send
 * reads the conversation record, so the new model applies without rewriting
 * past messages or their provider/model snapshots.
 */
export function ConversationModelControl({ conversationId }: ConversationModelControlProps) {
  const service = useProviderManagement()
  const [conversation, setConversation] = useState<Conversation | null | undefined>(undefined)
  const [models, setModels] = useState<Model[]>([])
  const [providers, setProviders] = useState<Provider[]>([])
  const [open, setOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    const value = await service.getConversation(conversationId)

    if (!value) {
      setConversation(null)
      return
    }

    const [modelList, providerList] = await Promise.all([
      service.listModels(value.providerId),
      service.listProviders(),
    ])

    setConversation(value)
    setModels(modelList)
    setProviders(providerList)
  }, [conversationId, service])

  useEffect(() => {
    let cancelled = false

    service
      .getConversation(conversationId)
      .then(async (value) => {
        if (!value) {
          if (!cancelled) {
            setConversation(null)
          }
          return
        }

        const [modelList, providerList] = await Promise.all([
          service.listModels(value.providerId),
          service.listProviders(),
        ])

        if (!cancelled) {
          setConversation(value)
          setModels(modelList)
          setProviders(providerList)
        }
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(errorMessage(cause))
        }
      })

    return () => {
      cancelled = true
    }
  }, [conversationId, service])

  async function run(action: () => Promise<void>) {
    setError(null)

    try {
      await action()
      await load()
    } catch (cause) {
      setError(errorMessage(cause))
    }
  }

  async function handleSelect(model: Model) {
    if (!conversation) {
      return
    }

    await run(async () => {
      const updated = await service.setConversationModel(conversation.id, model.id)
      setConversation(updated)
    })
  }

  async function handlePin(model: Model, pinned: boolean) {
    await run(async () => {
      await service.setModelPinned(model.id, pinned)
    })
  }

  async function handleHide(model: Model, hidden: boolean) {
    await run(async () => {
      await service.setModelHidden(model.id, hidden)
    })
  }

  async function handleAddManual(input: ManualModelInput) {
    if (!conversation) {
      return
    }

    await run(async () => {
      await service.addManualModel(conversation.providerId, input)
    })
  }

  async function handleRefresh() {
    if (!conversation) {
      return
    }

    await run(async () => {
      await service.refreshModels(conversation.providerId)
    })
  }

  if (conversation === undefined) {
    if (error) {
      return (
        <p className="model-picker__error" role="alert">
          {error}
        </p>
      )
    }

    return <p role="status">Loading model…</p>
  }

  if (conversation === null) {
    return <p role="alert">Conversation not found.</p>
  }

  const selectedModel = models.find((model) => model.id === conversation.modelId) ?? null
  const provider = providers.find((candidate) => candidate.id === conversation.providerId) ?? null
  const availability = modelAvailability(selectedModel, models)
  const label = selectedModel?.label ?? conversation.modelLabel ?? 'Choose model'

  return (
    <div className="conversation-model-control">
      <ModelChip
        label={label}
        provider={provider}
        warning={availability.message}
        onClick={() => setOpen(true)}
      />
      {availability.message && (
        <p className="model-picker__warning" role="status">
          {availability.message}
        </p>
      )}
      {error && (
        <p className="model-picker__error" role="alert">
          {error}
        </p>
      )}
      <ModelPickerDialog
        open={open}
        onOpenChange={setOpen}
        models={models}
        providers={providers}
        selectedModelId={conversation.modelId}
        onSelect={handleSelect}
        onPin={handlePin}
        onHide={handleHide}
        onAddManual={handleAddManual}
        onRefresh={handleRefresh}
        title="Choose a model for this conversation"
      />
    </div>
  )
}
