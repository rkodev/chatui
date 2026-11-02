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
import { TextDecoder } from 'node:util';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..');
const contractsDir = path.join(repoRoot, 'contracts');
const fixturesDir = path.join(contractsDir, 'fixtures');
const expectedDir = path.join(fixturesDir, 'expected');
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
