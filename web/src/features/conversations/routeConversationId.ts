/**
 * Extract the conversation id from a `/chat/:conversationId` pathname.
 *
 * The root layout uses this to highlight the active conversation without
 * depending on the child route's params. Returns null for every other route.
 */
export function conversationIdFromPathname(pathname: string): string | null {
  const match = /^\/chat\/([^/]+)\/?$/.exec(pathname)

  if (!match) {
    return null
  }

  try {
    return decodeURIComponent(match[1])
  } catch {
    return match[1]
  }
}
