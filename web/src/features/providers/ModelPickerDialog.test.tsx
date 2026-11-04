import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ModelPickerDialog } from './ModelPickerDialog'
import { createTestModel, createTestProvider } from './testSupport'

const provider = createTestProvider()
const paid = createTestModel()
const free = createTestModel({
  id: 'free-model',
  remoteId: 'author/free:free',
  label: 'Free model',
  pricing: { inputPerMTok: 0, outputPerMTok: 0 },
  capabilities: ['text'],
  inputModalities: ['text'],
})
const pinned = createTestModel({
  id: 'pinned-model',
  remoteId: 'author/pinned',
  label: 'Pinned model',
  pinned: true,
})
const hidden = createTestModel({
  id: 'hidden-model',
  remoteId: 'author/hidden',
  label: 'Hidden model',
  hidden: true,
})

function renderPicker(overrides: Partial<Parameters<typeof ModelPickerDialog>[0]> = {}) {
  const props = {
    open: true,
    onOpenChange: vi.fn(),
    models: [paid, free, pinned, hidden],
    providers: [provider],
    selectedModelId: paid.id,
    onSelect: vi.fn(),
    onPin: vi.fn(),
    onHide: vi.fn(),
    onAddManual: vi.fn(),
    onRefresh: vi.fn(),
    ...overrides,
  }

  render(<ModelPickerDialog {...props} />)
  return props
}

describe('ModelPickerDialog', () => {
  it('shows search, pinned-first groups, chips, context, modalities, price, and free badge', async () => {
    renderPicker()

    expect(screen.getByRole('heading', { name: 'Choose a model' })).toBeInTheDocument()
    expect(screen.getByLabelText('Search models')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Pinned' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'OpenRouter' })).toBeInTheDocument()
    expect(screen.getAllByText('128K context').length).toBeGreaterThan(0)
    expect(screen.getAllByText('text, image in · text out').length).toBeGreaterThan(0)
    expect(
      screen.getAllByText('$0.15 / M input · $0.6 / M output').length,
    ).toBeGreaterThan(0)
    expect(screen.getAllByText('Free').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Vision').length).toBeGreaterThan(0)
    expect(screen.queryByText('Hidden model')).not.toBeInTheDocument()
  })

  it('filters models by search', async () => {
    const user = userEvent.setup()
    renderPicker()

    await user.type(screen.getByLabelText('Search models'), 'free')

    expect(screen.getByText('Free model')).toBeInTheDocument()
    expect(screen.queryByText('OpenAI: GPT-4o mini')).not.toBeInTheDocument()
  })

  it('selects a model and closes the dialog', async () => {
    const user = userEvent.setup()
    const props = renderPicker()

    await user.click(screen.getByRole('button', { name: /Free model/ }))

    await waitFor(() => expect(props.onSelect).toHaveBeenCalledWith(free))
    expect(props.onOpenChange).toHaveBeenCalledWith(false)
  })

  it('pins, hides, and refreshes models', async () => {
    const user = userEvent.setup()
    const props = renderPicker()

    await user.click(screen.getAllByRole('button', { name: 'Pin' })[0])
    await waitFor(() => expect(props.onPin).toHaveBeenCalled())

    await user.click(screen.getAllByRole('button', { name: 'Hide' })[0])
    await waitFor(() => expect(props.onHide).toHaveBeenCalled())

    await user.click(screen.getByRole('button', { name: 'Refresh models' }))
    await waitFor(() => expect(props.onRefresh).toHaveBeenCalled())
  })

  it('adds a manual model', async () => {
    const user = userEvent.setup()
    const props = renderPicker()

    await user.type(screen.getByLabelText('Model id'), 'author/manual')
    await user.type(screen.getByLabelText('Label (optional)'), 'Manual model')
    await user.click(screen.getByRole('button', { name: 'Add model' }))

    await waitFor(() =>
      expect(props.onAddManual).toHaveBeenCalledWith({
        remoteId: 'author/manual',
        label: 'Manual model',
      }),
    )
  })

  it('shows model details and an unavailable warning', async () => {
    const user = userEvent.setup()
    renderPicker({ selectedModelId: 'missing-model' })

    expect(screen.getByText(/no longer available/i)).toBeInTheDocument()

    await user.click(screen.getAllByText('Details')[0])

    expect(screen.getAllByText('openai/gpt-4o-mini').length).toBeGreaterThan(0)
    expect(screen.getAllByText('16,384 tokens').length).toBeGreaterThan(0)
    expect(screen.getAllByText('temperature, tools').length).toBeGreaterThan(0)
    expect(
      screen
        .getAllByRole('link', { name: 'OpenRouter model page' })
        .some((link) => link.getAttribute('href') === 'https://openrouter.ai/openai/gpt-4o-mini'),
    ).toBe(true)
  })

  it('shows hidden models in management mode', () => {
    renderPicker({ includeHidden: true })

    expect(screen.getByText('Hidden model')).toBeInTheDocument()
    expect(screen.getByText('Hidden')).toBeInTheDocument()
  })
})
