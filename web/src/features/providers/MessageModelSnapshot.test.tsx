import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MessageModelSnapshot } from './MessageModelSnapshot'

describe('MessageModelSnapshot', () => {
  it('shows the provider and model snapshot stored on an assistant message', () => {
    render(
      <MessageModelSnapshot
        message={{
          providerId: 'provider-1',
          modelId: 'model-1',
          modelLabel: 'OpenAI: GPT-4o mini',
        }}
      />,
    )

    expect(screen.getByText('Model: OpenAI: GPT-4o mini')).toBeInTheDocument()
    expect(screen.getByText('Provider: provider-1')).toBeInTheDocument()
  })

  it('renders nothing when the message has no snapshot', () => {
    const { container } = render(
      <MessageModelSnapshot
        message={{ providerId: null, modelId: null, modelLabel: null }}
      />,
    )

    expect(container).toBeEmptyDOMElement()
  })
})
