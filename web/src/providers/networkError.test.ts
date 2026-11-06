import { describe, expect, it } from 'vitest'
import {
  browserOnlineStatus,
  classifyNetworkFailure,
  corsNetworkMessage,
  isCorsShapedNetworkError,
} from './networkError'

describe('isCorsShapedNetworkError', () => {
  it('recognizes the rejection text browsers use for blocked requests', () => {
    expect(isCorsShapedNetworkError(new TypeError('Failed to fetch'))).toBe(true)
    expect(
      isCorsShapedNetworkError(
        new TypeError('NetworkError when attempting to fetch resource.'),
      ),
    ).toBe(true)
    expect(isCorsShapedNetworkError(new TypeError('Load failed'))).toBe(true)
    expect(
      isCorsShapedNetworkError(
        new TypeError('Blocked by CORS policy: No Access-Control-Allow-Origin header'),
      ),
    ).toBe(true)
  })

  it('does not treat unrelated failures as CORS-shaped', () => {
    expect(isCorsShapedNetworkError(new TypeError('network down'))).toBe(false)
    expect(isCorsShapedNetworkError(new Error('The server rejected the key'))).toBe(false)
    expect(isCorsShapedNetworkError(null)).toBe(false)
  })
})

describe('classifyNetworkFailure', () => {
  it('gives an explicit abort priority over every other signal', () => {
    expect(
      classifyNetworkFailure(new TypeError('Failed to fetch'), {
        aborted: true,
        online: false,
      }),
    ).toBe('aborted')
  })

  it('classifies a known offline state as network, even with CORS-shaped text', () => {
    expect(
      classifyNetworkFailure(new TypeError('Failed to fetch'), { online: false }),
    ).toBe('offline')
  })

  it('classifies CORS-shaped failures while online as cors', () => {
    expect(
      classifyNetworkFailure(new TypeError('Failed to fetch'), { online: true }),
    ).toBe('cors')
  })

  it('keeps generic online network failures as network', () => {
    expect(
      classifyNetworkFailure(new TypeError('network down'), { online: true }),
    ).toBe('network')
    expect(classifyNetworkFailure(new Error('socket hang up'), { online: true })).toBe(
      'network',
    )
  })

  it('reads navigator.onLine when the caller does not override it', () => {
    expect(browserOnlineStatus()).toBe(true)
    expect(classifyNetworkFailure(new TypeError('Failed to fetch'))).toBe('cors')
  })
})

describe('corsNetworkMessage', () => {
  it('names the provider and suggests Android or a self-hosted proxy', () => {
    const message = corsNetworkMessage('Anthropic')

    expect(message).toContain('Anthropic')
    expect(message).toContain('CORS restriction')
    expect(message).toContain('Android app')
    expect(message).toContain('self-hosted proxy')
    expect(message.toLowerCase()).not.toContain('hidden proxy')
  })
})
