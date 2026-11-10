import { useStreamingStore } from '../../features/chat/streamingStore'

/**
 * Return true when any conversation has an unfinished stream.
 *
 * The update prompt uses this to defer activation. It reads the store directly
 * so the check is current at the moment the user clicks Update, not a stale
 * render value.
 */
export function hasActiveStream(): boolean {
  return Object.values(useStreamingStore.getState().streams).some((stream) => !stream.done)
}
