#!/usr/bin/env node
/**
 * Deterministic contract validator for ChatUI.
 *
 * This script intentionally has no network access. It reads the contract tree,
 * compiles the JSON Schemas once, and reports every problem it can find before
 * exiting non-zero.
 */

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { TextDecoder, isDeepStrictEqual } from 'node:util';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..');
const contractsDir = path.join(repoRoot, 'contracts');
const fixturesDir = path.join(contractsDir, 'fixtures');
const expectedDir = path.join(fixturesDir, 'expected');
const requestsDir = path.join(fixturesDir, 'requests');
const schemaDir = path.join(contractsDir, 'schema');

const EXPECTED_STREAM_SOURCES = new Map([
  ['openrouter-text.json', 'openrouter-text.sse'],
  ['openrouter-keepalive.json', 'openrouter-keepalive.sse'],
  ['openrouter-reasoning.json', 'openrouter-reasoning.sse'],
  ['openrouter-usage.json', 'openrouter-usage.sse'],
  ['openrouter-error-200.json', 'openrouter-error-200.sse'],
  ['openai-text.json', 'openai-text.sse'],
  ['openai-reasoning.json', 'openai-reasoning.sse'],
  ['anthropic-text.json', 'anthropic-text.sse'],
  ['gemini-text.json', 'gemini-text.sse'],
  ['ollama-ndjson.json', 'ollama-ndjson.txt'],
  ['error-429.json', 'error-429.sse'],
]);
const EXPECTED_KEY_FILE = 'openrouter-key.json';
const EXPECTED_ERRORS_FILE = 'openrouter-errors.json';
const EXPECTED_PROVIDER_ERRORS_FILE = 'provider-errors.json';
const EXPECTED_ANTHROPIC_THINKING_FILE = 'anthropic-thinking.json';
const EXPECTED_GEMINI_FINISH_REASONS_FILE = 'gemini-finish-reasons.json';
const EXPECTED_CATALOGS_FILE = 'catalogs.json';
const EXPECTED_CAPABILITY_INFERENCE_FILE = 'capability-inference.json';
const EXPECTED_FALLBACK_MODELS_FILE = 'fallback-models.json';
const EXPECTED_MATH_FILE = 'math.json';
const EXPECTED_EXPORT_FILE = 'chatui-export.json';
const EXPECTED_FILES = new Set([
  ...EXPECTED_STREAM_SOURCES.keys(),
  EXPECTED_KEY_FILE,
  EXPECTED_ERRORS_FILE,
  EXPECTED_PROVIDER_ERRORS_FILE,
  EXPECTED_ANTHROPIC_THINKING_FILE,
  EXPECTED_GEMINI_FINISH_REASONS_FILE,
  EXPECTED_CATALOGS_FILE,
  EXPECTED_CAPABILITY_INFERENCE_FILE,
  EXPECTED_FALLBACK_MODELS_FILE,
  EXPECTED_MATH_FILE,
  EXPECTED_EXPORT_FILE,
]);

const REQUEST_KINDS = ['chat-request', 'protected-fields', 'attribution', 'default-model'];
const REQUEST_PROVIDER_IDS = ['openrouter', 'openai', 'anthropic', 'google', 'ollama'];
const EXTRA_NAMESPACE_BY_PROVIDER = {
  openrouter: 'openrouter',
  openai: 'openai',
  anthropic: 'anthropic',
  google: 'google',
  ollama: 'ollama',
};
const REQUIRED_REQUEST_FILES = [
  'openrouter-basic.json',
  'openrouter-routing.json',
  'openrouter-advanced-protected.json',
  'openrouter-attribution.json',
  'openai-basic.json',
  'openai-attachments.json',
  'openai-extras.json',
  'openai-protected.json',
  'anthropic-basic.json',
  'anthropic-attachments.json',
  'anthropic-extras.json',
  'anthropic-protected.json',
  'gemini-basic.json',
  'gemini-attachments.json',
  'gemini-extras.json',
  'gemini-protected.json',
  'ollama-basic.json',
  'ollama-attachments.json',
  'ollama-extras.json',
  'ollama-protected.json',
];
const REQUIRED_REQUEST_PROVIDERS = ['openai', 'anthropic', 'google', 'ollama'];
const CATALOG_PROVIDER_IDS = ['openai', 'anthropic', 'google', 'ollama'];
const CATALOG_ENTRY_FIELDS = [
  'remoteId',
  'label',
  'contextWindow',
  'maxOutputTokens',
  'inputModalities',
  'outputModalities',
  'capabilities',
  'supportedParameters',
  'pricing',
  'hidden',
];
const CAPABILITY_ORDER = ['text', 'vision', 'files', 'tools', 'reasoning', 'streaming'];
const CAPABILITY_INFERENCE_FIELDS = [
  'inputModalities',
  'outputModalities',
  'capabilities',
  'supportedParameters',
  'contextWindow',
  'maxOutputTokens',
];
const FALLBACK_PRESET_IDS = ['openai', 'anthropic', 'google', 'ollama'];
const MATH_SEGMENT_TYPES = ['text', 'math'];
const BASE64_PATTERN = /^[A-Za-z0-9+/]*={0,2}$/;
const PROTECTED_REQUEST_FIELDS = ['model', 'messages', 'stream', 'stream_options'];
const REQUEST_BODY_FIELDS = [
  'model',
  'messages',
  'stream',
  'stream_options',
  'max_tokens',
  'temperature',
  'top_p',
  'stop',
  'reasoning',
  'provider',
  'models',
  'route',
];
const MESSAGE_ROLES = ['user', 'assistant', 'tool'];
const PART_TYPES = ['text', 'image', 'file', 'reasoning', 'tool-call', 'tool-result'];
const REASONING_EFFORTS = ['low', 'medium', 'high'];
const ROUTING_SORTS = ['price', 'throughput', 'latency'];
const DATA_COLLECTION_VALUES = ['allow', 'deny'];
const EXTRA_OPENROUTER_FIELDS = ['provider', 'models', 'route', 'reasoning'];

const EVENT_TYPES = [
  'start',
  'text-delta',
  'reasoning-delta',
  'tool-call-delta',
  'usage',
  'finish',
  'error',
];
const FINISH_REASONS = [
  'stop',
  'length',
  'content_filter',
  'tool_calls',
  'cancelled',
  'error',
  'unknown',
];
const CHAT_ERROR_CODES = [
  'auth',
  'permission',
  'rate_limit',
  'quota',
  'model_not_found',
  'context_length',
  'content_filter',
  'invalid_request',
  'network',
  'cors',
  'timeout',
  'cancelled',
  'provider',
  'parse',
  'storage',
  'unknown',
];

const PROVIDER_ERROR_IDS = ['openai', 'anthropic', 'google', 'ollama', 'openrouter'];
const PROVIDER_ERROR_KINDS = ['http', 'stream', 'synthetic'];
const REQUIRED_PROVIDER_ERROR_CODES = [
  'auth',
  'permission',
  'rate_limit',
  'quota',
  'model_not_found',
  'context_length',
  'content_filter',
  'network',
  'timeout',
  'provider',
];
const PROVIDER_REQUEST_ID_HEADERS = {
  openai: ['x-request-id'],
  anthropic: ['request-id'],
  google: ['x-goog-request-id'],
  ollama: [],
  openrouter: [],
};

const ANTHROPIC_RAW_EVENT_TYPES = [
  'message_start',
  'content_block_start',
  'content_block_delta',
  'content_block_stop',
  'message_delta',
  'message_stop',
  'error',
];

const GEMINI_FINISH_REASONS = [
  'STOP',
  'MAX_TOKENS',
  'SAFETY',
  'RECITATION',
  'OTHER',
  'BLOCKLIST',
  'PROHIBITED_CONTENT',
  'SPII',
  'MALFORMED_FUNCTION_CALL',
  'UNEXPECTED_TOOL_CALL',
  'LANGUAGE',
  'FINISH_REASON_UNSPECIFIED',
];
const GEMINI_FINISH_MAPPING = {
  STOP: 'stop',
  MAX_TOKENS: 'length',
  SAFETY: 'content_filter',
  RECITATION: 'content_filter',
};

const SECRET_PATTERNS = [
  /\bsk-[A-Za-z0-9]{16,}\b/,
  /\bAIza[0-9A-Za-z_-]{20,}\b/,
  /\bBearer\s+[A-Za-z0-9._-]{16,}\b/,
];

const REQUIRED_SCHEMA_FILES = [
  'provider-preset.schema.json',
  'model-info.schema.json',
  'settings.schema.json',
  'chat-export.schema.json',
  'backup-manifest.schema.json',
];

const REQUIRED_PRESET_FIELDS = [
  'id',
  'label',
  'kind',
  'baseUrl',
  'auth',
  'keyValidation',
  'modelDiscovery',
  'defaultHeaders',
  'attributionHeaders',
  'browserCors',
  'docsUrl',
  'defaultModelId',
  'default',
  'reference',
  'required',
];

const EXPECTED_PRESET_IDS = [
  'openrouter',
  'openai',
  'anthropic',
  'google',
  'groq',
  'mistral',
  'deepseek',
  'xai',
  'together',
  'fireworks',
  'ollama',
  'lmstudio',
  'llamacpp',
  'custom',
];

const REQUIRED_PARITY_COLUMNS = ['Feature', 'Web', 'Android', 'Rust'];

const REQUIRED_PARITY_ROWS = [
  'BYOK onboarding',
  'OpenRouter first-run onboarding',
  'OpenRouter key introspection (`/key`)',
  'OpenRouter catalog, pricing, modalities',
  'OpenRouter streaming, reasoning, usage/cost',
  'OpenRouter provider routing preferences',
  'OpenAI-compatible streaming',
  'Anthropic streaming',
  'Gemini streaming',
  'Ollama local',
  'Model discovery and switching',
  'Conversation persistence',
  'Search',
  'Markdown + code + math',
  'Attachments',
  'Export/import',
  'Backup/sync',
  'PWA install',
  'Native share target',
  'Desktop packaging (installer/AppImage)',
  'Auto-update',
];

const errors = [];
const jsonCache = new Map();

function relative(filePath) {
  return path.relative(repoRoot, filePath) || filePath;
}

function addError(filePath, field, message) {
  errors.push(`${relative(filePath)}: ${field}: ${message}`);
}

function walkFiles(dir) {
  if (!existsSync(dir)) {
    return [];
  }

  const files = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...walkFiles(fullPath));
    } else if (entry.isFile()) {
      files.push(fullPath);
    }
  }
  return files.sort();
}

function readJson(filePath) {
  if (jsonCache.has(filePath)) {
    return jsonCache.get(filePath);
  }

  let buffer;
  try {
    buffer = readFileSync(filePath);
  } catch (error) {
    addError(filePath, '$', `cannot read file: ${error.message}`);
    jsonCache.set(filePath, undefined);
    return undefined;
  }

  let text;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(buffer);
  } catch {
    addError(filePath, '$', 'file is not valid UTF-8');
    jsonCache.set(filePath, undefined);
    return undefined;
  }

  try {
    const document = JSON.parse(text);
    jsonCache.set(filePath, document);
    return document;
  } catch (error) {
    addError(filePath, '$', `invalid JSON: ${error.message}`);
    jsonCache.set(filePath, undefined);
    return undefined;
  }
}

function ajvField(error) {
  let field = error.instancePath || '';
  if (error.keyword === 'required' && error.params?.missingProperty) {
    field = `${field}/${error.params.missingProperty}`;
  } else if (error.keyword === 'additionalProperties' && error.params?.additionalProperty) {
    field = `${field}/${error.params.additionalProperty}`;
  }
  return field || '$';
}

function ajvMessage(error) {
  switch (error.keyword) {
    case 'required':
      return `required property "${error.params.missingProperty}" is missing`;
    case 'additionalProperties':
      return `unexpected property "${error.params.additionalProperty}"`;
    case 'enum':
      return `must be one of ${JSON.stringify(error.params.allowedValues)}`;
    case 'const':
      return `must equal ${JSON.stringify(error.params.allowedValue)}`;
    case 'pattern':
      return `must match pattern ${error.params.pattern}`;
    case 'format':
      return `must match format "${error.params.format}"`;
    case 'type':
      return `must be ${error.params.type}`;
    case 'oneOf':
      return 'must match exactly one allowed schema';
    case 'anyOf':
      return 'must match at least one allowed schema';
    default:
      return error.message || 'is invalid';
  }
}

function reportAjvErrors(filePath, validator, prefix = '') {
  for (const error of validator.errors ?? []) {
    addError(filePath, `${prefix}${ajvField(error)}`, ajvMessage(error));
  }
}

function loadSchemas() {
  const schemas = new Map();
  for (const filePath of walkFiles(schemaDir).filter((file) => file.endsWith('.json'))) {
    const document = readJson(filePath);
    if (document !== undefined) {
      schemas.set(path.basename(filePath), document);
    }
  }

  for (const name of REQUIRED_SCHEMA_FILES) {
    if (!schemas.has(name)) {
      addError(path.join(schemaDir, name), '$', 'required schema file is missing');
    }
  }

  for (const [name, schema] of schemas) {
    const filePath = path.join(schemaDir, name);
    if (schema.$schema !== 'https://json-schema.org/draft/2020-12/schema') {
      addError(filePath, '$schema', 'must be "https://json-schema.org/draft/2020-12/schema"');
    }
    if (typeof schema.$id !== 'string' || schema.$id.length === 0) {
      addError(filePath, '$id', 'must be a non-empty string');
    }
    if (typeof schema.title !== 'string' || schema.title.length === 0) {
      addError(filePath, 'title', 'must be a non-empty string');
    }
    if (!Number.isInteger(schema.contractVersion)) {
      addError(filePath, 'contractVersion', 'must be an integer');
    }
  }

  return schemas;
}

function compileSchemas(schemas) {
  const ajv = new Ajv2020({ allErrors: true, strict: false });
  addFormats(ajv);

  const validators = new Map();
  for (const [name, schema] of schemas) {
    try {
      validators.set(name, ajv.compile(schema));
    } catch (error) {
      addError(path.join(schemaDir, name), '$', `schema does not compile: ${error.message}`);
    }
  }
  return validators;
}

function validateAllJsonFiles() {
  for (const filePath of walkFiles(contractsDir).filter((file) => file.endsWith('.json'))) {
    readJson(filePath);
  }
}

function validateProviders(validators) {
  const filePath = path.join(contractsDir, 'providers.json');
  const document = readJson(filePath);
  if (document === undefined) {
    return;
  }
  if (!Array.isArray(document)) {
    addError(filePath, '$', 'must be an array of provider presets');
    return;
  }

  const validatePreset = validators.get('provider-preset.schema.json');
  const seenIds = new Map();
  const rolePresets = { default: [], reference: [], required: [] };

  document.forEach((preset, index) => {
    const base = `/${index}`;
    if (preset === null || typeof preset !== 'object' || Array.isArray(preset)) {
      addError(filePath, base, 'must be an object');
      return;
    }

    for (const field of REQUIRED_PRESET_FIELDS) {
      if (!Object.prototype.hasOwnProperty.call(preset, field)) {
        addError(filePath, `${base}/${field}`, 'required field is missing');
      }
    }

    if (typeof preset.id === 'string') {
      if (seenIds.has(preset.id)) {
        addError(
          filePath,
          `${base}/id`,
          `duplicate preset id "${preset.id}" (first seen at index ${seenIds.get(preset.id)})`,
        );
      } else {
        seenIds.set(preset.id, index);
      }
    }

    for (const role of ['default', 'reference', 'required']) {
      if (preset[role] === true) {
        rolePresets[role].push({ preset, index });
      }
    }

    if (validatePreset) {
      const valid = validatePreset(preset);
      if (!valid) {
        reportAjvErrors(filePath, validatePreset, base);
      }
    }
  });

  for (const id of EXPECTED_PRESET_IDS) {
    if (!seenIds.has(id)) {
      addError(filePath, '$', `missing required preset id "${id}"`);
    }
  }
  for (const [id, index] of seenIds) {
    if (!EXPECTED_PRESET_IDS.includes(id)) {
      addError(filePath, `/${index}/id`, `unexpected preset id "${id}"`);
    }
  }

  for (const role of ['default', 'reference', 'required']) {
    const matches = rolePresets[role];
    if (matches.length !== 1) {
      addError(filePath, '$', `expected exactly one preset with ${role}: true, found ${matches.length}`);
      continue;
    }
    const [{ preset, index }] = matches;
    if (preset.id !== 'openrouter') {
      addError(filePath, `/${index}/${role}`, `the ${role} preset must be "openrouter", found "${preset.id}"`);
    }
  }
}

function validateModelFixtures(validators) {
  const validateModelInfo = validators.get('model-info.schema.json');
  const modelFixturesDir = path.join(fixturesDir, 'models');

  for (const filePath of walkFiles(modelFixturesDir).filter((file) => file.endsWith('.json'))) {
    const document = readJson(filePath);
    if (document === undefined) {
      continue;
    }

    // The key introspection fixture is not a model catalog. It is parsed as
    // JSON above and intentionally excluded from the model-info schema.
    if (path.basename(filePath) === 'openrouter-key.json') {
      continue;
    }

    if (!validateModelInfo) {
      continue;
    }

    // M0-01.2 decision: a normalized model object is validated directly; a raw
    // provider catalog envelope is validated as an envelope. Arrays are treated
    // as lists of normalized models.
    const documents = Array.isArray(document) ? document : [document];
    documents.forEach((item, index) => {
      const valid = validateModelInfo(item);
      if (!valid) {
        const prefix = Array.isArray(document) ? `/${index}` : '';
        reportAjvErrors(filePath, validateModelInfo, prefix);
      }
    });
  }
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function checkObjectShape(filePath, field, value, allowedKeys, requiredKeys) {
  if (!isPlainObject(value)) {
    addError(filePath, field || '$', 'must be an object');
    return false;
  }

  for (const key of requiredKeys) {
    if (!Object.prototype.hasOwnProperty.call(value, key)) {
      addError(filePath, `${field}/${key}`, 'required property is missing');
    }
  }

  for (const key of Object.keys(value)) {
    if (!allowedKeys.includes(key)) {
      addError(filePath, `${field}/${key}`, `unexpected property "${key}"`);
    }
  }

  return true;
}

function validateUsage(filePath, field, usage) {
  const fields = ['inputTokens', 'outputTokens', 'totalTokens', 'costUsd', 'estimated'];
  if (!checkObjectShape(filePath, field, usage, fields, fields)) {
    return;
  }

  for (const key of ['inputTokens', 'outputTokens', 'totalTokens']) {
    const value = usage[key];
    if (value !== null && !Number.isInteger(value)) {
      addError(filePath, `${field}/${key}`, 'must be an integer or null');
    } else if (Number.isInteger(value) && value < 0) {
      addError(filePath, `${field}/${key}`, 'must be greater than or equal to 0');
    }
  }

  if (usage.costUsd !== null && (typeof usage.costUsd !== 'number' || !Number.isFinite(usage.costUsd))) {
    addError(filePath, `${field}/costUsd`, 'must be a finite number or null');
  } else if (typeof usage.costUsd === 'number' && usage.costUsd < 0) {
    addError(filePath, `${field}/costUsd`, 'must be greater than or equal to 0');
  }

  if (typeof usage.estimated !== 'boolean') {
    addError(filePath, `${field}/estimated`, 'must be a boolean');
  }
}

function validateChatError(filePath, field, error) {
  const fields = [
    'code',
    'message',
    'providerId',
    'status',
    'retryable',
    'retryAfterSeconds',
    'requestId',
    'details',
  ];
  if (!checkObjectShape(filePath, field, error, fields, fields)) {
    return;
  }

  if (!CHAT_ERROR_CODES.includes(error.code)) {
    addError(filePath, `${field}/code`, `must be one of ${JSON.stringify(CHAT_ERROR_CODES)}`);
  }
  if (typeof error.message !== 'string' || error.message.length === 0) {
    addError(filePath, `${field}/message`, 'must be a non-empty string');
  }
  if (error.providerId !== null && typeof error.providerId !== 'string') {
    addError(filePath, `${field}/providerId`, 'must be a string or null');
  }
  if (error.status !== null && !Number.isInteger(error.status)) {
    addError(filePath, `${field}/status`, 'must be an integer or null');
  }
  if (typeof error.retryable !== 'boolean') {
    addError(filePath, `${field}/retryable`, 'must be a boolean');
  }
  if (
    error.retryAfterSeconds !== null &&
    (typeof error.retryAfterSeconds !== 'number' ||
      !Number.isFinite(error.retryAfterSeconds) ||
      error.retryAfterSeconds < 0)
  ) {
    addError(filePath, `${field}/retryAfterSeconds`, 'must be a non-negative number or null');
  }
  if (error.requestId !== null && typeof error.requestId !== 'string') {
    addError(filePath, `${field}/requestId`, 'must be a string or null');
  }
  if (error.details !== null && !isPlainObject(error.details)) {
    addError(filePath, `${field}/details`, 'must be an object or null');
  }
}

function validateEvent(filePath, field, event) {
  if (!isPlainObject(event)) {
    addError(filePath, field, 'must be an object');
    return;
  }

  if (typeof event.type !== 'string') {
    addError(filePath, `${field}/type`, 'must be a string');
    return;
  }

  switch (event.type) {
    case 'start':
      checkObjectShape(filePath, field, event, ['type', 'responseId'], ['type', 'responseId']);
      if (event.responseId !== null && typeof event.responseId !== 'string') {
        addError(filePath, `${field}/responseId`, 'must be a string or null');
      }
      break;
    case 'text-delta':
    case 'reasoning-delta':
      checkObjectShape(filePath, field, event, ['type', 'text'], ['type', 'text']);
      if (typeof event.text !== 'string') {
        addError(filePath, `${field}/text`, 'must be a string');
      }
      break;
    case 'tool-call-delta':
      checkObjectShape(
        filePath,
        field,
        event,
        ['type', 'index', 'id', 'name', 'argumentsDelta'],
        ['type', 'index', 'id', 'name', 'argumentsDelta'],
      );
      if (!Number.isInteger(event.index) || event.index < 0) {
        addError(filePath, `${field}/index`, 'must be a non-negative integer');
      }
      if (event.id !== null && typeof event.id !== 'string') {
        addError(filePath, `${field}/id`, 'must be a string or null');
      }
      if (event.name !== null && typeof event.name !== 'string') {
        addError(filePath, `${field}/name`, 'must be a string or null');
      }
      if (typeof event.argumentsDelta !== 'string') {
        addError(filePath, `${field}/argumentsDelta`, 'must be a string');
      }
      break;
    case 'usage':
      checkObjectShape(filePath, field, event, ['type', 'usage'], ['type', 'usage']);
      validateUsage(filePath, `${field}/usage`, event.usage);
      break;
    case 'finish':
      checkObjectShape(filePath, field, event, ['type', 'reason'], ['type', 'reason']);
      if (!FINISH_REASONS.includes(event.reason)) {
        addError(filePath, `${field}/reason`, `must be one of ${JSON.stringify(FINISH_REASONS)}`);
      }
      break;
    case 'error':
      checkObjectShape(filePath, field, event, ['type', 'error'], ['type', 'error']);
      validateChatError(filePath, `${field}/error`, event.error);
      break;
    default:
      addError(filePath, `${field}/type`, `must be one of ${JSON.stringify(EVENT_TYPES)}`);
  }
}

function validateStreamExpected(filePath, document, expectedSource) {
  const fields = ['source', 'events', 'text', 'reasoning', 'retryAllowed'];
  if (!checkObjectShape(filePath, '', document, fields, fields)) {
    return;
  }

  if (document.source !== expectedSource) {
    addError(filePath, '/source', `must be "${expectedSource}"`);
  }

  if (!Array.isArray(document.events)) {
    addError(filePath, '/events', 'must be an array');
  } else {
    if (document.events.length === 0) {
      addError(filePath, '/events', 'must contain at least one event');
    }
    document.events.forEach((event, index) => validateEvent(filePath, `/events/${index}`, event));

    const text = document.events
      .filter((event) => isPlainObject(event) && event.type === 'text-delta' && typeof event.text === 'string')
      .map((event) => event.text)
      .join('');
    const reasoning = document.events
      .filter(
        (event) => isPlainObject(event) && event.type === 'reasoning-delta' && typeof event.text === 'string',
      )
      .map((event) => event.text)
      .join('');

    if (typeof document.text === 'string' && document.text !== text) {
      addError(filePath, '/text', `must equal the concatenated text-delta events (${JSON.stringify(text)})`);
    }
    if (typeof document.reasoning === 'string' && document.reasoning !== reasoning) {
      addError(
        filePath,
        '/reasoning',
        `must equal the concatenated reasoning-delta events (${JSON.stringify(reasoning)})`,
      );
    }
  }

  if (typeof document.text !== 'string') {
    addError(filePath, '/text', 'must be a string');
  }
  if (typeof document.reasoning !== 'string') {
    addError(filePath, '/reasoning', 'must be a string');
  }
  if (typeof document.retryAllowed !== 'boolean') {
    addError(filePath, '/retryAllowed', 'must be a boolean');
  }
}

function validateKeyExpected(filePath, document) {
  if (!checkObjectShape(filePath, '', document, ['source', 'providerId', 'key'], ['source', 'providerId', 'key'])) {
    return;
  }

  if (document.source !== 'openrouter-key.json') {
    addError(filePath, '/source', 'must be "openrouter-key.json"');
  }
  if (document.providerId !== 'openrouter') {
    addError(filePath, '/providerId', 'must be "openrouter"');
  }

  const keyFields = ['label', 'usageUsd', 'limitUsd', 'limitRemainingUsd', 'isFreeTier', 'rateLimit'];
  if (!checkObjectShape(filePath, '/key', document.key, keyFields, keyFields)) {
    return;
  }

  if (typeof document.key.label !== 'string' || document.key.label.length === 0) {
    addError(filePath, '/key/label', 'must be a non-empty string');
  }
  if (typeof document.key.usageUsd !== 'number' || !Number.isFinite(document.key.usageUsd)) {
    addError(filePath, '/key/usageUsd', 'must be a finite number');
  }
  for (const field of ['limitUsd', 'limitRemainingUsd']) {
    const value = document.key[field];
    if (value !== null && (typeof value !== 'number' || !Number.isFinite(value))) {
      addError(filePath, `/key/${field}`, 'must be a finite number or null');
    }
  }
  if (typeof document.key.isFreeTier !== 'boolean') {
    addError(filePath, '/key/isFreeTier', 'must be a boolean');
  }

  if (document.key.rateLimit !== null) {
    if (!checkObjectShape(filePath, '/key/rateLimit', document.key.rateLimit, ['requests', 'interval'], ['requests', 'interval'])) {
      return;
    }
    if (!Number.isInteger(document.key.rateLimit.requests) || document.key.rateLimit.requests < 0) {
      addError(filePath, '/key/rateLimit/requests', 'must be a non-negative integer');
    }
    if (typeof document.key.rateLimit.interval !== 'string' || document.key.rateLimit.interval.length === 0) {
      addError(filePath, '/key/rateLimit/interval', 'must be a non-empty string');
    }
  }
}

function validateErrorsExpected(filePath, document) {
  if (!checkObjectShape(filePath, '', document, ['source', 'cases'], ['source', 'cases'])) {
    return;
  }

  if (document.source !== 'openrouter-errors') {
    addError(filePath, '/source', 'must be "openrouter-errors"');
  }

  if (!Array.isArray(document.cases)) {
    addError(filePath, '/cases', 'must be an array');
    return;
  }
  if (document.cases.length === 0) {
    addError(filePath, '/cases', 'must contain at least one case');
  }

  const seenNames = new Set();
  document.cases.forEach((item, index) => {
    const field = `/cases/${index}`;
    if (
      !checkObjectShape(
        filePath,
        field,
        item,
        ['name', 'fixture', 'synthetic', 'status', 'error'],
        ['name', 'status', 'error'],
      )
    ) {
      return;
    }

    if (typeof item.name !== 'string' || item.name.length === 0) {
      addError(filePath, `${field}/name`, 'must be a non-empty string');
    } else if (seenNames.has(item.name)) {
      addError(filePath, `${field}/name`, `duplicate case name "${item.name}"`);
    } else {
      seenNames.add(item.name);
    }

    if (item.fixture !== undefined) {
      if (typeof item.fixture !== 'string' || item.fixture.length === 0) {
        addError(filePath, `${field}/fixture`, 'must be a non-empty string');
      } else {
        const fixturePath = path.resolve(fixturesDir, item.fixture);
        const errorsDir = path.join(fixturesDir, 'errors');
        if (!fixturePath.startsWith(`${errorsDir}${path.sep}`)) {
          addError(filePath, `${field}/fixture`, 'must reference a file under fixtures/errors');
        } else if (!existsSync(fixturePath)) {
          addError(filePath, `${field}/fixture`, `references missing fixture "${item.fixture}"`);
        }
      }
    } else if (item.synthetic !== true) {
      addError(filePath, `${field}/fixture`, 'must reference a fixture or set synthetic to true');
    }

    if (item.synthetic !== undefined && typeof item.synthetic !== 'boolean') {
      addError(filePath, `${field}/synthetic`, 'must be a boolean');
    }
    if (!Number.isInteger(item.status)) {
      addError(filePath, `${field}/status`, 'must be an integer');
    }

    validateChatError(filePath, `${field}/error`, item.error);
    if (isPlainObject(item.error) && item.error.status !== item.status) {
      addError(filePath, `${field}/error/status`, 'must match the case status');
    }
  });
}

function providerRequestIdFromHeaders(providerId, headers) {
  for (const name of PROVIDER_REQUEST_ID_HEADERS[providerId] ?? []) {
    if (typeof headers[name] === 'string' && headers[name].length > 0) {
      return headers[name];
    }
  }
  return null;
}

function validateProviderHttpErrorFixture(fixturePath, caseStatus, providerId) {
  const document = readJson(fixturePath);
  if (document === undefined) {
    return undefined;
  }
  if (!checkObjectShape(fixturePath, '', document, ['status', 'headers', 'body'], ['status', 'headers', 'body'])) {
    return undefined;
  }

  if (!Number.isInteger(document.status)) {
    addError(fixturePath, '/status', 'must be an integer');
  } else if (document.status !== caseStatus) {
    addError(fixturePath, '/status', `must equal the case status ${caseStatus}`);
  }

  if (!isPlainObject(document.headers)) {
    addError(fixturePath, '/headers', 'must be an object');
    return undefined;
  }
  for (const [key, value] of Object.entries(document.headers)) {
    if (typeof value !== 'string') {
      addError(fixturePath, `/headers/${key}`, 'must be a string');
    }
  }

  if (!isPlainObject(document.body)) {
    addError(fixturePath, '/body', 'must be an object');
  } else if (!Object.prototype.hasOwnProperty.call(document.body, 'error')) {
    addError(fixturePath, '/body/error', 'required property is missing');
  } else if (!isPlainObject(document.body.error) && typeof document.body.error !== 'string') {
    addError(fixturePath, '/body/error', 'must be an object or string');
  }

  return providerRequestIdFromHeaders(providerId, document.headers);
}

function validateProviderErrorsExpected(filePath, document) {
  if (!checkObjectShape(filePath, '', document, ['source', 'cases'], ['source', 'cases'])) {
    return;
  }

  if (document.source !== 'provider-errors') {
    addError(filePath, '/source', 'must be "provider-errors"');
  }

  if (!Array.isArray(document.cases)) {
    addError(filePath, '/cases', 'must be an array');
    return;
  }
  if (document.cases.length === 0) {
    addError(filePath, '/cases', 'must contain at least one case');
  }

  const seenNames = new Set();
  const seenCodes = new Set();
  let httpCases = 0;
  let streamCases = 0;

  document.cases.forEach((item, index) => {
    const field = `/cases/${index}`;
    if (
      !checkObjectShape(
        filePath,
        field,
        item,
        ['name', 'providerId', 'kind', 'fixture', 'synthetic', 'httpStatus', 'status', 'error'],
        ['name', 'providerId', 'kind', 'status', 'error'],
      )
    ) {
      return;
    }

    if (typeof item.name !== 'string' || item.name.length === 0) {
      addError(filePath, `${field}/name`, 'must be a non-empty string');
    } else if (seenNames.has(item.name)) {
      addError(filePath, `${field}/name`, `duplicate case name "${item.name}"`);
    } else {
      seenNames.add(item.name);
    }

    if (!PROVIDER_ERROR_IDS.includes(item.providerId)) {
      addError(filePath, `${field}/providerId`, `must be one of ${JSON.stringify(PROVIDER_ERROR_IDS)}`);
    }

    if (!PROVIDER_ERROR_KINDS.includes(item.kind)) {
      addError(filePath, `${field}/kind`, `must be one of ${JSON.stringify(PROVIDER_ERROR_KINDS)}`);
    } else if (item.kind === 'http') {
      httpCases += 1;
    } else if (item.kind === 'stream') {
      streamCases += 1;
    }

    let fixtureRequestId;
    if (item.fixture !== undefined) {
      if (typeof item.fixture !== 'string' || item.fixture.length === 0) {
        addError(filePath, `${field}/fixture`, 'must be a non-empty string');
      } else {
        const fixturePath = path.resolve(fixturesDir, item.fixture);
        const errorsDir = path.join(fixturesDir, 'errors');
        if (!fixturePath.startsWith(`${errorsDir}${path.sep}`)) {
          addError(filePath, `${field}/fixture`, 'must reference a file under fixtures/errors');
        } else if (!existsSync(fixturePath)) {
          addError(filePath, `${field}/fixture`, `references missing fixture "${item.fixture}"`);
        } else if (item.kind === 'http') {
          fixtureRequestId = validateProviderHttpErrorFixture(fixturePath, item.status, item.providerId);
        }
      }
    } else if (item.synthetic !== true) {
      addError(filePath, `${field}/fixture`, 'must reference a fixture or set synthetic to true');
    }

    if (item.synthetic !== undefined && typeof item.synthetic !== 'boolean') {
      addError(filePath, `${field}/synthetic`, 'must be a boolean');
    }

    if (item.status !== null && !Number.isInteger(item.status)) {
      addError(filePath, `${field}/status`, 'must be an integer or null');
    }
    if (item.kind === 'http' && item.status === null) {
      addError(filePath, `${field}/status`, 'must be an integer for an HTTP error');
    }
    if (item.kind === 'stream' && item.status === null) {
      addError(filePath, `${field}/status`, 'must be an integer for an in-stream error');
    }

    if (item.httpStatus !== undefined) {
      if (!Number.isInteger(item.httpStatus)) {
        addError(filePath, `${field}/httpStatus`, 'must be an integer');
      } else if (item.kind === 'stream' && item.httpStatus !== 200) {
        addError(filePath, `${field}/httpStatus`, 'must be 200 for an in-stream error');
      } else if (item.kind === 'http' && item.httpStatus !== item.status) {
        addError(filePath, `${field}/httpStatus`, 'must equal the case status for an HTTP error');
      }
    } else if (item.kind === 'stream') {
      addError(filePath, `${field}/httpStatus`, 'must be 200 for an in-stream error');
    }

    validateChatError(filePath, `${field}/error`, item.error);
    if (isPlainObject(item.error)) {
      if (item.error.status !== item.status) {
        addError(filePath, `${field}/error/status`, 'must match the case status');
      }
      if (item.error.providerId !== item.providerId) {
        addError(filePath, `${field}/error/providerId`, 'must match the case providerId');
      }
      if (typeof item.error.code === 'string') {
        seenCodes.add(item.error.code);
      }
      if (fixtureRequestId !== undefined && item.error.requestId !== fixtureRequestId) {
        addError(
          filePath,
          `${field}/error/requestId`,
          `must equal the fixture request ID ${JSON.stringify(fixtureRequestId)}`,
        );
      }
    }
  });

  if (httpCases === 0) {
    addError(filePath, '/cases', 'must contain at least one HTTP error case');
  }
  if (streamCases === 0) {
    addError(filePath, '/cases', 'must contain at least one in-stream error case');
  }
  for (const code of REQUIRED_PROVIDER_ERROR_CODES) {
    if (!seenCodes.has(code)) {
      addError(filePath, '/cases', `missing required error code "${code}"`);
    }
  }
}

function validateAnthropicThinkingExpected(filePath, document) {
  if (!checkObjectShape(filePath, '', document, ['source', 'cases'], ['source', 'cases'])) {
    return;
  }

  if (document.source !== 'anthropic-thinking') {
    addError(filePath, '/source', 'must be "anthropic-thinking"');
  }

  if (!Array.isArray(document.cases)) {
    addError(filePath, '/cases', 'must be an array');
    return;
  }
  if (document.cases.length === 0) {
    addError(filePath, '/cases', 'must contain at least one case');
  }

  const seenNames = new Set();
  document.cases.forEach((item, index) => {
    const field = `/cases/${index}`;
    if (
      !checkObjectShape(
        filePath,
        field,
        item,
        ['name', 'rawEvents', 'events', 'text', 'reasoning'],
        ['name', 'rawEvents', 'events', 'text', 'reasoning'],
      )
    ) {
      return;
    }

    if (typeof item.name !== 'string' || item.name.length === 0) {
      addError(filePath, `${field}/name`, 'must be a non-empty string');
    } else if (seenNames.has(item.name)) {
      addError(filePath, `${field}/name`, `duplicate case name "${item.name}"`);
    } else {
      seenNames.add(item.name);
    }

    if (!Array.isArray(item.rawEvents) || item.rawEvents.length === 0) {
      addError(filePath, `${field}/rawEvents`, 'must be a non-empty array');
    } else {
      item.rawEvents.forEach((rawEvent, rawIndex) => {
        const rawField = `${field}/rawEvents/${rawIndex}`;
        if (!isPlainObject(rawEvent)) {
          addError(filePath, rawField, 'must be an object');
          return;
        }
        if (!ANTHROPIC_RAW_EVENT_TYPES.includes(rawEvent.type)) {
          addError(filePath, `${rawField}/type`, `must be one of ${JSON.stringify(ANTHROPIC_RAW_EVENT_TYPES)}`);
        }
      });
    }

    if (!Array.isArray(item.events) || item.events.length === 0) {
      addError(filePath, `${field}/events`, 'must be a non-empty array');
    } else {
      item.events.forEach((event, eventIndex) => validateEvent(filePath, `${field}/events/${eventIndex}`, event));

      const text = item.events
        .filter((event) => isPlainObject(event) && event.type === 'text-delta' && typeof event.text === 'string')
        .map((event) => event.text)
        .join('');
      const reasoning = item.events
        .filter(
          (event) => isPlainObject(event) && event.type === 'reasoning-delta' && typeof event.text === 'string',
        )
        .map((event) => event.text)
        .join('');

      if (typeof item.text === 'string' && item.text !== text) {
        addError(filePath, `${field}/text`, `must equal the concatenated text-delta events (${JSON.stringify(text)})`);
      }
      if (typeof item.reasoning === 'string' && item.reasoning !== reasoning) {
        addError(
          filePath,
          `${field}/reasoning`,
          `must equal the concatenated reasoning-delta events (${JSON.stringify(reasoning)})`,
        );
      }
      if (!item.events.some((event) => isPlainObject(event) && event.type === 'reasoning-delta')) {
        addError(filePath, `${field}/events`, 'must contain at least one reasoning-delta event');
      }
      if (!item.events.some((event) => isPlainObject(event) && event.type === 'text-delta')) {
        addError(filePath, `${field}/events`, 'must contain at least one text-delta event');
      }
    }

    if (typeof item.text !== 'string') {
      addError(filePath, `${field}/text`, 'must be a string');
    }
    if (typeof item.reasoning !== 'string') {
      addError(filePath, `${field}/reasoning`, 'must be a string');
    }
  });
}

function validateGeminiFinishReasonsExpected(filePath, document) {
  if (!checkObjectShape(filePath, '', document, ['source', 'cases'], ['source', 'cases'])) {
    return;
  }

  if (document.source !== 'gemini-finish-reasons') {
    addError(filePath, '/source', 'must be "gemini-finish-reasons"');
  }

  if (!Array.isArray(document.cases)) {
    addError(filePath, '/cases', 'must be an array');
    return;
  }
  if (document.cases.length === 0) {
    addError(filePath, '/cases', 'must contain at least one case');
  }

  const seenNames = new Set();
  const seenReasons = new Set();
  document.cases.forEach((item, index) => {
    const field = `/cases/${index}`;
    if (
      !checkObjectShape(
        filePath,
        field,
        item,
        ['name', 'finishReason', 'expected'],
        ['name', 'finishReason', 'expected'],
      )
    ) {
      return;
    }

    if (typeof item.name !== 'string' || item.name.length === 0) {
      addError(filePath, `${field}/name`, 'must be a non-empty string');
    } else if (seenNames.has(item.name)) {
      addError(filePath, `${field}/name`, `duplicate case name "${item.name}"`);
    } else {
      seenNames.add(item.name);
    }

    if (!GEMINI_FINISH_REASONS.includes(item.finishReason)) {
      addError(filePath, `${field}/finishReason`, `must be one of ${JSON.stringify(GEMINI_FINISH_REASONS)}`);
    } else {
      seenReasons.add(item.finishReason);
    }

    if (!FINISH_REASONS.includes(item.expected)) {
      addError(filePath, `${field}/expected`, `must be one of ${JSON.stringify(FINISH_REASONS)}`);
    }

    const mapped = GEMINI_FINISH_MAPPING[item.finishReason];
    if (mapped !== undefined && item.expected !== mapped) {
      addError(filePath, `${field}/expected`, `must be "${mapped}" for finishReason "${item.finishReason}"`);
    }
  });

  for (const reason of ['STOP', 'MAX_TOKENS', 'SAFETY', 'RECITATION']) {
    if (!seenReasons.has(reason)) {
      addError(filePath, '/cases', `missing required finishReason "${reason}"`);
    }
  }
}

function validateStringArrayField(filePath, field, value, allowedValues) {
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    addError(filePath, field, 'must be an array of strings');
    return false;
  }
  if (allowedValues !== undefined) {
    for (const item of value) {
      if (!allowedValues.includes(item)) {
        addError(filePath, field, `must contain only ${JSON.stringify(allowedValues)}`);
        return false;
      }
    }
  }
  return true;
}

function validateCatalogEntry(filePath, field, entry) {
  if (!checkObjectShape(filePath, field, entry, CATALOG_ENTRY_FIELDS, CATALOG_ENTRY_FIELDS)) {
    return false;
  }
  if (typeof entry.remoteId !== 'string' || entry.remoteId.length === 0) {
    addError(filePath, `${field}/remoteId`, 'must be a non-empty string');
  }
  if (typeof entry.label !== 'string' || entry.label.length === 0) {
    addError(filePath, `${field}/label`, 'must be a non-empty string');
  }
  for (const key of ['contextWindow', 'maxOutputTokens']) {
    const value = entry[key];
    if (value !== null && (!Number.isInteger(value) || value < 1)) {
      addError(filePath, `${field}/${key}`, 'must be a positive integer or null');
    }
  }
  validateStringArrayField(filePath, `${field}/inputModalities`, entry.inputModalities, [
    'text',
    'image',
    'file',
    'audio',
    'video',
  ]);
  validateStringArrayField(filePath, `${field}/outputModalities`, entry.outputModalities, [
    'text',
    'image',
    'audio',
  ]);
  validateStringArrayField(filePath, `${field}/capabilities`, entry.capabilities, [
    'text',
    'vision',
    'files',
    'tools',
    'reasoning',
    'streaming',
  ]);
  validateStringArrayField(filePath, `${field}/supportedParameters`, entry.supportedParameters);
  if (entry.pricing !== null && !isPlainObject(entry.pricing)) {
    addError(filePath, `${field}/pricing`, 'must be an object or null');
  }
  if (typeof entry.hidden !== 'boolean') {
    addError(filePath, `${field}/hidden`, 'must be a boolean');
  }
  return true;
}

function mapOpenAICatalog(raw, preset) {
  const items = Array.isArray(raw.data) ? raw.data : [];
  return items.map((item) => ({
    remoteId: item.id,
    label: item.id,
    contextWindow: null,
    maxOutputTokens: null,
    inputModalities: [...preset.inputModalities],
    outputModalities: [...preset.outputModalities],
    capabilities: [...preset.capabilities],
    supportedParameters: [...preset.supportedParameters],
    pricing: null,
    hidden: /embedding/i.test(item.id),
  }));
}

function mapAnthropicCatalog(raw, preset) {
  const items = Array.isArray(raw.data) ? raw.data : [];
  return items.map((item) => ({
    remoteId: item.id,
    label:
      typeof item.display_name === 'string' && item.display_name.length > 0
        ? item.display_name
        : item.id,
    contextWindow: null,
    maxOutputTokens: null,
    inputModalities: [...preset.inputModalities],
    outputModalities: [...preset.outputModalities],
    capabilities: [...preset.capabilities],
    supportedParameters: [...preset.supportedParameters],
    pricing: null,
    hidden: false,
  }));
}

function mapGeminiCatalog(raw, preset) {
  const items = Array.isArray(raw.models) ? raw.models : [];
  return items.map((item) => {
    const remoteId =
      typeof item.baseModelId === 'string' && item.baseModelId.length > 0
        ? item.baseModelId
        : String(item.name ?? '').replace(/^models\//, '');
    return {
      remoteId,
      label:
        typeof item.displayName === 'string' && item.displayName.length > 0
          ? item.displayName
          : remoteId,
      contextWindow: Number.isInteger(item.inputTokenLimit) ? item.inputTokenLimit : null,
      maxOutputTokens: Number.isInteger(item.outputTokenLimit) ? item.outputTokenLimit : null,
      inputModalities: [...preset.inputModalities],
      outputModalities: [...preset.outputModalities],
      capabilities: [...preset.capabilities],
      supportedParameters: [...preset.supportedParameters],
      pricing: null,
      hidden: false,
    };
  });
}

function mapOllamaCatalog(raw, preset) {
  const items = Array.isArray(raw.models) ? raw.models : [];
  return items.map((item) => {
    const remoteId =
      typeof item.model === 'string' && item.model.length > 0 ? item.model : item.name;
    return {
      remoteId,
      label: typeof item.name === 'string' && item.name.length > 0 ? item.name : remoteId,
      contextWindow: null,
      maxOutputTokens: null,
      inputModalities: [...preset.inputModalities],
      outputModalities: [...preset.outputModalities],
      capabilities: [...preset.capabilities],
      supportedParameters: [...preset.supportedParameters],
      pricing: null,
      hidden: /embed/i.test(remoteId),
    };
  });
}

function mapCatalog(providerId, raw, preset) {
  switch (providerId) {
    case 'openai':
      return mapOpenAICatalog(raw, preset);
    case 'anthropic':
      return mapAnthropicCatalog(raw, preset);
    case 'google':
      return mapGeminiCatalog(raw, preset);
    case 'ollama':
      return mapOllamaCatalog(raw, preset);
    default:
      return [];
  }
}

function validateCatalogPreset(filePath, field, preset) {
  if (
    !checkObjectShape(
      filePath,
      field,
      preset,
      ['inputModalities', 'outputModalities', 'capabilities', 'supportedParameters'],
      ['inputModalities', 'outputModalities', 'capabilities', 'supportedParameters'],
    )
  ) {
    return false;
  }
  validateStringArrayField(filePath, `${field}/inputModalities`, preset.inputModalities, [
    'text',
    'image',
    'file',
    'audio',
    'video',
  ]);
  validateStringArrayField(filePath, `${field}/outputModalities`, preset.outputModalities, [
    'text',
    'image',
    'audio',
  ]);
  validateStringArrayField(filePath, `${field}/capabilities`, preset.capabilities, [
    'text',
    'vision',
    'files',
    'tools',
    'reasoning',
    'streaming',
  ]);
  validateStringArrayField(filePath, `${field}/supportedParameters`, preset.supportedParameters);
  return true;
}

function validateCatalogsExpected(filePath, document) {
  if (!checkObjectShape(filePath, '', document, ['source', 'cases'], ['source', 'cases'])) {
    return;
  }
  if (document.source !== 'catalogs') {
    addError(filePath, '/source', 'must be "catalogs"');
  }
  if (!Array.isArray(document.cases)) {
    addError(filePath, '/cases', 'must be an array');
    return;
  }
  if (document.cases.length === 0) {
    addError(filePath, '/cases', 'must contain at least one case');
  }

  const seenNames = new Set();
  const seenProviders = new Set();
  document.cases.forEach((item, index) => {
    const field = `/cases/${index}`;
    if (
      !checkObjectShape(
        filePath,
        field,
        item,
        ['name', 'providerId', 'fixture', 'preset', 'expected'],
        ['name', 'providerId', 'fixture', 'preset', 'expected'],
      )
    ) {
      return;
    }

    if (typeof item.name !== 'string' || item.name.length === 0) {
      addError(filePath, `${field}/name`, 'must be a non-empty string');
    } else if (seenNames.has(item.name)) {
      addError(filePath, `${field}/name`, `duplicate case name "${item.name}"`);
    } else {
      seenNames.add(item.name);
    }

    if (!CATALOG_PROVIDER_IDS.includes(item.providerId)) {
      addError(filePath, `${field}/providerId`, `must be one of ${JSON.stringify(CATALOG_PROVIDER_IDS)}`);
      return;
    }
    seenProviders.add(item.providerId);

    if (typeof item.fixture !== 'string' || item.fixture.length === 0) {
      addError(filePath, `${field}/fixture`, 'must be a non-empty string');
      return;
    }
    const fixturePath = path.resolve(fixturesDir, item.fixture);
    const modelsDir = path.join(fixturesDir, 'models');
    if (!fixturePath.startsWith(`${modelsDir}${path.sep}`)) {
      addError(filePath, `${field}/fixture`, 'must reference a file under fixtures/models');
      return;
    }
    if (!existsSync(fixturePath)) {
      addError(filePath, `${field}/fixture`, `references missing fixture "${item.fixture}"`);
      return;
    }

    if (!validateCatalogPreset(filePath, `${field}/preset`, item.preset)) {
      return;
    }

    if (!Array.isArray(item.expected)) {
      addError(filePath, `${field}/expected`, 'must be an array');
      return;
    }
    if (item.expected.length === 0) {
      addError(filePath, `${field}/expected`, 'must contain at least one model');
    }
    item.expected.forEach((entry, entryIndex) =>
      validateCatalogEntry(filePath, `${field}/expected/${entryIndex}`, entry),
    );

    const raw = readJson(fixturePath);
    if (raw === undefined) {
      return;
    }
    const mapped = mapCatalog(item.providerId, raw, item.preset);
    if (mapped.length !== item.expected.length) {
      addError(
        filePath,
        `${field}/expected`,
        `must contain ${mapped.length} entries to match the raw fixture`,
      );
      return;
    }
    mapped.forEach((expectedEntry, entryIndex) => {
      const actual = item.expected[entryIndex];
      if (!isPlainObject(actual)) {
        return;
      }
      for (const key of CATALOG_ENTRY_FIELDS) {
        if (!isDeepStrictEqual(actual[key], expectedEntry[key])) {
          addError(
            filePath,
            `${field}/expected/${entryIndex}/${key}`,
            `must equal ${JSON.stringify(expectedEntry[key])} per the catalog mapping rule`,
          );
        }
      }
    });
  });

  for (const providerId of CATALOG_PROVIDER_IDS) {
    if (!seenProviders.has(providerId)) {
      addError(filePath, '/cases', `missing catalog case for provider "${providerId}"`);
    }
  }
}

function inferCapabilities(inputModalities, outputModalities, supportedParameters, presetCapabilities) {
  const capabilities = new Set();
  if (outputModalities.includes('text')) {
    capabilities.add('text');
  }
  if (inputModalities.includes('image')) {
    capabilities.add('vision');
  }
  if (inputModalities.includes('file')) {
    capabilities.add('files');
  }
  if (supportedParameters.includes('tools')) {
    capabilities.add('tools');
  }
  if (supportedParameters.includes('reasoning')) {
    capabilities.add('reasoning');
  }
  if (presetCapabilities.includes('streaming') || supportedParameters.includes('stream')) {
    capabilities.add('streaming');
  }
  return CAPABILITY_ORDER.filter((capability) => capabilities.has(capability));
}

function validateCapabilityInferenceExpected(filePath, document) {
  if (!checkObjectShape(filePath, '', document, ['source', 'cases'], ['source', 'cases'])) {
    return;
  }
  if (document.source !== 'capability-inference') {
    addError(filePath, '/source', 'must be "capability-inference"');
  }
  if (!Array.isArray(document.cases)) {
    addError(filePath, '/cases', 'must be an array');
    return;
  }
  if (document.cases.length === 0) {
    addError(filePath, '/cases', 'must contain at least one case');
  }

  const seenNames = new Set();
  document.cases.forEach((item, index) => {
    const field = `/cases/${index}`;
    if (
      !checkObjectShape(
        filePath,
        field,
        item,
        ['name', 'preset', 'raw', 'expected'],
        ['name', 'preset', 'raw', 'expected'],
      )
    ) {
      return;
    }

    if (typeof item.name !== 'string' || item.name.length === 0) {
      addError(filePath, `${field}/name`, 'must be a non-empty string');
    } else if (seenNames.has(item.name)) {
      addError(filePath, `${field}/name`, `duplicate case name "${item.name}"`);
    } else {
      seenNames.add(item.name);
    }

    if (
      !checkObjectShape(
        filePath,
        `${field}/preset`,
        item.preset,
        ['id', 'kind', 'inputModalities', 'outputModalities', 'capabilities', 'supportedParameters'],
        ['id', 'kind', 'inputModalities', 'outputModalities', 'capabilities', 'supportedParameters'],
      )
    ) {
      return;
    }
    validateStringArrayField(filePath, `${field}/preset/inputModalities`, item.preset.inputModalities);
    validateStringArrayField(filePath, `${field}/preset/outputModalities`, item.preset.outputModalities);
    validateStringArrayField(filePath, `${field}/preset/capabilities`, item.preset.capabilities);
    validateStringArrayField(filePath, `${field}/preset/supportedParameters`, item.preset.supportedParameters);

    if (!isPlainObject(item.raw)) {
      addError(filePath, `${field}/raw`, 'must be an object');
      return;
    }

    if (
      !checkObjectShape(
        filePath,
        `${field}/expected`,
        item.expected,
        CAPABILITY_INFERENCE_FIELDS,
        CAPABILITY_INFERENCE_FIELDS,
      )
    ) {
      return;
    }

    const inputModalities = Array.isArray(item.raw.inputModalities)
      ? item.raw.inputModalities
      : item.preset.inputModalities;
    const outputModalities = Array.isArray(item.raw.outputModalities)
      ? item.raw.outputModalities
      : item.preset.outputModalities;
    const supportedParameters = Array.isArray(item.raw.supportedParameters)
      ? item.raw.supportedParameters
      : item.preset.supportedParameters;
    const capabilities = Array.isArray(item.raw.capabilities)
      ? item.raw.capabilities
      : inferCapabilities(
          inputModalities,
          outputModalities,
          supportedParameters,
          item.preset.capabilities,
        );
    const contextWindow = Number.isInteger(item.raw.contextWindow) ? item.raw.contextWindow : null;
    const maxOutputTokens = Number.isInteger(item.raw.maxOutputTokens)
      ? item.raw.maxOutputTokens
      : null;

    const expected = {
      inputModalities,
      outputModalities,
      capabilities,
      supportedParameters,
      contextWindow,
      maxOutputTokens,
    };
    for (const key of CAPABILITY_INFERENCE_FIELDS) {
      if (!isDeepStrictEqual(item.expected[key], expected[key])) {
        addError(
          filePath,
          `${field}/expected/${key}`,
          `must equal ${JSON.stringify(expected[key])} per the capability inference rule`,
        );
      }
    }
  });
}

function computeFallbackSelection(presetDefaultModelId, catalog, fallbackModelIds) {
  if (catalog.length === 0) {
    if (fallbackModelIds.length > 0) {
      return {
        selectedModelId: fallbackModelIds[0],
        notice: `Discovery unavailable; using fallback model ${fallbackModelIds[0]}.`,
      };
    }
    return {
      selectedModelId: null,
      notice: 'Discovery unavailable; no fallback model is available.',
    };
  }

  const hasPresetDefault =
    presetDefaultModelId !== null &&
    catalog.some((model) => isPlainObject(model) && model.remoteId === presetDefaultModelId);
  if (hasPresetDefault) {
    return { selectedModelId: presetDefaultModelId, notice: null };
  }

  const candidates = catalog
    .filter(
      (model) =>
        isPlainObject(model) &&
        model.hidden === false &&
        Array.isArray(model.outputModalities) &&
        model.outputModalities.includes('text'),
    )
    .map((model) => model.remoteId)
    .sort();
  if (candidates.length > 0) {
    return {
      selectedModelId: candidates[0],
      notice: `Default model ${presetDefaultModelId ?? '(none)'} is unavailable; using ${candidates[0]}.`,
    };
  }
  return {
    selectedModelId: null,
    notice: `Default model ${presetDefaultModelId ?? '(none)'} is unavailable; no text model is available.`,
  };
}

function validateFallbackModelsExpected(filePath, document) {
  if (!checkObjectShape(filePath, '', document, ['source', 'presets', 'cases'], ['source', 'presets', 'cases'])) {
    return;
  }
  if (document.source !== 'fallback-models') {
    addError(filePath, '/source', 'must be "fallback-models"');
  }

  if (!Array.isArray(document.presets)) {
    addError(filePath, '/presets', 'must be an array');
  } else {
    const seenPresets = new Set();
    document.presets.forEach((preset, index) => {
      const field = `/presets/${index}`;
      if (!checkObjectShape(filePath, field, preset, ['providerId', 'fallbackModelIds'], ['providerId', 'fallbackModelIds'])) {
        return;
      }
      if (!FALLBACK_PRESET_IDS.includes(preset.providerId)) {
        addError(filePath, `${field}/providerId`, `must be one of ${JSON.stringify(FALLBACK_PRESET_IDS)}`);
      } else if (seenPresets.has(preset.providerId)) {
        addError(filePath, `${field}/providerId`, `duplicate preset "${preset.providerId}"`);
      } else {
        seenPresets.add(preset.providerId);
      }
      if (
        !Array.isArray(preset.fallbackModelIds) ||
        preset.fallbackModelIds.some((value) => typeof value !== 'string' || value.length === 0)
      ) {
        addError(filePath, `${field}/fallbackModelIds`, 'must be an array of non-empty strings');
      }
    });
    for (const providerId of FALLBACK_PRESET_IDS) {
      if (!seenPresets.has(providerId)) {
        addError(filePath, '/presets', `missing fallback list for provider "${providerId}"`);
      }
    }
  }

  if (!Array.isArray(document.cases)) {
    addError(filePath, '/cases', 'must be an array');
    return;
  }
  if (document.cases.length === 0) {
    addError(filePath, '/cases', 'must contain at least one case');
  }

  const seenNames = new Set();
  document.cases.forEach((item, index) => {
    const field = `/cases/${index}`;
    if (
      !checkObjectShape(
        filePath,
        field,
        item,
        ['name', 'providerId', 'presetDefaultModelId', 'fallbackModelIds', 'catalog', 'expected'],
        ['name', 'providerId', 'presetDefaultModelId', 'fallbackModelIds', 'catalog', 'expected'],
      )
    ) {
      return;
    }

    if (typeof item.name !== 'string' || item.name.length === 0) {
      addError(filePath, `${field}/name`, 'must be a non-empty string');
    } else if (seenNames.has(item.name)) {
      addError(filePath, `${field}/name`, `duplicate case name "${item.name}"`);
    } else {
      seenNames.add(item.name);
    }

    if (!FALLBACK_PRESET_IDS.includes(item.providerId)) {
      addError(filePath, `${field}/providerId`, `must be one of ${JSON.stringify(FALLBACK_PRESET_IDS)}`);
    }
    if (
      item.presetDefaultModelId !== null &&
      (typeof item.presetDefaultModelId !== 'string' || item.presetDefaultModelId.length === 0)
    ) {
      addError(filePath, `${field}/presetDefaultModelId`, 'must be a non-empty string or null');
    }
    if (
      !Array.isArray(item.fallbackModelIds) ||
      item.fallbackModelIds.some((value) => typeof value !== 'string' || value.length === 0)
    ) {
      addError(filePath, `${field}/fallbackModelIds`, 'must be an array of non-empty strings');
    }
    if (!Array.isArray(item.catalog)) {
      addError(filePath, `${field}/catalog`, 'must be an array');
      return;
    }

    const seenRemoteIds = new Set();
    item.catalog.forEach((model, modelIndex) => {
      const modelField = `${field}/catalog/${modelIndex}`;
      if (
        !checkObjectShape(
          filePath,
          modelField,
          model,
          ['remoteId', 'hidden', 'outputModalities'],
          ['remoteId', 'hidden', 'outputModalities'],
        )
      ) {
        return;
      }
      if (typeof model.remoteId !== 'string' || model.remoteId.length === 0) {
        addError(filePath, `${modelField}/remoteId`, 'must be a non-empty string');
      } else if (seenRemoteIds.has(model.remoteId)) {
        addError(filePath, `${modelField}/remoteId`, `duplicate remoteId "${model.remoteId}"`);
      } else {
        seenRemoteIds.add(model.remoteId);
      }
      if (typeof model.hidden !== 'boolean') {
        addError(filePath, `${modelField}/hidden`, 'must be a boolean');
      }
      validateStringArrayField(filePath, `${modelField}/outputModalities`, model.outputModalities);
    });

    if (
      !checkObjectShape(
        filePath,
        `${field}/expected`,
        item.expected,
        ['selectedModelId', 'notice'],
        ['selectedModelId', 'notice'],
      )
    ) {
      return;
    }
    if (
      item.expected.selectedModelId !== null &&
      (typeof item.expected.selectedModelId !== 'string' || item.expected.selectedModelId.length === 0)
    ) {
      addError(filePath, `${field}/expected/selectedModelId`, 'must be a non-empty string or null');
    }
    if (
      item.expected.notice !== null &&
      (typeof item.expected.notice !== 'string' || item.expected.notice.length === 0)
    ) {
      addError(filePath, `${field}/expected/notice`, 'must be a non-empty string or null');
    }

    const selection = computeFallbackSelection(
      item.presetDefaultModelId,
      item.catalog,
      Array.isArray(item.fallbackModelIds) ? item.fallbackModelIds : [],
    );
    if (item.expected.selectedModelId !== selection.selectedModelId) {
      addError(
        filePath,
        `${field}/expected/selectedModelId`,
        `must be ${JSON.stringify(selection.selectedModelId)} per the fallback model rule`,
      );
    }
    if (item.expected.notice !== selection.notice) {
      addError(
        filePath,
        `${field}/expected/notice`,
        `must be ${JSON.stringify(selection.notice)} per the fallback model rule`,
      );
    }
  });
}

function isBalancedBraces(source) {
  let depth = 0;
  for (const character of source) {
    if (character === '{') {
      depth += 1;
    } else if (character === '}') {
      depth -= 1;
      if (depth < 0) {
        return false;
      }
    }
  }
  return depth === 0;
}

function parseMath(input) {
  const segments = [];
  let index = 0;
  let textStart = 0;

  const pushText = (end) => {
    if (end > textStart) {
      segments.push({ type: 'text', text: input.slice(textStart, end) });
    }
  };
  const rawFallback = () => ({ segments: [{ type: 'text', text: input }], fallback: 'raw' });

  while (index < input.length) {
    let open = null;
    let close = null;
    let display = false;
    if (input.startsWith('$$', index)) {
      open = '$$';
      close = '$$';
      display = true;
    } else if (input.startsWith('\\[', index)) {
      open = '\\[';
      close = '\\]';
      display = true;
    } else if (input.startsWith('\\(', index)) {
      open = '\\(';
      close = '\\)';
      display = false;
    } else if (input[index] === '$') {
      open = '$';
      close = '$';
      display = false;
    }

    if (open === null) {
      index += 1;
      continue;
    }

    const end = input.indexOf(close, index + open.length);
    if (end === -1) {
      return rawFallback();
    }
    pushText(index);
    const source = input.slice(index + open.length, end).trim();
    if (source.length === 0 || !isBalancedBraces(source)) {
      return rawFallback();
    }
    segments.push({ type: 'math', display, source });
    index = end + close.length;
    textStart = index;
  }

  pushText(input.length);
  return { segments, fallback: null };
}

function validateMathSegment(filePath, field, segment) {
  if (!isPlainObject(segment)) {
    addError(filePath, field, 'must be an object');
    return;
  }
  if (!MATH_SEGMENT_TYPES.includes(segment.type)) {
    addError(filePath, `${field}/type`, `must be one of ${JSON.stringify(MATH_SEGMENT_TYPES)}`);
    return;
  }
  if (segment.type === 'text') {
    if (!checkObjectShape(filePath, field, segment, ['type', 'text'], ['type', 'text'])) {
      return;
    }
    if (typeof segment.text !== 'string') {
      addError(filePath, `${field}/text`, 'must be a string');
    }
  } else {
    if (!checkObjectShape(filePath, field, segment, ['type', 'display', 'source'], ['type', 'display', 'source'])) {
      return;
    }
    if (typeof segment.display !== 'boolean') {
      addError(filePath, `${field}/display`, 'must be a boolean');
    }
    if (typeof segment.source !== 'string' || segment.source.length === 0) {
      addError(filePath, `${field}/source`, 'must be a non-empty string');
    }
  }
}

function validateMathExpected(filePath, document) {
  if (!checkObjectShape(filePath, '', document, ['source', 'cases'], ['source', 'cases'])) {
    return;
  }
  if (document.source !== 'math') {
    addError(filePath, '/source', 'must be "math"');
  }
  if (!Array.isArray(document.cases)) {
    addError(filePath, '/cases', 'must be an array');
    return;
  }
  if (document.cases.length === 0) {
    addError(filePath, '/cases', 'must contain at least one case');
  }

  const seenNames = new Set();
  document.cases.forEach((item, index) => {
    const field = `/cases/${index}`;
    if (
      !checkObjectShape(
        filePath,
        field,
        item,
        ['name', 'input', 'valid', 'expected'],
        ['name', 'input', 'valid', 'expected'],
      )
    ) {
      return;
    }

    if (typeof item.name !== 'string' || item.name.length === 0) {
      addError(filePath, `${field}/name`, 'must be a non-empty string');
    } else if (seenNames.has(item.name)) {
      addError(filePath, `${field}/name`, `duplicate case name "${item.name}"`);
    } else {
      seenNames.add(item.name);
    }

    if (typeof item.input !== 'string') {
      addError(filePath, `${field}/input`, 'must be a string');
      return;
    }
    if (typeof item.valid !== 'boolean') {
      addError(filePath, `${field}/valid`, 'must be a boolean');
    }

    if (
      !checkObjectShape(
        filePath,
        `${field}/expected`,
        item.expected,
        ['segments', 'fallback'],
        ['segments', 'fallback'],
      )
    ) {
      return;
    }
    if (!Array.isArray(item.expected.segments) || item.expected.segments.length === 0) {
      addError(filePath, `${field}/expected/segments`, 'must be a non-empty array');
      return;
    }
    item.expected.segments.forEach((segment, segmentIndex) =>
      validateMathSegment(filePath, `${field}/expected/segments/${segmentIndex}`, segment),
    );
    if (item.expected.fallback !== null && item.expected.fallback !== 'raw') {
      addError(filePath, `${field}/expected/fallback`, 'must be "raw" or null');
    }

    const parsed = parseMath(item.input);
    if (item.valid === true) {
      if (!isDeepStrictEqual(item.expected.segments, parsed.segments)) {
        addError(
          filePath,
          `${field}/expected/segments`,
          `must equal ${JSON.stringify(parsed.segments)} per the math parsing rule`,
        );
      }
      if (item.expected.fallback !== parsed.fallback) {
        addError(
          filePath,
          `${field}/expected/fallback`,
          `must be ${JSON.stringify(parsed.fallback)} per the math parsing rule`,
        );
      }
    } else if (item.valid === false) {
      const rawSegments = [{ type: 'text', text: item.input }];
      if (!isDeepStrictEqual(item.expected.segments, rawSegments)) {
        addError(
          filePath,
          `${field}/expected/segments`,
          'must be one raw text segment equal to the input for invalid or incomplete math',
        );
      }
      if (item.expected.fallback !== 'raw') {
        addError(filePath, `${field}/expected/fallback`, 'must be "raw" for invalid or incomplete math');
      }
    }
  });
}

function validateExportExpected(filePath, document, validateExportSchema) {
  if (validateExportSchema) {
    const valid = validateExportSchema(document);
    if (!valid) {
      reportAjvErrors(filePath, validateExportSchema);
    }
  }

  if (!isPlainObject(document)) {
    return;
  }

  const conversations = Array.isArray(document.conversations) ? document.conversations : [];
  const messages = Array.isArray(document.messages) ? document.messages : [];
  const attachments = Array.isArray(document.attachments) ? document.attachments : [];
  const providers = Array.isArray(document.providers) ? document.providers : [];
  const models = Array.isArray(document.models) ? document.models : [];

  const conversationIds = new Set();
  conversations.forEach((conversation, index) => {
    if (!isPlainObject(conversation)) {
      return;
    }
    if (typeof conversation.id === 'string') {
      if (conversationIds.has(conversation.id)) {
        addError(filePath, `/conversations/${index}/id`, `duplicate conversation id "${conversation.id}"`);
      } else {
        conversationIds.add(conversation.id);
      }
    }
  });

  const providerIds = new Set();
  providers.forEach((provider, index) => {
    if (!isPlainObject(provider)) {
      return;
    }
    if (typeof provider.id === 'string') {
      if (providerIds.has(provider.id)) {
        addError(filePath, `/providers/${index}/id`, `duplicate provider id "${provider.id}"`);
      } else {
        providerIds.add(provider.id);
      }
    }
  });

  const modelIds = new Set();
  const modelLabels = new Map();
  models.forEach((model, index) => {
    if (!isPlainObject(model)) {
      return;
    }
    if (typeof model.id === 'string') {
      if (modelIds.has(model.id)) {
        addError(filePath, `/models/${index}/id`, `duplicate model id "${model.id}"`);
      } else {
        modelIds.add(model.id);
        modelLabels.set(model.id, model.label);
      }
    }
    if (typeof model.providerId === 'string' && !providerIds.has(model.providerId)) {
      addError(filePath, `/models/${index}/providerId`, `references unknown provider "${model.providerId}"`);
    }
  });

  const attachmentIds = new Set();
  attachments.forEach((attachment, index) => {
    if (!isPlainObject(attachment)) {
      return;
    }
    if (typeof attachment.id === 'string') {
      if (attachmentIds.has(attachment.id)) {
        addError(filePath, `/attachments/${index}/id`, `duplicate attachment id "${attachment.id}"`);
      } else {
        attachmentIds.add(attachment.id);
      }
    }
    if (attachment.dataBase64 !== undefined) {
      if (
        typeof attachment.dataBase64 !== 'string' ||
        attachment.dataBase64.length === 0 ||
        attachment.dataBase64.length % 4 !== 0 ||
        !BASE64_PATTERN.test(attachment.dataBase64)
      ) {
        addError(filePath, `/attachments/${index}/dataBase64`, 'must be a non-empty base64 string');
      }
    }
  });

  const sequencesByConversation = new Map();
  messages.forEach((message, index) => {
    if (!isPlainObject(message)) {
      return;
    }
    if (typeof message.conversationId === 'string' && !conversationIds.has(message.conversationId)) {
      addError(
        filePath,
        `/messages/${index}/conversationId`,
        `references unknown conversation "${message.conversationId}"`,
      );
    }
    if (typeof message.conversationId === 'string' && Number.isInteger(message.sequence)) {
      const sequences = sequencesByConversation.get(message.conversationId) ?? [];
      sequences.push(message.sequence);
      sequencesByConversation.set(message.conversationId, sequences);
    }
    if (Array.isArray(message.parts)) {
      message.parts.forEach((part, partIndex) => {
        if (
          isPlainObject(part) &&
          (part.type === 'image' || part.type === 'file') &&
          typeof part.attachmentId === 'string' &&
          !attachmentIds.has(part.attachmentId)
        ) {
          addError(
            filePath,
            `/messages/${index}/parts/${partIndex}/attachmentId`,
            `references unknown attachment "${part.attachmentId}"`,
          );
        }
      });
    }
    if (typeof message.modelId === 'string') {
      if (!modelIds.has(message.modelId)) {
        addError(filePath, `/messages/${index}/modelId`, `references unknown model "${message.modelId}"`);
      } else if (message.modelLabel !== modelLabels.get(message.modelId)) {
        addError(
          filePath,
          `/messages/${index}/modelLabel`,
          `must equal the model snapshot label ${JSON.stringify(modelLabels.get(message.modelId))}`,
        );
      }
    }
  });

  for (const [conversationId, sequences] of sequencesByConversation) {
    const sorted = [...sequences].sort((left, right) => left - right);
    const expected = sorted.map((_, index) => index);
    if (!isDeepStrictEqual(sorted, expected)) {
      addError(
        filePath,
        `/messages`,
        `conversation "${conversationId}" sequences must be contiguous from 0`,
      );
    }
  }

  if (isPlainObject(document.settings)) {
    if (
      document.settings.defaultProviderId !== null &&
      typeof document.settings.defaultProviderId === 'string' &&
      !providerIds.has(document.settings.defaultProviderId)
    ) {
      addError(
        filePath,
        '/settings/defaultProviderId',
        `references unknown provider "${document.settings.defaultProviderId}"`,
      );
    }
    if (
      document.settings.defaultModelId !== null &&
      typeof document.settings.defaultModelId === 'string' &&
      !modelIds.has(document.settings.defaultModelId)
    ) {
      addError(
        filePath,
        '/settings/defaultModelId',
        `references unknown model "${document.settings.defaultModelId}"`,
      );
    }
  }
}

function validateNormalizedRequest(filePath, field, request) {
  if (
    !checkObjectShape(
      filePath,
      field,
      request,
      ['model', 'system', 'messages', 'params'],
      ['model', 'system', 'messages', 'params'],
    )
  ) {
    return false;
  }

  let valid = true;
  if (typeof request.model !== 'string' || request.model.length === 0) {
    addError(filePath, `${field}/model`, 'must be a non-empty string');
    valid = false;
  }
  if (request.system !== null && typeof request.system !== 'string') {
    addError(filePath, `${field}/system`, 'must be a string or null');
    valid = false;
  }

  if (!Array.isArray(request.messages)) {
    addError(filePath, `${field}/messages`, 'must be an array');
    valid = false;
  } else if (request.messages.length === 0) {
    addError(filePath, `${field}/messages`, 'must contain at least one message');
    valid = false;
  } else {
    request.messages.forEach((message, index) => {
      const messageField = `${field}/messages/${index}`;
      if (!checkObjectShape(filePath, messageField, message, ['role', 'parts'], ['role', 'parts'])) {
        valid = false;
        return;
      }
      if (!MESSAGE_ROLES.includes(message.role)) {
        addError(filePath, `${messageField}/role`, `must be one of ${JSON.stringify(MESSAGE_ROLES)}`);
        valid = false;
      }
      if (!Array.isArray(message.parts)) {
        addError(filePath, `${messageField}/parts`, 'must be an array');
        valid = false;
        return;
      }
      if (message.parts.length === 0) {
        addError(filePath, `${messageField}/parts`, 'must contain at least one part');
        valid = false;
      }
      message.parts.forEach((part, partIndex) => {
        const partField = `${messageField}/parts/${partIndex}`;
        if (!isPlainObject(part)) {
          addError(filePath, partField, 'must be an object');
          valid = false;
          return;
        }
        if (!PART_TYPES.includes(part.type)) {
          addError(filePath, `${partField}/type`, `must be one of ${JSON.stringify(PART_TYPES)}`);
          valid = false;
        }
        if (part.type === 'text' && typeof part.text !== 'string') {
          addError(filePath, `${partField}/text`, 'must be a string');
          valid = false;
        }
        if (part.type === 'image') {
          if (
            !checkObjectShape(
              filePath,
              partField,
              part,
              ['type', 'attachmentId', 'mimeType', 'alt'],
              ['type', 'attachmentId', 'mimeType', 'alt'],
            )
          ) {
            valid = false;
            return;
          }
          for (const key of ['attachmentId', 'mimeType', 'alt']) {
            if (typeof part[key] !== 'string') {
              addError(filePath, `${partField}/${key}`, 'must be a string');
              valid = false;
            }
          }
        }
        if (part.type === 'file') {
          if (
            !checkObjectShape(
              filePath,
              partField,
              part,
              ['type', 'attachmentId', 'mimeType', 'name'],
              ['type', 'attachmentId', 'mimeType', 'name'],
            )
          ) {
            valid = false;
            return;
          }
          for (const key of ['attachmentId', 'mimeType', 'name']) {
            if (typeof part[key] !== 'string' || part[key].length === 0) {
              addError(filePath, `${partField}/${key}`, 'must be a non-empty string');
              valid = false;
            }
          }
        }
      });
    });
  }

  const paramsField = `${field}/params`;
  if (
    !checkObjectShape(
      filePath,
      paramsField,
      request.params,
      ['temperature', 'topP', 'maxOutputTokens', 'stop', 'reasoningEffort', 'streaming', 'extra'],
      ['temperature', 'topP', 'maxOutputTokens', 'stop', 'reasoningEffort', 'streaming', 'extra'],
    )
  ) {
    return false;
  }

  if (
    request.params.temperature !== null &&
    (typeof request.params.temperature !== 'number' || !Number.isFinite(request.params.temperature))
  ) {
    addError(filePath, `${paramsField}/temperature`, 'must be a finite number or null');
    valid = false;
  }
  if (
    request.params.topP !== null &&
    (typeof request.params.topP !== 'number' || !Number.isFinite(request.params.topP))
  ) {
    addError(filePath, `${paramsField}/topP`, 'must be a finite number or null');
    valid = false;
  }
  if (
    request.params.maxOutputTokens !== null &&
    (!Number.isInteger(request.params.maxOutputTokens) || request.params.maxOutputTokens < 0)
  ) {
    addError(filePath, `${paramsField}/maxOutputTokens`, 'must be a non-negative integer or null');
    valid = false;
  }
  if (!Array.isArray(request.params.stop) || request.params.stop.some((value) => typeof value !== 'string')) {
    addError(filePath, `${paramsField}/stop`, 'must be an array of strings');
    valid = false;
  }
  if (
    request.params.reasoningEffort !== null &&
    !REASONING_EFFORTS.includes(request.params.reasoningEffort)
  ) {
    addError(
      filePath,
      `${paramsField}/reasoningEffort`,
      `must be one of ${JSON.stringify(REASONING_EFFORTS)} or null`,
    );
    valid = false;
  }
  if (typeof request.params.streaming !== 'boolean') {
    addError(filePath, `${paramsField}/streaming`, 'must be a boolean');
    valid = false;
  }
  if (!isPlainObject(request.params.extra)) {
    addError(filePath, `${paramsField}/extra`, 'must be an object');
    valid = false;
  }

  return valid;
}

function serializeRequestMessages(request) {
  const messages = [];
  if (request.system !== null) {
    messages.push({ role: 'system', content: request.system });
  }
  for (const message of request.messages) {
    const content = message.parts
      .filter((part) => part.type === 'text' && typeof part.text === 'string')
      .map((part) => part.text)
      .join('');
    messages.push({ role: message.role, content });
  }
  return messages;
}

function validateOpenRouterExtra(filePath, field, extra, allowProtected) {
  if (!checkObjectShape(filePath, field, extra, ['openrouter'], [])) {
    return undefined;
  }
  if (!Object.prototype.hasOwnProperty.call(extra, 'openrouter')) {
    return undefined;
  }

  const openrouter = extra.openrouter;
  if (!isPlainObject(openrouter)) {
    addError(filePath, `${field}/openrouter`, 'must be an object');
    return undefined;
  }

  const allowed = allowProtected
    ? [...EXTRA_OPENROUTER_FIELDS, ...PROTECTED_REQUEST_FIELDS]
    : EXTRA_OPENROUTER_FIELDS;
  checkObjectShape(filePath, `${field}/openrouter`, openrouter, allowed, []);

  if (!allowProtected) {
    for (const key of PROTECTED_REQUEST_FIELDS) {
      if (Object.prototype.hasOwnProperty.call(openrouter, key)) {
        addError(filePath, `${field}/openrouter/${key}`, 'protected field must be rejected, not serialized');
      }
    }
  }

  if (openrouter.provider !== undefined) {
    const providerField = `${field}/openrouter/provider`;
    if (
      checkObjectShape(
        filePath,
        providerField,
        openrouter.provider,
        ['allow_fallbacks', 'sort', 'data_collection', 'order', 'only', 'ignore', 'models', 'route'],
        [],
      )
    ) {
      const provider = openrouter.provider;
      if (provider.allow_fallbacks !== undefined && typeof provider.allow_fallbacks !== 'boolean') {
        addError(filePath, `${providerField}/allow_fallbacks`, 'must be a boolean');
      }
      if (provider.sort !== undefined && !ROUTING_SORTS.includes(provider.sort)) {
        addError(filePath, `${providerField}/sort`, `must be one of ${JSON.stringify(ROUTING_SORTS)}`);
      }
      if (
        provider.data_collection !== undefined &&
        !DATA_COLLECTION_VALUES.includes(provider.data_collection)
      ) {
        addError(
          filePath,
          `${providerField}/data_collection`,
          `must be one of ${JSON.stringify(DATA_COLLECTION_VALUES)}`,
        );
      }
      for (const key of ['order', 'only', 'ignore', 'models']) {
        if (
          provider[key] !== undefined &&
          (!Array.isArray(provider[key]) || provider[key].some((value) => typeof value !== 'string'))
        ) {
          addError(filePath, `${providerField}/${key}`, 'must be an array of strings');
        }
      }
      if (provider.route !== undefined && typeof provider.route !== 'string') {
        addError(filePath, `${providerField}/route`, 'must be a string');
      }
    }
  }

  if (
    openrouter.models !== undefined &&
    (!Array.isArray(openrouter.models) || openrouter.models.some((value) => typeof value !== 'string'))
  ) {
    addError(filePath, `${field}/openrouter/models`, 'must be an array of strings');
  }
  if (openrouter.route !== undefined && typeof openrouter.route !== 'string') {
    addError(filePath, `${field}/openrouter/route`, 'must be a string');
  }
  if (openrouter.reasoning !== undefined && !isPlainObject(openrouter.reasoning)) {
    addError(filePath, `${field}/openrouter/reasoning`, 'must be an object');
  }

  return openrouter;
}

function validateProviderExtra(filePath, field, extra, providerId, allowProtected) {
  const namespace = EXTRA_NAMESPACE_BY_PROVIDER[providerId];
  if (namespace === undefined) {
    addError(filePath, field, `unsupported provider "${providerId}"`);
    return undefined;
  }
  if (!checkObjectShape(filePath, field, extra, [namespace], [])) {
    return undefined;
  }
  if (!Object.prototype.hasOwnProperty.call(extra, namespace)) {
    return undefined;
  }

  if (providerId === 'openrouter') {
    return validateOpenRouterExtra(filePath, field, extra, allowProtected);
  }

  const value = extra[namespace];
  if (!isPlainObject(value)) {
    addError(filePath, `${field}/${namespace}`, 'must be an object');
    return undefined;
  }

  if (!allowProtected) {
    for (const key of PROTECTED_REQUEST_FIELDS) {
      if (Object.prototype.hasOwnProperty.call(value, key)) {
        addError(
          filePath,
          `${field}/${namespace}/${key}`,
          'protected field must be rejected, not serialized',
        );
      }
    }
  }

  return value;
}

function validateAttachmentsMap(filePath, field, attachments) {
  if (!checkObjectShape(filePath, field, attachments, Object.keys(attachments), [])) {
    return false;
  }
  for (const [id, attachment] of Object.entries(attachments)) {
    const attachmentField = `${field}/${id}`;
    if (id.length === 0) {
      addError(filePath, attachmentField, 'attachment id must be non-empty');
    }
    if (
      !checkObjectShape(
        filePath,
        attachmentField,
        attachment,
        ['mimeType', 'name', 'dataBase64'],
        ['mimeType', 'name', 'dataBase64'],
      )
    ) {
      continue;
    }
    for (const key of ['mimeType', 'name', 'dataBase64']) {
      if (typeof attachment[key] !== 'string' || attachment[key].length === 0) {
        addError(filePath, `${attachmentField}/${key}`, 'must be a non-empty string');
      }
    }
    if (
      typeof attachment.dataBase64 === 'string' &&
      (attachment.dataBase64.length % 4 !== 0 || !BASE64_PATTERN.test(attachment.dataBase64))
    ) {
      addError(filePath, `${attachmentField}/dataBase64`, 'must be a base64 string');
    }
  }
  return true;
}

function resolvedAttachment(attachments, attachmentId) {
  return isPlainObject(attachments) ? attachments[attachmentId] : undefined;
}

function attachmentText(attachment) {
  const content = Buffer.from(attachment.dataBase64, 'base64').toString('utf8');
  return `\n\n[File: ${attachment.name}]\n${content}`;
}

function serializeOpenAIContent(parts, attachments) {
  const hasImage = parts.some((part) => part.type === 'image');
  if (!hasImage) {
    return parts
      .map((part) => {
        if (part.type === 'text') {
          return part.text;
        }
        if (part.type === 'file') {
          const attachment = resolvedAttachment(attachments, part.attachmentId);
          return attachment === undefined ? '' : attachmentText(attachment);
        }
        return '';
      })
      .join('');
  }

  return parts
    .map((part) => {
      if (part.type === 'text') {
        return { type: 'text', text: part.text };
      }
      if (part.type === 'file') {
        const attachment = resolvedAttachment(attachments, part.attachmentId);
        return { type: 'text', text: attachment === undefined ? '' : attachmentText(attachment) };
      }
      if (part.type === 'image') {
        const attachment = resolvedAttachment(attachments, part.attachmentId);
        const data = attachment === undefined ? '' : attachment.dataBase64;
        return {
          type: 'image_url',
          image_url: { url: `data:${part.mimeType};base64,${data}` },
        };
      }
      return { type: 'text', text: '' };
    })
    .filter((part) => part.type !== 'text' || part.text.length > 0);
}

function serializeProviderRequest(request, attachments, providerId, supportedParameters) {
  const supported = new Set(supportedParameters);
  const namespace = EXTRA_NAMESPACE_BY_PROVIDER[providerId];
  const extra = isPlainObject(request.params.extra[namespace])
    ? request.params.extra[namespace]
    : {};

  if (providerId === 'openai') {
    const messages = [];
    if (request.system !== null) {
      messages.push({ role: 'system', content: request.system });
    }
    for (const message of request.messages) {
      messages.push({
        role: message.role,
        content: serializeOpenAIContent(message.parts, attachments),
      });
    }
    const body = {
      model: request.model,
      messages,
      stream: request.params.streaming,
      stream_options: { include_usage: true },
    };
    if (request.params.maxOutputTokens !== null && supported.has('max_tokens')) {
      body.max_tokens = request.params.maxOutputTokens;
    }
    if (request.params.temperature !== null && supported.has('temperature')) {
      body.temperature = request.params.temperature;
    }
    if (request.params.topP !== null && supported.has('top_p')) {
      body.top_p = request.params.topP;
    }
    if (request.params.stop.length > 0 && supported.has('stop')) {
      body.stop = request.params.stop;
    }
    if (request.params.reasoningEffort !== null && supported.has('reasoning')) {
      body.reasoning = { effort: request.params.reasoningEffort };
    }
    return { ...body, ...extra };
  }

  if (providerId === 'anthropic') {
    const messages = request.messages.map((message) => ({
      role: message.role,
      content: message.parts.map((part) => {
        if (part.type === 'text') {
          return { type: 'text', text: part.text };
        }
        if (part.type === 'file') {
          const attachment = resolvedAttachment(attachments, part.attachmentId);
          return { type: 'text', text: attachment === undefined ? '' : attachmentText(attachment) };
        }
        if (part.type === 'image') {
          const attachment = resolvedAttachment(attachments, part.attachmentId);
          return {
            type: 'image',
            source: {
              type: 'base64',
              media_type: part.mimeType,
              data: attachment === undefined ? '' : attachment.dataBase64,
            },
          };
        }
        return { type: 'text', text: '' };
      }),
    }));
    const body = {
      model: request.model,
      messages,
      max_tokens: request.params.maxOutputTokens ?? 1024,
      stream: request.params.streaming,
    };
    if (request.system !== null) {
      body.system = request.system;
    }
    if (request.params.temperature !== null && supported.has('temperature')) {
      body.temperature = request.params.temperature;
    }
    if (request.params.topP !== null && supported.has('top_p')) {
      body.top_p = request.params.topP;
    }
    if (request.params.stop.length > 0 && supported.has('stop')) {
      body.stop_sequences = request.params.stop;
    }
    return { ...body, ...extra };
  }

  if (providerId === 'google') {
    const contents = request.messages.map((message) => ({
      role: message.role === 'assistant' ? 'model' : 'user',
      parts: message.parts.map((part) => {
        if (part.type === 'text') {
          return { text: part.text };
        }
        if (part.type === 'file') {
          const attachment = resolvedAttachment(attachments, part.attachmentId);
          return { text: attachment === undefined ? '' : attachmentText(attachment) };
        }
        if (part.type === 'image') {
          const attachment = resolvedAttachment(attachments, part.attachmentId);
          return {
            inlineData: {
              mimeType: part.mimeType,
              data: attachment === undefined ? '' : attachment.dataBase64,
            },
          };
        }
        return { text: '' };
      }),
    }));
    const body = { contents };
    if (request.system !== null) {
      body.systemInstruction = { parts: [{ text: request.system }] };
    }
    const generationConfig = {};
    if (request.params.temperature !== null && supported.has('temperature')) {
      generationConfig.temperature = request.params.temperature;
    }
    if (request.params.topP !== null && supported.has('top_p')) {
      generationConfig.topP = request.params.topP;
    }
    if (request.params.maxOutputTokens !== null && supported.has('max_tokens')) {
      generationConfig.maxOutputTokens = request.params.maxOutputTokens;
    }
    if (request.params.stop.length > 0 && supported.has('stop')) {
      generationConfig.stopSequences = request.params.stop;
    }
    if (Object.keys(generationConfig).length > 0) {
      body.generationConfig = generationConfig;
    }
    return { ...body, ...extra };
  }

  if (providerId === 'ollama') {
    const messages = [];
    if (request.system !== null) {
      messages.push({ role: 'system', content: request.system });
    }
    for (const message of request.messages) {
      const content = message.parts
        .map((part) => {
          if (part.type === 'text') {
            return part.text;
          }
          if (part.type === 'file') {
            const attachment = resolvedAttachment(attachments, part.attachmentId);
            return attachment === undefined ? '' : attachmentText(attachment);
          }
          return '';
        })
        .join('');
      const images = message.parts
        .filter((part) => part.type === 'image')
        .map((part) => {
          const attachment = resolvedAttachment(attachments, part.attachmentId);
          return attachment === undefined ? '' : attachment.dataBase64;
        });
      const entry = { role: message.role, content };
      if (images.length > 0) {
        entry.images = images;
      }
      messages.push(entry);
    }
    const body = {
      model: request.model,
      messages,
      stream: request.params.streaming,
    };
    const options = {};
    if (request.params.temperature !== null && supported.has('temperature')) {
      options.temperature = request.params.temperature;
    }
    if (request.params.topP !== null && supported.has('top_p')) {
      options.top_p = request.params.topP;
    }
    if (request.params.maxOutputTokens !== null && supported.has('max_tokens')) {
      options.num_predict = request.params.maxOutputTokens;
    }
    if (request.params.stop.length > 0 && supported.has('stop')) {
      options.stop = request.params.stop;
    }
    if (Object.keys(options).length > 0) {
      body.options = options;
    }
    return { ...body, ...extra };
  }

  return {};
}

function validateProviderChatRequestBody(filePath, document) {
  const expectedBody = document.expected?.body;
  if (!isPlainObject(expectedBody)) {
    addError(filePath, '/expected/body', 'must be an object');
    return;
  }
  const actual = serializeProviderRequest(
    document.request,
    document.attachments ?? {},
    document.providerId,
    document.supportedParameters,
  );

  for (const key of Object.keys(actual)) {
    if (!Object.prototype.hasOwnProperty.call(expectedBody, key)) {
      addError(
        filePath,
        `/expected/body/${key}`,
        `must equal ${JSON.stringify(actual[key])} per the provider serialization rule`,
      );
    } else if (!isDeepStrictEqual(expectedBody[key], actual[key])) {
      addError(
        filePath,
        `/expected/body/${key}`,
        `must equal ${JSON.stringify(actual[key])} per the provider serialization rule`,
      );
    }
  }
  for (const key of Object.keys(expectedBody)) {
    if (!Object.prototype.hasOwnProperty.call(actual, key)) {
      addError(
        filePath,
        `/expected/body/${key}`,
        'must be omitted because the provider serialization rule does not emit it',
      );
    }
  }
}

function validateChatRequestBody(filePath, request, supportedParameters, expectedBody) {
  if (
    !checkObjectShape(
      filePath,
      '/expected/body',
      expectedBody,
      REQUEST_BODY_FIELDS,
      ['model', 'messages', 'stream', 'stream_options'],
    )
  ) {
    return;
  }

  if (expectedBody.model !== request.model) {
    addError(filePath, '/expected/body/model', `must equal the normalized request model "${request.model}"`);
  }

  const expectedMessages = serializeRequestMessages(request);
  if (!isDeepStrictEqual(expectedBody.messages, expectedMessages)) {
    addError(
      filePath,
      '/expected/body/messages',
      `must equal the serialized normalized messages ${JSON.stringify(expectedMessages)}`,
    );
  }

  if (expectedBody.stream !== request.params.streaming) {
    addError(filePath, '/expected/body/stream', `must equal params.streaming (${request.params.streaming})`);
  }
  if (!isDeepStrictEqual(expectedBody.stream_options, { include_usage: true })) {
    addError(filePath, '/expected/body/stream_options', 'must equal {"include_usage":true}');
  }

  const supported = new Set(supportedParameters);
  const optionalControls = [
    ['max_tokens', request.params.maxOutputTokens, 'max_tokens'],
    ['temperature', request.params.temperature, 'temperature'],
    ['top_p', request.params.topP, 'top_p'],
    ['stop', request.params.stop.length > 0 ? request.params.stop : null, 'stop'],
  ];
  for (const [bodyKey, value, supportedKey] of optionalControls) {
    if (value !== null && supported.has(supportedKey)) {
      if (!isDeepStrictEqual(expectedBody[bodyKey], value)) {
        addError(
          filePath,
          `/expected/body/${bodyKey}`,
          `must equal the normalized request value ${JSON.stringify(value)}`,
        );
      }
    } else if (Object.prototype.hasOwnProperty.call(expectedBody, bodyKey)) {
      addError(
        filePath,
        `/expected/body/${bodyKey}`,
        'must be omitted because the control is unsupported or unset',
      );
    }
  }

  const reasoning =
    request.params.reasoningEffort !== null && supported.has('reasoning')
      ? { effort: request.params.reasoningEffort }
      : null;
  if (reasoning !== null) {
    if (!isDeepStrictEqual(expectedBody.reasoning, reasoning)) {
      addError(filePath, '/expected/body/reasoning', `must equal ${JSON.stringify(reasoning)}`);
    }
  } else if (Object.prototype.hasOwnProperty.call(expectedBody, 'reasoning')) {
    addError(filePath, '/expected/body/reasoning', 'must be omitted because reasoning is unsupported or unset');
  }

  const openrouter = request.params.extra.openrouter;
  const provider = isPlainObject(openrouter) ? openrouter.provider : undefined;
  if (provider !== undefined) {
    if (!isDeepStrictEqual(expectedBody.provider, provider)) {
      addError(
        filePath,
        '/expected/body/provider',
        `must equal params.extra.openrouter.provider ${JSON.stringify(provider)}`,
      );
    }
  } else if (Object.prototype.hasOwnProperty.call(expectedBody, 'provider')) {
    addError(filePath, '/expected/body/provider', 'must be omitted because no routing preferences are configured');
  }

  const models = isPlainObject(openrouter) ? openrouter.models : undefined;
  if (models !== undefined) {
    if (!isDeepStrictEqual(expectedBody.models, models)) {
      addError(
        filePath,
        '/expected/body/models',
        `must equal params.extra.openrouter.models ${JSON.stringify(models)}`,
      );
    }
  } else if (Object.prototype.hasOwnProperty.call(expectedBody, 'models')) {
    addError(filePath, '/expected/body/models', 'must be omitted because no fallback models are configured');
  }

  const route = isPlainObject(openrouter) ? openrouter.route : undefined;
  if (route !== undefined) {
    if (expectedBody.route !== route) {
      addError(filePath, '/expected/body/route', `must equal params.extra.openrouter.route "${route}"`);
    }
  } else if (Object.prototype.hasOwnProperty.call(expectedBody, 'route')) {
    addError(filePath, '/expected/body/route', 'must be omitted because no route is configured');
  }

  const extraReasoning = isPlainObject(openrouter) ? openrouter.reasoning : undefined;
  if (extraReasoning !== undefined) {
    const merged = { ...(reasoning ?? {}), ...extraReasoning };
    if (!isDeepStrictEqual(expectedBody.reasoning, merged)) {
      addError(filePath, '/expected/body/reasoning', `must equal ${JSON.stringify(merged)}`);
    }
  }
}

function validateChatRequestVector(filePath, document) {
  if (
    !checkObjectShape(
      filePath,
      '',
      document,
      ['name', 'kind', 'providerId', 'request', 'attachments', 'supportedParameters', 'expected'],
      ['name', 'kind', 'providerId', 'request', 'supportedParameters', 'expected'],
    )
  ) {
    return;
  }
  if (!validateNormalizedRequest(filePath, '/request', document.request)) {
    return;
  }
  if (
    !Array.isArray(document.supportedParameters) ||
    document.supportedParameters.some((value) => typeof value !== 'string')
  ) {
    addError(filePath, '/supportedParameters', 'must be an array of strings');
    return;
  }
  if (document.attachments !== undefined) {
    if (!isPlainObject(document.attachments)) {
      addError(filePath, '/attachments', 'must be an object');
      return;
    }
    validateAttachmentsMap(filePath, '/attachments', document.attachments);
  }
  validateProviderExtra(
    filePath,
    '/request/params/extra',
    document.request.params.extra,
    document.providerId,
    false,
  );
  if (!checkObjectShape(filePath, '/expected', document.expected, ['body'], ['body'])) {
    return;
  }
  if (document.providerId === 'openrouter') {
    validateChatRequestBody(filePath, document.request, document.supportedParameters, document.expected.body);
  } else {
    validateProviderChatRequestBody(filePath, document);
  }
}

function validateProtectedFieldsVector(filePath, document) {
  if (
    !checkObjectShape(
      filePath,
      '',
      document,
      ['name', 'kind', 'providerId', 'request', 'supportedParameters', 'expected'],
      ['name', 'kind', 'providerId', 'request', 'supportedParameters', 'expected'],
    )
  ) {
    return;
  }
  if (!validateNormalizedRequest(filePath, '/request', document.request)) {
    return;
  }
  if (
    !Array.isArray(document.supportedParameters) ||
    document.supportedParameters.some((value) => typeof value !== 'string')
  ) {
    addError(filePath, '/supportedParameters', 'must be an array of strings');
  }

  const namespace = EXTRA_NAMESPACE_BY_PROVIDER[document.providerId];
  const providerExtra = validateProviderExtra(
    filePath,
    '/request/params/extra',
    document.request.params.extra,
    document.providerId,
    true,
  );
  const protectedFields = isPlainObject(providerExtra)
    ? PROTECTED_REQUEST_FIELDS.filter((key) => Object.prototype.hasOwnProperty.call(providerExtra, key)).sort()
    : [];
  if (protectedFields.length === 0) {
    addError(
      filePath,
      `/request/params/extra/${namespace ?? 'unknown'}`,
      `must contain at least one protected field (${PROTECTED_REQUEST_FIELDS.join(', ')})`,
    );
  }

  if (!checkObjectShape(filePath, '/expected', document.expected, ['error'], ['error'])) {
    return;
  }
  validateChatError(filePath, '/expected/error', document.expected.error);
  if (isPlainObject(document.expected.error)) {
    if (document.expected.error.code !== 'invalid_request') {
      addError(filePath, '/expected/error/code', 'must be "invalid_request"');
    }
    if (document.expected.error.providerId !== document.providerId) {
      addError(
        filePath,
        '/expected/error/providerId',
        `must be "${document.providerId}"`,
      );
    }
    if (document.expected.error.retryable !== false) {
      addError(filePath, '/expected/error/retryable', 'must be false');
    }
    const details = document.expected.error.details;
    if (!isPlainObject(details) || !Array.isArray(details.protectedFields)) {
      addError(
        filePath,
        '/expected/error/details/protectedFields',
        'must be an array of protected field names',
      );
    } else if (!isDeepStrictEqual(details.protectedFields, protectedFields)) {
      addError(
        filePath,
        '/expected/error/details/protectedFields',
        `must equal ${JSON.stringify(protectedFields)}`,
      );
    }
  }
}

function validateAttributionVector(filePath, document) {
  if (
    !checkObjectShape(
      filePath,
      '',
      document,
      ['name', 'kind', 'providerId', 'appConfig', 'expected'],
      ['name', 'kind', 'providerId', 'appConfig', 'expected'],
    )
  ) {
    return;
  }
  if (!checkObjectShape(filePath, '/appConfig', document.appConfig, ['name', 'origin'], ['name', 'origin'])) {
    return;
  }
  if (typeof document.appConfig.name !== 'string' || document.appConfig.name.length === 0) {
    addError(filePath, '/appConfig/name', 'must be a non-empty string');
  }
  if (typeof document.appConfig.origin !== 'string' || document.appConfig.origin.length === 0) {
    addError(filePath, '/appConfig/origin', 'must be a non-empty string');
  }
  if (!checkObjectShape(filePath, '/expected', document.expected, ['headers'], ['headers'])) {
    return;
  }
  if (
    !checkObjectShape(
      filePath,
      '/expected/headers',
      document.expected.headers,
      ['HTTP-Referer', 'X-Title'],
      ['HTTP-Referer', 'X-Title'],
    )
  ) {
    return;
  }
  if (document.expected.headers['HTTP-Referer'] !== document.appConfig.origin) {
    addError(filePath, '/expected/headers/HTTP-Referer', 'must equal appConfig.origin');
  }
  if (document.expected.headers['X-Title'] !== document.appConfig.name) {
    addError(filePath, '/expected/headers/X-Title', 'must equal appConfig.name');
  }

  const providersPath = path.join(contractsDir, 'providers.json');
  const providers = readJson(providersPath);
  if (Array.isArray(providers)) {
    const openrouter = providers.find((preset) => isPlainObject(preset) && preset.id === 'openrouter');
    if (!isPlainObject(openrouter) || !isPlainObject(openrouter.attributionHeaders)) {
      addError(
        providersPath,
        '/openrouter/attributionHeaders',
        'must define the OpenRouter attribution header mapping',
      );
    } else {
      if (openrouter.attributionHeaders['HTTP-Referer'] !== 'app.origin') {
        addError(providersPath, '/openrouter/attributionHeaders/HTTP-Referer', 'must map to "app.origin"');
      }
      if (openrouter.attributionHeaders['X-Title'] !== 'app.name') {
        addError(providersPath, '/openrouter/attributionHeaders/X-Title', 'must map to "app.name"');
      }
    }
  }
}

function validateDefaultModelVector(filePath, document) {
  if (
    !checkObjectShape(
      filePath,
      '',
      document,
      ['name', 'kind', 'providerId', 'cases'],
      ['name', 'kind', 'providerId', 'cases'],
    )
  ) {
    return;
  }
  if (!Array.isArray(document.cases)) {
    addError(filePath, '/cases', 'must be an array');
    return;
  }
  if (document.cases.length === 0) {
    addError(filePath, '/cases', 'must contain at least one case');
  }

  const seenNames = new Set();
  document.cases.forEach((item, index) => {
    const field = `/cases/${index}`;
    if (
      !checkObjectShape(
        filePath,
        field,
        item,
        ['name', 'presetDefaultModelId', 'catalog', 'expected'],
        ['name', 'presetDefaultModelId', 'catalog', 'expected'],
      )
    ) {
      return;
    }

    if (typeof item.name !== 'string' || item.name.length === 0) {
      addError(filePath, `${field}/name`, 'must be a non-empty string');
    } else if (seenNames.has(item.name)) {
      addError(filePath, `${field}/name`, `duplicate case name "${item.name}"`);
    } else {
      seenNames.add(item.name);
    }

    if (
      item.presetDefaultModelId !== null &&
      (typeof item.presetDefaultModelId !== 'string' || item.presetDefaultModelId.length === 0)
    ) {
      addError(filePath, `${field}/presetDefaultModelId`, 'must be a non-empty string or null');
    }

    if (!Array.isArray(item.catalog)) {
      addError(filePath, `${field}/catalog`, 'must be an array');
      return;
    }
    if (item.catalog.length === 0) {
      addError(filePath, `${field}/catalog`, 'must contain at least one model');
    }

    const seenRemoteIds = new Set();
    item.catalog.forEach((model, modelIndex) => {
      const modelField = `${field}/catalog/${modelIndex}`;
      if (
        !checkObjectShape(
          filePath,
          modelField,
          model,
          ['remoteId', 'hidden', 'outputModalities'],
          ['remoteId', 'hidden', 'outputModalities'],
        )
      ) {
        return;
      }
      if (typeof model.remoteId !== 'string' || model.remoteId.length === 0) {
        addError(filePath, `${modelField}/remoteId`, 'must be a non-empty string');
      } else if (seenRemoteIds.has(model.remoteId)) {
        addError(filePath, `${modelField}/remoteId`, `duplicate remoteId "${model.remoteId}"`);
      } else {
        seenRemoteIds.add(model.remoteId);
      }
      if (typeof model.hidden !== 'boolean') {
        addError(filePath, `${modelField}/hidden`, 'must be a boolean');
      }
      if (
        !Array.isArray(model.outputModalities) ||
        model.outputModalities.some((value) => typeof value !== 'string')
      ) {
        addError(filePath, `${modelField}/outputModalities`, 'must be an array of strings');
      }
    });

    if (
      !checkObjectShape(
        filePath,
        `${field}/expected`,
        item.expected,
        ['selectedModelId', 'notice'],
        ['selectedModelId', 'notice'],
      )
    ) {
      return;
    }
    if (
      item.expected.selectedModelId !== null &&
      (typeof item.expected.selectedModelId !== 'string' || item.expected.selectedModelId.length === 0)
    ) {
      addError(filePath, `${field}/expected/selectedModelId`, 'must be a non-empty string or null');
    }
    if (
      item.expected.notice !== null &&
      (typeof item.expected.notice !== 'string' || item.expected.notice.length === 0)
    ) {
      addError(filePath, `${field}/expected/notice`, 'must be a non-empty string or null');
    }

    const presetDefault = item.presetDefaultModelId;
    const hasPresetDefault =
      presetDefault !== null &&
      item.catalog.some((model) => isPlainObject(model) && model.remoteId === presetDefault);
    let selectedModelId = null;
    let notice = null;
    if (hasPresetDefault) {
      selectedModelId = presetDefault;
    } else {
      const candidates = item.catalog
        .filter(
          (model) =>
            isPlainObject(model) &&
            model.hidden === false &&
            Array.isArray(model.outputModalities) &&
            model.outputModalities.includes('text'),
        )
        .map((model) => model.remoteId)
        .sort();
      if (candidates.length > 0) {
        selectedModelId = candidates[0];
        notice = `Default model ${presetDefault ?? '(none)'} is unavailable; using ${selectedModelId}.`;
      } else {
        notice = `Default model ${presetDefault ?? '(none)'} is unavailable; no text model is available.`;
      }
    }

    if (item.expected.selectedModelId !== selectedModelId) {
      addError(
        filePath,
        `${field}/expected/selectedModelId`,
        `must be ${JSON.stringify(selectedModelId)} per the default-model fallback rule`,
      );
    }
    if (item.expected.notice !== notice) {
      addError(
        filePath,
        `${field}/expected/notice`,
        `must be ${JSON.stringify(notice)} per the default-model fallback rule`,
      );
    }
  });
}

function validateRequestVectors() {
  const requestFiles = walkFiles(requestsDir).filter((file) => file.endsWith('.json'));
  if (requestFiles.length === 0) {
    addError(requestsDir, '$', 'no request vectors found');
    return;
  }

  const seen = new Set();
  const seenProviders = new Set();
  for (const filePath of requestFiles) {
    const name = path.basename(filePath, '.json');
    seen.add(path.basename(filePath));
    const document = readJson(filePath);
    if (document === undefined) {
      continue;
    }
    if (!isPlainObject(document)) {
      addError(filePath, '$', 'must be an object');
      continue;
    }
    if (document.name !== name) {
      addError(filePath, '/name', `must equal the file name "${name}"`);
    }
    if (!REQUEST_KINDS.includes(document.kind)) {
      addError(filePath, '/kind', `must be one of ${JSON.stringify(REQUEST_KINDS)}`);
      continue;
    }
    if (!REQUEST_PROVIDER_IDS.includes(document.providerId)) {
      addError(filePath, '/providerId', `must be one of ${JSON.stringify(REQUEST_PROVIDER_IDS)}`);
    } else {
      seenProviders.add(document.providerId);
    }

    switch (document.kind) {
      case 'chat-request':
        validateChatRequestVector(filePath, document);
        break;
      case 'protected-fields':
        validateProtectedFieldsVector(filePath, document);
        break;
      case 'attribution':
        validateAttributionVector(filePath, document);
        break;
      case 'default-model':
        validateDefaultModelVector(filePath, document);
        break;
    }
  }

  for (const name of REQUIRED_REQUEST_FILES) {
    if (!seen.has(name)) {
      addError(path.join(requestsDir, name), '$', 'required request vector is missing');
    }
  }
  for (const providerId of REQUIRED_REQUEST_PROVIDERS) {
    if (!seenProviders.has(providerId)) {
      addError(requestsDir, '$', `missing request vectors for provider "${providerId}"`);
    }
  }
}

function validateExpectedVectors(validators) {
  const expectedFiles = walkFiles(expectedDir).filter((file) => file.endsWith('.json'));
  const seen = new Set();

  for (const filePath of expectedFiles) {
    const name = path.basename(filePath);
    if (!EXPECTED_FILES.has(name)) {
      addError(filePath, '$', `unexpected expected vector "${name}"`);
      continue;
    }

    seen.add(name);
    const document = readJson(filePath);
    if (document === undefined) {
      continue;
    }

    if (EXPECTED_STREAM_SOURCES.has(name)) {
      validateStreamExpected(filePath, document, EXPECTED_STREAM_SOURCES.get(name));
    } else if (name === EXPECTED_KEY_FILE) {
      validateKeyExpected(filePath, document);
    } else if (name === EXPECTED_ERRORS_FILE) {
      validateErrorsExpected(filePath, document);
    } else if (name === EXPECTED_PROVIDER_ERRORS_FILE) {
      validateProviderErrorsExpected(filePath, document);
    } else if (name === EXPECTED_ANTHROPIC_THINKING_FILE) {
      validateAnthropicThinkingExpected(filePath, document);
    } else if (name === EXPECTED_GEMINI_FINISH_REASONS_FILE) {
      validateGeminiFinishReasonsExpected(filePath, document);
    } else if (name === EXPECTED_CATALOGS_FILE) {
      validateCatalogsExpected(filePath, document);
    } else if (name === EXPECTED_CAPABILITY_INFERENCE_FILE) {
      validateCapabilityInferenceExpected(filePath, document);
    } else if (name === EXPECTED_FALLBACK_MODELS_FILE) {
      validateFallbackModelsExpected(filePath, document);
    } else if (name === EXPECTED_MATH_FILE) {
      validateMathExpected(filePath, document);
    } else if (name === EXPECTED_EXPORT_FILE) {
      validateExportExpected(filePath, document, validators.get('chat-export.schema.json'));
    }
  }

  for (const name of EXPECTED_FILES) {
    if (!seen.has(name)) {
      addError(path.join(expectedDir, name), '$', 'required expected vector is missing');
    }
  }

  const streamDir = path.join(fixturesDir, 'streams');
  const expectedSources = new Set(EXPECTED_STREAM_SOURCES.values());
  for (const filePath of walkFiles(streamDir).filter((file) => file.endsWith('.sse') || file.endsWith('.txt'))) {
    const name = path.basename(filePath);
    if (!expectedSources.has(name)) {
      addError(filePath, '$', 'stream fixture has no expected vector');
    }
  }

  for (const [name, source] of EXPECTED_STREAM_SOURCES) {
    if (!existsSync(path.join(streamDir, source))) {
      addError(path.join(expectedDir, name), '/source', `references missing fixture "${source}"`);
    }
  }

  if (!existsSync(path.join(fixturesDir, 'models', 'openrouter-key.json'))) {
    addError(
      path.join(expectedDir, EXPECTED_KEY_FILE),
      '/source',
      'references missing fixture "openrouter-key.json"',
    );
  }
}

function validateFixtureEncoding() {
  const fixtureFiles = walkFiles(fixturesDir);
  if (fixtureFiles.length === 0) {
    addError(fixturesDir, '$', 'no fixture files found');
    return;
  }

  for (const filePath of fixtureFiles) {
    let buffer;
    try {
      buffer = readFileSync(filePath);
    } catch (error) {
      addError(filePath, '$', `cannot read fixture: ${error.message}`);
      continue;
    }

    if (buffer.length === 0) {
      addError(filePath, '$', 'fixture is empty');
      continue;
    }

    let text;
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(buffer);
    } catch {
      addError(filePath, '$', 'fixture is not valid UTF-8');
      continue;
    }

    for (const pattern of SECRET_PATTERNS) {
      if (pattern.test(text)) {
        addError(filePath, '$', `fixture appears to contain a secret matching ${pattern}`);
      }
    }
  }
}

function splitTableRow(line) {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((cell) => cell.trim());
}

function isSeparatorRow(line) {
  const cells = splitTableRow(line);
  return cells.length > 0 && cells.every((cell) => /^:?-{3,}:?$/.test(cell));
}

function validateParity() {
  const filePath = path.join(contractsDir, 'parity.md');
  let text;
  try {
    text = readFileSync(filePath, 'utf8');
  } catch (error) {
    addError(filePath, '$', `cannot read parity file: ${error.message}`);
    return;
  }

  const tableLines = text.split(/\r?\n/).filter((line) => line.trim().startsWith('|'));
  if (tableLines.length === 0) {
    addError(filePath, '$', 'no markdown table found');
    return;
  }

  const header = splitTableRow(tableLines[0]);
  const columnsMatch =
    header.length === REQUIRED_PARITY_COLUMNS.length &&
    header.every((cell, index) => cell === REQUIRED_PARITY_COLUMNS[index]);
  if (!columnsMatch) {
    addError(
      filePath,
      'columns',
      `expected columns ${REQUIRED_PARITY_COLUMNS.join(', ')}, found ${header.join(', ') || '(none)'}`,
    );
  }

  const dataRows = tableLines.slice(1).filter((line) => !isSeparatorRow(line));
  const seenFeatures = new Set();
  for (const line of dataRows) {
    const cells = splitTableRow(line);
    if (cells.length !== REQUIRED_PARITY_COLUMNS.length) {
      addError(
        filePath,
        cells[0] || '$',
        `expected ${REQUIRED_PARITY_COLUMNS.length} columns, found ${cells.length}`,
      );
      continue;
    }

    const feature = cells[0];
    if (seenFeatures.has(feature)) {
      addError(filePath, feature, 'duplicate parity row');
    }
    seenFeatures.add(feature);
  }

  for (const feature of REQUIRED_PARITY_ROWS) {
    if (!seenFeatures.has(feature)) {
      addError(filePath, feature, 'required parity row is missing');
    }
  }
}

function runBrokenVectorSelfTest(validators) {
  const start = errors.length;

  const brokenStreamPath = path.join(expectedDir, '__broken-stream__.json');
  validateStreamExpected(
    brokenStreamPath,
    {
      source: 'openai-text.sse',
      events: [
        { type: 'start', responseId: 'broken' },
        { type: 'text-delta', text: 'Hello' },
        { type: 'finish', reason: 'stop' },
      ],
      text: 'Goodbye',
      reasoning: '',
      retryAllowed: false,
    },
    'openai-text.sse',
  );

  const brokenErrorPath = path.join(expectedDir, '__broken-error__.json');
  validateChatError(brokenErrorPath, '/cases/0/error', {
    code: 'not_a_chat_error_code',
    message: 'Broken vector',
    providerId: 'openai',
    status: 401,
    retryable: false,
    retryAfterSeconds: null,
    requestId: null,
    details: null,
  });

  const brokenCatalogPath = path.join(expectedDir, '__broken-catalog__.json');
  const catalogDocument = JSON.parse(
    readFileSync(path.join(expectedDir, EXPECTED_CATALOGS_FILE), 'utf8'),
  );
  catalogDocument.cases[0].expected[0].label = 'wrong-label';
  validateCatalogsExpected(brokenCatalogPath, catalogDocument);

  const brokenRequestPath = path.join(requestsDir, '__broken-request__.json');
  const requestDocument = JSON.parse(
    readFileSync(path.join(requestsDir, 'openai-basic.json'), 'utf8'),
  );
  requestDocument.expected.body.model = 'wrong-model';
  validateChatRequestVector(brokenRequestPath, requestDocument);

  const brokenMathPath = path.join(expectedDir, '__broken-math__.json');
  const mathDocument = JSON.parse(
    readFileSync(path.join(expectedDir, EXPECTED_MATH_FILE), 'utf8'),
  );
  mathDocument.cases[0].expected.segments[0].text = 'wrong-text';
  validateMathExpected(brokenMathPath, mathDocument);

  const brokenExportPath = path.join(expectedDir, '__broken-export__.json');
  const exportDocument = JSON.parse(
    readFileSync(path.join(expectedDir, EXPECTED_EXPORT_FILE), 'utf8'),
  );
  exportDocument.format = 'wrong.format';
  validateExportExpected(
    brokenExportPath,
    exportDocument,
    validators.get('chat-export.schema.json'),
  );

  const produced = errors.slice(start);
  errors.length = start;

  const streamOk = produced.some(
    (message) => message.includes(relative(brokenStreamPath)) && message.includes('/text'),
  );
  const errorOk = produced.some(
    (message) => message.includes(relative(brokenErrorPath)) && message.includes('/cases/0/error/code'),
  );
  const catalogOk = produced.some(
    (message) =>
      message.includes(relative(brokenCatalogPath)) &&
      message.includes('/cases/0/expected/0/label'),
  );
  const requestOk = produced.some(
    (message) =>
      message.includes(relative(brokenRequestPath)) &&
      message.includes('/expected/body/model'),
  );
  const mathOk = produced.some(
    (message) =>
      message.includes(relative(brokenMathPath)) &&
      message.includes('/cases/0/expected/segments'),
  );
  const exportOk = produced.some(
    (message) => message.includes(relative(brokenExportPath)) && message.includes('/format'),
  );

  if (!streamOk) {
    addError(brokenStreamPath, '/text', 'broken-vector self-test did not report the expected text mismatch');
  }
  if (!errorOk) {
    addError(brokenErrorPath, '/cases/0/error/code', 'broken-vector self-test did not report the expected invalid code');
  }
  if (!catalogOk) {
    addError(
      brokenCatalogPath,
      '/cases/0/expected/0/label',
      'broken-vector self-test did not report the expected catalog mismatch',
    );
  }
  if (!requestOk) {
    addError(
      brokenRequestPath,
      '/expected/body/model',
      'broken-vector self-test did not report the expected request mismatch',
    );
  }
  if (!mathOk) {
    addError(
      brokenMathPath,
      '/cases/0/expected/segments',
      'broken-vector self-test did not report the expected math mismatch',
    );
  }
  if (!exportOk) {
    addError(
      brokenExportPath,
      '/format',
      'broken-vector self-test did not report the expected export mismatch',
    );
  }
}

function main() {
  const schemas = loadSchemas();
  const validators = compileSchemas(schemas);

  validateAllJsonFiles();
  validateProviders(validators);
  validateModelFixtures(validators);
  validateExpectedVectors(validators);
  validateRequestVectors();
  validateFixtureEncoding();
  validateParity();
  runBrokenVectorSelfTest(validators);

  if (errors.length > 0) {
    for (const error of errors) {
      console.error(error);
    }
    console.error(`\nContract validation failed with ${errors.length} error(s).`);
    process.exitCode = 1;
    return;
  }

  console.log('Contract validation passed.');
}

main();
