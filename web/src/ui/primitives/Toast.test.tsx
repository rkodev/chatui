import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { useToastStore } from '../../app/stores'
import { Button } from './Button'
import { ToastProvider } from './Toast'
import { useToast } from './useToast'

function ToastTrigger() {
  const { addToast } = useToast()

  return <Button onClick={() => addToast({ message: 'Saved', tone: 'success' })}>Show toast</Button>
}

beforeEach(() => {
  useToastStore.setState({ toasts: [] })
})

describe('Toast', () => {
  it('adds a toast from the shell store and dismisses it', async () => {
    const user = userEvent.setup()

    render(
      <ToastProvider>
        <ToastTrigger />
      </ToastProvider>,
    )

    await user.click(screen.getByRole('button', { name: 'Show toast' }))

    const message = await screen.findByText('Saved')
    expect(message).toBeInTheDocument()
    expect(message.closest('[data-tone]')).toHaveAttribute('data-tone', 'success')

    await user.click(screen.getByRole('button', { name: 'Dismiss notification' }))

    await waitFor(() => expect(screen.queryByText('Saved')).not.toBeInTheDocument())
    expect(useToastStore.getState().toasts).toEqual([])
  })

  it('renders every queued toast', async () => {
    const user = userEvent.setup()

    render(
      <ToastProvider>
        <ToastTrigger />
      </ToastProvider>,
    )

    await user.click(screen.getByRole('button', { name: 'Show toast' }))
    await user.click(screen.getByRole('button', { name: 'Show toast' }))

    expect(await screen.findAllByText('Saved')).toHaveLength(2)
  })
})
