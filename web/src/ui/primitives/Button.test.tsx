import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Button } from './Button'

describe('Button', () => {
  it('renders a native button with variant and size classes', () => {
    render(
      <Button variant="secondary" size="sm">
        Save
      </Button>,
    )

    const button = screen.getByRole('button', { name: 'Save' })
    expect(button).toHaveAttribute('type', 'button')
    expect(button).toHaveClass('ui-button', 'ui-button--secondary', 'ui-button--sm')
  })

  it('activates with Enter and Space from the keyboard', async () => {
    const user = userEvent.setup()
    const onClick = vi.fn()

    render(<Button onClick={onClick}>Continue</Button>)

    await user.tab()
    expect(screen.getByRole('button', { name: 'Continue' })).toHaveFocus()

    await user.keyboard('{Enter}')
    await user.keyboard(' ')

    expect(onClick).toHaveBeenCalledTimes(2)
  })

  it('does not fire click handlers while disabled', async () => {
    const user = userEvent.setup()
    const onClick = vi.fn()

    render(
      <Button disabled onClick={onClick}>
        Disabled
      </Button>,
    )

    const button = screen.getByRole('button', { name: 'Disabled' })
    expect(button).toBeDisabled()

    await user.click(button)
    expect(onClick).not.toHaveBeenCalled()
  })
})
