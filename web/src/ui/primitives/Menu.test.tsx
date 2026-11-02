import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Button } from './Button'
import { Menu, MenuContent, MenuItem, MenuLabel, MenuTrigger } from './Menu'

function renderMenu(onRename = vi.fn(), onDelete = vi.fn()) {
  render(
    <Menu>
      <MenuTrigger asChild>
        <Button>Actions</Button>
      </MenuTrigger>
      <MenuContent aria-label="Conversation actions">
        <MenuLabel>Conversation</MenuLabel>
        <MenuItem onSelect={onRename}>Rename</MenuItem>
        <MenuItem onSelect={onDelete}>Delete</MenuItem>
      </MenuContent>
    </Menu>,
  )

  return { trigger: screen.getByRole('button', { name: 'Actions' }), onRename, onDelete }
}

describe('Menu', () => {
  it('opens and selects an item with the pointer', async () => {
    const user = userEvent.setup()
    const { trigger, onRename } = renderMenu()

    await user.click(trigger)
    const menu = await screen.findByRole('menu')
    expect(menu).toHaveAttribute('aria-label', 'Conversation actions')

    await user.click(screen.getByRole('menuitem', { name: 'Rename' }))

    expect(onRename).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument())
  })

  it('opens from the keyboard and selects the first item', async () => {
    const user = userEvent.setup()
    const { trigger, onRename } = renderMenu()

    trigger.focus()
    await user.keyboard('{Enter}')
    await screen.findByRole('menu')

    expect(screen.getByRole('menuitem', { name: 'Rename' })).toHaveFocus()

    await user.keyboard('{Enter}')

    expect(onRename).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument())
  })

  it('closes with Escape and returns focus to the trigger', async () => {
    const user = userEvent.setup()
    const { trigger } = renderMenu()

    await user.click(trigger)
    await screen.findByRole('menu')

    await user.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument())
    expect(trigger).toHaveFocus()
  })
})
