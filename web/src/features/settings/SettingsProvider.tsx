import { useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import type { Settings } from '../../domain'
import { openDatabase } from '../../storage/db'
import { SettingsRepository } from '../../storage/SettingsRepository'
import { ThemeContext } from '../../ui/theme/theme-context'
import { SettingsService, type SettingsClient } from './settingsService'
import { SettingsContext, type SettingsContextValue } from './settingsContext'

const STALE_SETTINGS_MESSAGE =
  'ChatUI could not load settings from this device. Defaults are shown until storage is available.'

export interface SettingsProviderProps {
  children: ReactNode
  /** Test or story override. When omitted, the provider opens IndexedDB. */
  service?: SettingsClient
}

async function createDefaultSettingsClient(): Promise<SettingsClient> {
  const db = await openDatabase()
  return new SettingsService(new SettingsRepository(db))
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

interface SettingsProviderInnerProps extends SettingsProviderProps {
  service: SettingsClient
}

function SettingsProviderInner({ children, service }: SettingsProviderInnerProps) {
  const [settings, setSettings] = useState<Settings | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const theme = useContext(ThemeContext)

  useEffect(() => {
    let cancelled = false

    service
      .get()
      .then((value) => {
        if (!cancelled) {
          setSettings(value)
          setError(null)
          setLoading(false)
        }
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(errorMessage(cause))
          setLoading(false)
        }
      })

    return () => {
      cancelled = true
    }
  }, [service])

  const setTheme = theme?.setTheme
  const setDensity = theme?.setDensity
  const setChatFontSize = theme?.setChatFontSize

  useEffect(() => {
    if (settings === null) {
      return
    }

    setTheme?.(settings.theme)
    setDensity?.(settings.density)
    setChatFontSize?.(settings.chatFontSize)
  }, [setChatFontSize, setDensity, setTheme, settings])

  const update = useCallback(
    async (patch: Partial<Settings>): Promise<Settings> => {
      setError(null)

      try {
        const next = await service.update(patch)
        setSettings(next)
        return next
      } catch (cause) {
        setError(errorMessage(cause))
        throw cause
      }
    },
    [service],
  )

  const reload = useCallback(async (): Promise<void> => {
    setError(null)

    try {
      setSettings(await service.get())
    } catch (cause) {
      setError(errorMessage(cause))
    }
  }, [service])

  const value = useMemo<SettingsContextValue>(
    () => ({ settings, error, loading, service, update, reload }),
    [error, loading, reload, service, settings, update],
  )

  return <SettingsContext.Provider value={value}>{children}</SettingsContext.Provider>
}

function SettingsProviderWithClient({ children, service }: SettingsProviderInnerProps) {
  return <SettingsProviderInner service={service}>{children}</SettingsProviderInner>
}

/**
 * Load typed settings once and expose a read/write surface to settings screens.
 *
 * The provider also applies persisted appearance values through the theme
 * provider, so a reload restores theme, density, and chat font size. When a
 * provider already exists above it (for example in route tests), the nested
 * provider is a transparent pass-through unless an explicit service is given.
 */
export function SettingsProvider({ children, service }: SettingsProviderProps) {
  const existing = useContext(SettingsContext)

  if (existing !== null && service === undefined) {
    return <>{children}</>
  }

  if (service !== undefined) {
    return <SettingsProviderWithClient service={service}>{children}</SettingsProviderWithClient>
  }

  return <LazySettingsProvider>{children}</LazySettingsProvider>
}

function LazySettingsProvider({ children }: { children: ReactNode }) {
  const [client, setClient] = useState<SettingsClient | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false

    createDefaultSettingsClient().then(
      (value) => {
        if (!cancelled) {
          setClient(value)
        }
      },
      (cause: unknown) => {
        if (!cancelled) {
          setError(errorMessage(cause))
        }
      },
    )

    return () => {
      cancelled = true
    }
  }, [attempt])

  if (client !== null) {
    return <SettingsProviderWithClient service={client}>{children}</SettingsProviderWithClient>
  }

  if (error !== null) {
    return (
      <SettingsContext.Provider
        value={{
          settings: null,
          error: `${STALE_SETTINGS_MESSAGE} ${error}`,
          loading: false,
          service: null,
          update: async () => {
            throw new Error(STALE_SETTINGS_MESSAGE)
          },
          reload: async () => {
            setError(null)
            setAttempt((value) => value + 1)
          },
        }}
      >
        {children}
      </SettingsContext.Provider>
    )
  }

  return (
    <SettingsContext.Provider
      value={{
        settings: null,
        error: null,
        loading: true,
        service: null,
        update: async () => {
          throw new Error('Settings are still loading.')
        },
        reload: async () => undefined,
      }}
    >
      {children}
    </SettingsContext.Provider>
  )
}
