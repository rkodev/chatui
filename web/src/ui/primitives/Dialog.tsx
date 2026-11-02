import * as DialogPrimitive from '@radix-ui/react-dialog'
import { forwardRef, type ComponentPropsWithoutRef, type ComponentRef, type ReactNode } from 'react'
import './primitives.css'

export const Dialog = DialogPrimitive.Root
export const DialogTrigger = DialogPrimitive.Trigger
export const DialogClose = DialogPrimitive.Close
export const DialogPortal = DialogPrimitive.Portal

export const DialogOverlay = forwardRef<
  ComponentRef<typeof DialogPrimitive.Overlay>,
  ComponentPropsWithoutRef<typeof DialogPrimitive.Overlay>
>(function DialogOverlay({ className, ...props }, ref) {
  return (
    <DialogPrimitive.Overlay
      ref={ref}
      className={['ui-dialog__overlay', className].filter(Boolean).join(' ')}
      {...props}
    />
  )
})

export interface DialogContentProps extends ComponentPropsWithoutRef<
  typeof DialogPrimitive.Content
> {
  children: ReactNode
  showCloseButton?: boolean
}

/**
 * Modal dialog content. Radix owns focus trapping, Escape handling, and focus
 * restoration; the close button is included by default so every dialog has a
 * visible, keyboard-reachable dismissal control.
 */
export const DialogContent = forwardRef<
  ComponentRef<typeof DialogPrimitive.Content>,
  DialogContentProps
>(function DialogContent({ className, children, showCloseButton = true, ...props }, ref) {
  return (
    <DialogPrimitive.Portal>
      <DialogOverlay />
      <DialogPrimitive.Content
        ref={ref}
        className={['ui-dialog__content', className].filter(Boolean).join(' ')}
        {...props}
      >
        {children}
        {showCloseButton ? (
          <DialogPrimitive.Close className="ui-dialog__close" aria-label="Close dialog">
            <span aria-hidden="true">×</span>
          </DialogPrimitive.Close>
        ) : null}
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  )
})

export const DialogTitle = forwardRef<
  ComponentRef<typeof DialogPrimitive.Title>,
  ComponentPropsWithoutRef<typeof DialogPrimitive.Title>
>(function DialogTitle({ className, ...props }, ref) {
  return (
    <DialogPrimitive.Title
      ref={ref}
      className={['ui-dialog__title', className].filter(Boolean).join(' ')}
      {...props}
    />
  )
})

export const DialogDescription = forwardRef<
  ComponentRef<typeof DialogPrimitive.Description>,
  ComponentPropsWithoutRef<typeof DialogPrimitive.Description>
>(function DialogDescription({ className, ...props }, ref) {
  return (
    <DialogPrimitive.Description
      ref={ref}
      className={['ui-dialog__description', className].filter(Boolean).join(' ')}
      {...props}
    />
  )
})
