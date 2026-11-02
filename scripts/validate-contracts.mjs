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
const schemaDir = path.join(contractsDir, 'schema');

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
