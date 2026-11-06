import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  OPENAI_CATALOG_DEFAULTS,
  catalogDefaultsForPreset,
  inferOpenAIModelMetadata,
  mapOpenAIModel,
  mapOpenAIModels,
  type OpenAICatalogDefaults,
} from './openaiCatalog'

interface CapabilityCase {
  name: string
  preset: {
    inputModalities: OpenAICatalogDefaults['inputModalities']
    outputModalities: OpenAICatalogDefaults['outputModalities']
    capabilities: OpenAICatalogDefaults['capabilities']
    supportedParameters: string[]
  }
  raw: Record<string, unknown>
  expected: {
    inputModalities: string[]
    outputModalities: string[]
    capabilities: string[]
    supportedParameters: string[]
    contextWindow: number | null
    maxOutputTokens: number | null
  }
}

interface CapabilityFixture {
  cases: CapabilityCase[]
}

function loadFixture(): CapabilityFixture {
  return JSON.parse(
    readFileSync(
      resolve(process.cwd(), '../contracts/fixtures/expected/capability-inference.json'),
      'utf8',
    ),
  ) as CapabilityFixture
}

function defaultsFor(testCase: CapabilityCase): OpenAICatalogDefaults {
  return {
    inputModalities: testCase.preset.inputModalities,
    outputModalities: testCase.preset.outputModalities,
    capabilities: testCase.preset.capabilities,
    supportedParameters: testCase.preset.supportedParameters,
  }
}

describe('OpenAI-compatible capability inference', () => {
  it.each(loadFixture().cases)('$name follows the shared rule', (testCase) => {
    expect(inferOpenAIModelMetadata(testCase.raw, defaultsFor(testCase))).toEqual(
      testCase.expected,
    )
  })

  it('uses preset defaults when a model entry only has an id', () => {
    const model = mapOpenAIModel({ id: 'gpt-4o-mini' })

    expect(model).toMatchObject({
      remoteId: 'gpt-4o-mini',
      contextWindow: null,
      maxOutputTokens: null,
      inputModalities: ['text'],
      outputModalities: ['text'],
      capabilities: ['text', 'streaming'],
      supportedParameters: OPENAI_CATALOG_DEFAULTS.supportedParameters,
    })
  })

  it('prefers raw modalities, parameters, and limits over defaults', () => {
    const metadata = inferOpenAIModelMetadata(
      {
        id: 'vision-model',
        inputModalities: ['text', 'image'],
        outputModalities: ['text'],
        supportedParameters: ['stream', 'tools', 'reasoning'],
        contextWindow: 128000,
        maxOutputTokens: 4096,
      },
      OPENAI_CATALOG_DEFAULTS,
    )

    expect(metadata.inputModalities).toEqual(['text', 'image'])
    expect(metadata.capabilities).toEqual(['text', 'vision', 'tools', 'reasoning', 'streaming'])
    expect(metadata.supportedParameters).toEqual(['stream', 'tools', 'reasoning'])
    expect(metadata.contextWindow).toBe(128000)
    expect(metadata.maxOutputTokens).toBe(4096)
  })

  it('accepts snake_case catalog fields', () => {
    const metadata = inferOpenAIModelMetadata({
      id: 'snake',
      input_modalities: ['text', 'file'],
      supported_parameters: ['stream'],
      context_length: 8192,
      max_output_tokens: 1024,
    })

    expect(metadata.inputModalities).toEqual(['text', 'file'])
    expect(metadata.capabilities).toEqual(['text', 'files', 'streaming'])
    expect(metadata.contextWindow).toBe(8192)
    expect(metadata.maxOutputTokens).toBe(1024)
  })

  it('applies the Anthropic and Gemini preset defaults', () => {
    expect(catalogDefaultsForPreset('anthropic').inputModalities).toEqual(['text', 'image'])
    expect(catalogDefaultsForPreset('google').inputModalities).toEqual(['text', 'image', 'file'])
    expect(catalogDefaultsForPreset('openai')).toEqual(OPENAI_CATALOG_DEFAULTS)
  })

  it('skips malformed entries and rejects a malformed envelope', () => {
    expect(mapOpenAIModels({ data: [{ id: 'ok' }, null, { id: '  ' }] })).toHaveLength(1)
    expect(() => mapOpenAIModels({ models: [] })).toThrow('data array')
  })
})
