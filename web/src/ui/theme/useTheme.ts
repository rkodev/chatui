import { useContext } from 'react'
import { ThemeContext, type ThemeContextValue } from './theme-context'

/**
 * Read and update the current appearance modes. Throws outside a provider so a
 * missing provider is never silently replaced with defaults.
 */
export function useTheme(): ThemeContextValue {
  const value = useContext(ThemeContext)

  if (value === null) {
    throw new Error('useTheme must be used inside a ThemeProvider')
  }

  return value
}
