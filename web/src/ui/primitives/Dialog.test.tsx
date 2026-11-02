import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { Button } from './Button'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
  DialogTrigger,
} from './Dialog'

function renderDialog() {
  render(
    <Dialog>
      <DialogTrigger asChild>
        <Button>Open settings</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogTitle>Settings</DialogTitle>
        <DialogDescription>Adjust the appearance of the application.</DialogDescription>
        <DialogClose asChild>
          <Button>Done</Button>
        </DialogClose>
      </DialogContent>
    </Dialog>,
  )

  return screen.getByRole('button', { name: 'Open settings' })
}

describe('Dialog', () => {
  it('opens, moves focus inside, and closes with Escape', async () => {
    const user = userEvent.setup()
    const trigger = renderDialog()

    await user.click(trigger)

    const dialog = await screen.findByRole('dialog', { name: 'Settings' })
    expect(dialog).toBeInTheDocument()
    expect(dialog).toHaveAccessibleDescription('Adjust the appearance of the application.')
    expect(dialog.contains(document.activeElement)).toBe(true)

    await user.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(trigger).toHaveFocus()
  })

  it('closes from the close button and restores focus to the trigger', async () => {
    const user = userEvent.setup()
    const trigger = renderDialog()

    await user.click(trigger)
    await screen.findByRole('dialog', { name: 'Settings' })

    await user.click(screen.getByRole('button', { name: 'Done' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(trigger).toHaveFocus()
  })
})
