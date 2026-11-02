import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { Button } from './Button'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from './Tooltip'

function renderTooltip() {
  render(
    <TooltipProvider delayDuration={0}>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button variant="ghost">Help</Button>
        </TooltipTrigger>
        <TooltipContent>Opens the help centre</TooltipContent>
      </Tooltip>
    </TooltipProvider>,
  )

  return screen.getByRole('button', { name: 'Help' })
}

describe('Tooltip', () => {
  it('describes the trigger without replacing its accessible name', async () => {
    const user = userEvent.setup()
    const trigger = renderTooltip()

    await user.hover(trigger)

    const tooltip = await screen.findByRole('tooltip')
    expect(tooltip).toHaveTextContent('Opens the help centre')
    expect(trigger).toHaveAccessibleName('Help')
    expect(trigger).toHaveAttribute('aria-describedby', tooltip.id)
  })

  it('opens when the trigger receives keyboard focus', async () => {
    const user = userEvent.setup()
    renderTooltip()

    await user.tab()

    expect(await screen.findByRole('tooltip')).toHaveTextContent('Opens the help centre')
  })
})
