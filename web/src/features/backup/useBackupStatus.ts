import { useCallback, useEffect, useState } from 'react'

/** Status shown on `/settings/backup`. */
export interface BackupStatus {
  enabled: boolean
  lastBackupAt: string | null
  pendingChanges: number
  lastError: string | null
  /** Approximate local data size that a backup would upload. */
  storageUsageBytes: number
}

export interface BackupStatusServiceLike {
  getStatus(): Promise<BackupStatus>
}

export interface UseBackupStatusOptions {
  /** Poll interval in milliseconds. Defaults to 5 seconds. */
  intervalMs?: number
  /** Bump to reload immediately after a manual action. */
  refreshKey?: number
}

export interface UseBackupStatusResult {
  status: BackupStatus | null
  error: string | null
  refresh: () => Promise<void>
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause)
}

/**
 * Poll the backup status while the settings screen is mounted.
 *
 * The web client has no background sync in this milestone, so polling is the
 * only way to reflect a manual backup or a pending queue change. The interval
 * is cleared on unmount.
 */
export function useBackupStatus(
  service: BackupStatusServiceLike | null,
  options: UseBackupStatusOptions = {},
): UseBackupStatusResult {
  const { intervalMs = 5000, refreshKey = 0 } = options
  const [status, setStatus] = useState<BackupStatus | null>(null)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    if (service === null) {
      return
    }

    try {
      setStatus(await service.getStatus())
      setError(null)
    } catch (cause) {
      setError(errorMessage(cause))
    }
  }, [service])

  useEffect(() => {
    const currentService = service
    if (currentService === null) {
      return
    }
    const activeService: BackupStatusServiceLike = currentService

    let cancelled = false

    async function load() {
      try {
        const next = await activeService.getStatus()
        if (!cancelled) {
          setStatus(next)
          setError(null)
        }
      } catch (cause) {
        if (!cancelled) {
          setError(errorMessage(cause))
        }
      }
    }

    void load()
    const timer = setInterval(() => {
      void load()
    }, intervalMs)

    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [intervalMs, refreshKey, service])

  return { status, error, refresh }
}
