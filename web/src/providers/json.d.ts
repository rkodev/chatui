/**
 * Vite resolves JSON imports to parsed values. The contracts document is
 * validated by `registry.ts`, so the ambient type stays `unknown` here instead
 * of duplicating provider metadata in TypeScript.
 */
declare module '*.json' {
  const value: unknown
  export default value
}
