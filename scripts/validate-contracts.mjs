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
]);
const EXPECTED_KEY_FILE = 'openrouter-key.json';
const EXPECTED_ERRORS_FILE = 'openrouter-errors.json';
const EXPECTED_FILES = new Set([
  ...EXPECTED_STREAM_SOURCES.keys(),
  EXPECTED_KEY_FILE,
  EXPECTED_ERRORS_FILE,
]);

const REQUEST_KINDS = ['chat-request', 'protected-fields', 'attribution', 'default-model'];
const REQUIRED_REQUEST_FILES = [
  'openrouter-basic.json',
  'openrouter-routing.json',
  'openrouter-advanced-protected.json',
  'openrouter-attribution.json',
];
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
    return;
  }
  validateOpenRouterExtra(filePath, '/request/params/extra', document.request.params.extra, false);
  if (!checkObjectShape(filePath, '/expected', document.expected, ['body'], ['body'])) {
    return;
  }
  validateChatRequestBody(filePath, document.request, document.supportedParameters, document.expected.body);
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

  const openrouter = validateOpenRouterExtra(
    filePath,
    '/request/params/extra',
    document.request.params.extra,
    true,
  );
  const protectedFields = isPlainObject(openrouter)
    ? PROTECTED_REQUEST_FIELDS.filter((key) => Object.prototype.hasOwnProperty.call(openrouter, key)).sort()
    : [];
  if (protectedFields.length === 0) {
    addError(
      filePath,
      '/request/params/extra/openrouter',
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
    if (document.expected.error.providerId !== 'openrouter') {
      addError(filePath, '/expected/error/providerId', 'must be "openrouter"');
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
    if (document.providerId !== 'openrouter') {
      addError(filePath, '/providerId', 'must be "openrouter"');
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
}

function validateExpectedVectors() {
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
    }
  }

  for (const name of EXPECTED_FILES) {
    if (!seen.has(name)) {
      addError(path.join(expectedDir, name), '$', 'required expected vector is missing');
    }
  }

  const streamDir = path.join(fixturesDir, 'streams');
  const expectedSources = new Set(EXPECTED_STREAM_SOURCES.values());
  for (const filePath of walkFiles(streamDir).filter((file) => file.endsWith('.sse'))) {
    const name = path.basename(filePath);
    if (name.startsWith('openrouter-') && !expectedSources.has(name)) {
      addError(filePath, '$', 'OpenRouter stream fixture has no expected vector');
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

    try {
      new TextDecoder('utf-8', { fatal: true }).decode(buffer);
    } catch {
      addError(filePath, '$', 'fixture is not valid UTF-8');
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

function main() {
  const schemas = loadSchemas();
  const validators = compileSchemas(schemas);

  validateAllJsonFiles();
  validateProviders(validators);
  validateModelFixtures(validators);
  validateExpectedVectors();
  validateRequestVectors();
  validateFixtureEncoding();
  validateParity();

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
