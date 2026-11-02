import type { Provider } from '../../domain'
import './providers.css'

export interface ModelChipProps {
  label: string
  provider?: Pick<Provider, 'label'> | null
  warning?: string | null
  onClick?: () => void
}

function initialFor(label: string): string {
  const trimmed = label.trim()
  return trimmed === '' ? '?' : trimmed.slice(0, 1).toUpperCase()
}

/**
 * Header model chip. Shows the current model label, a provider initial, and a
 * warning marker when the selected model is unavailable.
 */
export function ModelChip({ label, provider, warning, onClick }: ModelChipProps) {
  const content = (
    <>
      <span className="model-chip__initial" aria-hidden="true">
        {initialFor(provider?.label ?? label)}
      </span>
      <span className="model-chip__label">{label}</span>
      {warning && (
        <span className="model-chip__warning" role="img" aria-label="Model warning">
          ⚠
        </span>
      )}
    </>
  )

  if (onClick) {
    return (
      <button type="button" className="model-chip" onClick={onClick}>
        {content}
      </button>
    )
  }

  return <span className="model-chip">{content}</span>
}
