import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ProviderRegistryProvider } from './ProviderRegistryProvider'
import { providerPresets } from './registry'
import { useProviderRegistry } from './useProviderRegistry'

function RegistryProbe() {
  const registry = useProviderRegistry()

  return (
    <div>
      <span data-testid="preset-count">{registry.presets.length}</span>
      <span data-testid="enabled-presets">
        {registry.enabledPresets.map((preset) => preset.id).join(',')}
      </span>
      <span data-testid="default-preset">{registry.getDefaultPreset().id}</span>
      <span data-testid="openrouter-label">{registry.getPreset('openrouter')?.label}</span>
      <span data-testid="custom-enabled">{String(registry.isEnabled('custom'))}</span>
    </div>
  )
}

describe('ProviderRegistryProvider', () => {
  it('supplies contracts presets and M2 enablement to feature code', () => {
    render(
      <ProviderRegistryProvider>
        <RegistryProbe />
      </ProviderRegistryProvider>,
    )

    expect(screen.getByTestId('preset-count')).toHaveTextContent(String(providerPresets.length))
    expect(screen.getByTestId('enabled-presets')).toHaveTextContent(
      'openrouter,openai,anthropic,google,groq,mistral,deepseek,xai,together,fireworks,ollama,lmstudio,llamacpp,custom',
    )
    expect(screen.getByTestId('default-preset')).toHaveTextContent('openrouter')
    expect(screen.getByTestId('openrouter-label')).toHaveTextContent('OpenRouter')
    expect(screen.getByTestId('custom-enabled')).toHaveTextContent('true')
  })
})
