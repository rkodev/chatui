/**
 * Format the timestamp shown on a conversation row.
 *
 * Today shows the local time, the current year shows month/day, and older
 * conversations include the year. Invalid timestamps render nothing.
 */
export function formatConversationTimestamp(value: string, now: Date = new Date()): string {
  const date = new Date(value)

  if (Number.isNaN(date.getTime())) {
    return ''
  }

  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  }

  if (date.getFullYear() === now.getFullYear()) {
    return date.toLocaleDateString([], { month: 'short', day: 'numeric' })
  }

  return date.toLocaleDateString([], { year: 'numeric', month: 'short', day: 'numeric' })
}
