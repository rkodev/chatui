import { useCallback, useLayoutEffect, useMemo, useState, type RefObject } from 'react'

export interface UseWindowedListOptions {
  keys: readonly string[]
  estimateHeight: number
  overscan: number
  containerRef: RefObject<HTMLElement | null>
  scrollTop: number
}

export interface UseWindowedListResult {
  start: number
  end: number
  topPadding: number
  bottomPadding: number
  totalHeight: number
  measureItem: (key: string, height: number) => void
}

function findStart(offsets: readonly number[], value: number): number {
  let low = 0
  let high = offsets.length - 1

  while (low < high) {
    const middle = Math.floor((low + high) / 2)

    if (offsets[middle] < value) {
      low = middle + 1
    } else {
      high = middle
    }
  }

  return Math.max(0, low - 1)
}

function findEnd(offsets: readonly number[], value: number): number {
  let low = 0
  let high = offsets.length - 1

  while (low < high) {
    const middle = Math.floor((low + high) / 2)

    if (offsets[middle] <= value) {
      low = middle + 1
    } else {
      high = middle
    }
  }

  return Math.min(offsets.length - 1, low)
}

/**
 * Lightweight windowing for a variable-height message list.
 *
 * Rows report their measured height through `measureItem`. Until a row has
 * been measured, the estimate is used. When the viewport has no measurable
 * height (for example in jsdom), every row is rendered so tests and
 * accessibility tools see the full list.
 */
export function useWindowedList({
  keys,
  estimateHeight,
  overscan,
  containerRef,
  scrollTop,
}: UseWindowedListOptions): UseWindowedListResult {
  const [viewportHeight, setViewportHeight] = useState(0)
  const [heights, setHeights] = useState<Record<string, number>>({})

  useLayoutEffect(() => {
    const element = containerRef.current

    if (!element) {
      return
    }

    const update = () => {
      setViewportHeight(element.clientHeight)
    }

    update()

    const observer = new ResizeObserver(update)
    observer.observe(element)

    return () => {
      observer.disconnect()
    }
  }, [containerRef])

  const measureItem = useCallback((key: string, height: number) => {
    if (height <= 0) {
      return
    }

    setHeights((current) => {
      const previous = current[key]

      if (previous !== undefined && Math.abs(previous - height) < 1) {
        return current
      }

      return { ...current, [key]: height }
    })
  }, [])

  const offsets = useMemo(() => {
    const next: number[] = [0]

    for (const key of keys) {
      next.push(next[next.length - 1] + (heights[key] ?? estimateHeight))
    }

    return next
  }, [estimateHeight, heights, keys])

  const totalHeight = offsets[offsets.length - 1] ?? 0

  if (viewportHeight <= 0 || keys.length === 0) {
    return {
      start: 0,
      end: keys.length,
      topPadding: 0,
      bottomPadding: 0,
      totalHeight,
      measureItem,
    }
  }

  const start = findStart(offsets, scrollTop - overscan * estimateHeight)
  const end = findEnd(offsets, scrollTop + viewportHeight + overscan * estimateHeight)

  return {
    start,
    end,
    topPadding: offsets[start] ?? 0,
    bottomPadding: totalHeight - (offsets[end] ?? totalHeight),
    totalHeight,
    measureItem,
  }
}
