import { useEffect, useMemo, useState } from 'react'
import type { ChatFontSize, Density, Theme } from '../../domain/settings'
import { ThemeContext, type ThemeContextValue, type ThemeProviderProps } from './theme-context'
import '../../styles/tokens.css'
import '../../styles/themes.css'
import '../../styles/layout.css'
import '../../styles/typography.css'

/**
 * Applies the appearance contract to the document element:
 *
 *   data-theme="light" | "dark" | "system"
 *   data-density="comfortable" | "compact"
 *   data-chat-font-size="sm" | "md" | "lg"
 *
 * CSS owns the actual values. Persistence is intentionally omitted in M0; a
 * later settings screen can pass the stored values as initial props.
 */
export function ThemeProvider({
  children,
  initialTheme = 'system',
  initialDensity = 'comfortable',
  initialChatFontSize = 'md',
}: ThemeProviderProps) {
  const [theme, setTheme] = useState<Theme>(initialTheme)
  const [density, setDensity] = useState<Density>(initialDensity)
  const [chatFontSize, setChatFontSize] = useState<ChatFontSize>(initialChatFontSize)

  useEffect(() => {
    if (typeof document === 'undefined') {
      return
    }

    const root = document.documentElement
    root.dataset.theme = theme
    root.dataset.density = density
    root.dataset.chatFontSize = chatFontSize
  }, [theme, density, chatFontSize])

  const value = useMemo<ThemeContextValue>(
    () => ({
      theme,
      density,
      chatFontSize,
      setTheme,
      setDensity,
      setChatFontSize,
    }),
    [theme, density, chatFontSize],
  )

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}
