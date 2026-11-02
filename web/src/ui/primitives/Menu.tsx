import * as DropdownMenuPrimitive from '@radix-ui/react-dropdown-menu'
import { forwardRef, type ComponentPropsWithoutRef, type ComponentRef } from 'react'
import './primitives.css'

export const Menu = DropdownMenuPrimitive.Root
export const MenuTrigger = DropdownMenuPrimitive.Trigger
export const MenuGroup = DropdownMenuPrimitive.Group
export const MenuPortal = DropdownMenuPrimitive.Portal
export const MenuSeparator = DropdownMenuPrimitive.Separator

export const MenuLabel = forwardRef<
  ComponentRef<typeof DropdownMenuPrimitive.Label>,
  ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Label>
>(function MenuLabel({ className, ...props }, ref) {
  return (
    <DropdownMenuPrimitive.Label
      ref={ref}
      className={['ui-menu__label', className].filter(Boolean).join(' ')}
      {...props}
    />
  )
})

export interface MenuContentProps extends ComponentPropsWithoutRef<
  typeof DropdownMenuPrimitive.Content
> {
  sideOffset?: number
}

/**
 * Menu surface. Radix handles roving focus, typeahead, Escape dismissal, and
 * returning focus to the trigger.
 */
export const MenuContent = forwardRef<
  ComponentRef<typeof DropdownMenuPrimitive.Content>,
  MenuContentProps
>(function MenuContent({ className, sideOffset = 4, ...props }, ref) {
  return (
    <DropdownMenuPrimitive.Portal>
      <DropdownMenuPrimitive.Content
        ref={ref}
        sideOffset={sideOffset}
        className={['ui-menu__content', className].filter(Boolean).join(' ')}
        {...props}
      />
    </DropdownMenuPrimitive.Portal>
  )
})

export const MenuItem = forwardRef<
  ComponentRef<typeof DropdownMenuPrimitive.Item>,
  ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Item>
>(function MenuItem({ className, ...props }, ref) {
  return (
    <DropdownMenuPrimitive.Item
      ref={ref}
      className={['ui-menu__item', className].filter(Boolean).join(' ')}
      {...props}
    />
  )
})
