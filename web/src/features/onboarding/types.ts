import type { ModelInfo } from '../../providers/openrouter'

/** Values submitted by the onboarding key form. */
export interface ConnectInput {
  apiKey: string
  label: string
  rememberKeys: boolean
}

/** Result of a successful onboarding connection. */
export interface ConnectResult {
  conversationId: string
  /** Non-blocking notice, for example when the preset default is unavailable. */
  notice: string | null
}

/**
 * Surface used by the onboarding screen.
 *
 * The concrete `OnboardingService` implements this interface. Component tests
 * inject a fake so the screen can be exercised without IndexedDB or network.
 */
export interface OnboardingServiceLike {
  loadCatalog(signal: AbortSignal): Promise<ModelInfo[]>
  connect(input: ConnectInput): Promise<ConnectResult>
  isOnboardingComplete(): Promise<boolean>
}

export type OnboardingErrorCode =
  | 'invalid_input'
  | 'no_model'
  | 'not_configured'
  | 'storage'

/**
 * Service-level onboarding failure that is safe to show to the UI.
 *
 * Adapter failures (`OpenRouterRequestError`) and provider-service failures
 * propagate unchanged so the error-copy helper can map their codes.
 */
export class OnboardingError extends Error {
  readonly code: OnboardingErrorCode

  constructor(code: OnboardingErrorCode, message: string, options: { cause?: unknown } = {}) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause })
    this.name = 'OnboardingError'
    this.code = code
  }
}
