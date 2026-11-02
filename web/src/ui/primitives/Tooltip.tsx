import * as TooltipPrimitive from '@radix-ui/react-tooltip'
import { forwardRef, type ComponentPropsWithoutRef, type ComponentRef } from 'react'
import './primitives.css'

export const TooltipProvider = TooltipPrimitive.Provider
export const Tooltip = TooltipPrimitive.Root
export const TooltipTrigger = TooltipPrimitive.Trigger

export interface TooltipContentProps extends ComponentPropsWithoutRef<
  typeof TooltipPrimitive.Content
> {
  sideOffset?: number
}

/**
 * Tooltip surface. Radix associates the content with the trigger through
 * `aria-describedby` while open, so the tooltip text is available to assistive
 * technology without replacing the trigger's accessible name.
 */
export const TooltipContent = forwardRef<
  ComponentRef<typeof TooltipPrimitive.Content>,
  TooltipContentProps
>(function TooltipContent({ className, sideOffset = 6, ...props }, ref) {
  return (
    <TooltipPrimitive.Portal>
      <TooltipPrimitive.Content
        ref={ref}
        sideOffset={sideOffset}
        className={['ui-tooltip__content', className].filter(Boolean).join(' ')}
        {...props}
      />
    </TooltipPrimitive.Portal>
  )
})
