import { createContext, type ReactNode } from 'react'
import type { ChatFontSize, Density, Theme } from '../../domain/settings'

export interface ThemeProviderProps {
  children: ReactNode
  initialTheme?: Theme
  initialDensity?: Density
  initialChatFontSize?: ChatFontSize
}

export interface ThemeContextValue {
  theme: Theme
  density: Density
  chatFontSize: ChatFontSize
  setTheme: (theme: Theme) => void
  setDensity: (density: Density) => void
  setChatFontSize: (chatFontSize: ChatFontSize) => void
}

export const ThemeContext = createContext<ThemeContextValue | null>(null)
