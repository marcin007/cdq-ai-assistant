import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  KnowledgeCheckError,
  verifySnapshot,
  verifySnapshotFiles
} from './check-knowledge-freshness.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const script = path.join(root, 'scripts', 'check-knowledge-freshness.mjs');
const workflowPath = path.join(root, '.github', 'workflows', 'ci.yml');

function parseWorkflowSteps(workflow) {
  const lines = workflow.split(/\r?\n/);
  const stepsHeaderIndex = lines.findIndex((line) => /^\s*steps:\s*(?:#.*)?$/.test(line));
  assert.notEqual(stepsHeaderIndex, -1, 'expected CI workflow to define steps');

  const stepsIndent = lines[stepsHeaderIndex].match(/^\s*/)[0].length;
  const rawSteps = [];
  let stepIndent;
  let currentStep;

  for (const line of lines.slice(stepsHeaderIndex + 1)) {
    if (line.trim() === '' || line.trimStart().startsWith('#')) continue;
    const indentation = line.match(/^\s*/)[0].length;
    if (indentation <= stepsIndent) break;

    const listItem = /^(\s*)-\s+(.+)$/.exec(line);
    if (listItem && (stepIndent === undefined || indentation === stepIndent)) {
      stepIndent ??= indentation;
      if (currentStep) rawSteps.push(currentStep);
      currentStep = { inlineField: listItem[2], childLines: [] };
    } else if (currentStep) {
      currentStep.childLines.push(line);
    }
  }
  if (currentStep) rawSteps.push(currentStep);

  return rawSteps.map(({ inlineField, childLines }) => {
    const fieldLines = [inlineField];
    const nonBlankChildren = childLines.filter((line) => line.trim() !== '');
    const fieldIndent = nonBlankChildren.length === 0
      ? undefined
      : Math.min(...nonBlankChildren.map((line) => line.match(/^\s*/)[0].length));
    fieldLines.push(...nonBlankChildren
      .filter((line) => line.match(/^\s*/)[0].length === fieldIndent)
      .map((line) => line.trim()));

    const runField = fieldLines.find((line) => /^run:\s*/.test(line));
    return {
      run: runField?.replace(/^run:\s*/, '').trim(),
      conditional: fieldLines.some((line) => /^if:\s*/.test(line)),
      continueOnError: fieldLines.some((line) => /^continue-on-error:\s*/.test(line))
    };
  });
}

function requireUnconditionalRunStep(workflowSteps, command) {
  const matches = workflowSteps
    .map((step, index) => ({ step, index }))
    .filter(({ step }) => step.run === command);
  assert.equal(matches.length, 1, `expected CI to run exactly once: ${command}`);
  assert.equal(matches[0].step.conditional, false, `expected CI step to be unconditional: ${command}`);
  assert.equal(matches[0].step.continueOnError, false, `expected CI step to fail closed: ${command}`);
  return matches[0].index;
}
const canonicalSourceUrl = 'https://www.cdq.com/products/cdq-fraud-guard';
const textBytes = Buffer.from('reviewed CDQ knowledge\n', 'utf8');
const snapshotHash = createHash('sha256').update(textBytes).digest('hex');
const capturedAt = '2026-07-26T08:22:11Z';
const now = new Date('2026-08-02T08:22:11Z');

function metadata(overrides = {}) {
  return JSON.stringify({
    sourceUrl: canonicalSourceUrl,
    capturedAt,
    snapshotHash,
    ...overrides
  });
}

function assertKnowledgeFailure(action, reason) {
  assert.throws(action, (error) =>
    error instanceof KnowledgeCheckError && error.message === reason
  );
}

function runCli(args) {
  return new Promise((resolve, reject) => {
    execFile(process.execPath, [script, ...args], { cwd: root }, (error, stdout, stderr) => {
      if (error && error.code === 'ENOENT') {
        reject(error);
        return;
      }
      resolve({ code: error?.code ?? 0, stdout, stderr });
    });
  });
}

test('accepts matching reviewed knowledge within the age budget', () => {
  const result = verifySnapshot({
    textBytes,
    metadataText: metadata(),
    now,
    maxAgeDays: 45
  });

  assert.equal(result.snapshotHash, snapshotHash);
  assert.equal(result.capturedAt, capturedAt);
  assert.equal(result.ageDays, 7);
});

test('rejects a snapshot hash that does not match its bytes', () => {
  assertKnowledgeFailure(() => verifySnapshot({
    textBytes,
    metadataText: metadata({ snapshotHash: '0'.repeat(64) }),
    now,
    maxAgeDays: 45
  }), 'snapshot hash does not match text');
});

test('rejects empty metadata JSON', () => {
  assertKnowledgeFailure(() => verifySnapshot({
    textBytes,
    metadataText: '',
    now,
    maxAgeDays: 45
  }), 'metadata is not valid JSON');
});

test('rejects metadata JSON that is not an object', () => {
  assertKnowledgeFailure(() => verifySnapshot({
    textBytes,
    metadataText: '[]',
    now,
    maxAgeDays: 45
  }), 'metadata must be an object');
});

test('rejects missing and wrongly typed metadata fields', () => {
  for (const metadataText of [
    JSON.stringify({ capturedAt, snapshotHash }),
    metadata({ sourceUrl: 42 }),
    metadata({ capturedAt: 42 }),
    metadata({ snapshotHash: 42 })
  ]) {
    assertKnowledgeFailure(() => verifySnapshot({
      textBytes,
      metadataText,
      now,
      maxAgeDays: 45
    }), 'metadata fields are invalid');
  }
});

test('rejects a source URL other than the canonical CDQ Fraud Guard URL', () => {
  assertKnowledgeFailure(() => verifySnapshot({
    textBytes,
    metadataText: metadata({ sourceUrl: 'https://example.invalid' }),
    now,
    maxAgeDays: 45
  }), 'metadata source URL is not canonical');
});

test('rejects a snapshot hash outside the lowercase hexadecimal format', () => {
  assertKnowledgeFailure(() => verifySnapshot({
    textBytes,
    metadataText: metadata({ snapshotHash: snapshotHash.toUpperCase() }),
    now,
    maxAgeDays: 45
  }), 'metadata snapshot hash is invalid');
});

test('rejects an invalid capture timestamp', () => {
  assertKnowledgeFailure(() => verifySnapshot({
    textBytes,
    metadataText: metadata({ capturedAt: 'not-a-timestamp' }),
    now,
    maxAgeDays: 45
  }), 'metadata capture timestamp is invalid');
});

test('rejects a future capture timestamp', () => {
  assertKnowledgeFailure(() => verifySnapshot({
    textBytes,
    metadataText: metadata({ capturedAt: '2026-08-02T08:22:12Z' }),
    now,
    maxAgeDays: 45
  }), 'capture timestamp is in the future');
});

test('rejects an age budget outside one to 365 days', () => {
  for (const maxAgeDays of [0, 366, 1.5, '45']) {
    assertKnowledgeFailure(() => verifySnapshot({
      textBytes,
      metadataText: metadata(),
      now,
      maxAgeDays
    }), 'max age days is invalid');
  }
});

test('rejects knowledge older than its age budget', () => {
  assertKnowledgeFailure(() => verifySnapshot({
    textBytes,
    metadataText: metadata(),
    now,
    maxAgeDays: 6
  }), 'knowledge is older than the age budget');
});

test('accepts knowledge exactly as old as the age budget', () => {
  const result = verifySnapshot({
    textBytes,
    metadataText: metadata(),
    now: new Date('2026-08-10T08:22:11Z'),
    maxAgeDays: 15
  });

  assert.equal(result.ageDays, 15);
});

test('verifies matching snapshot files without reading production paths', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'cdq-knowledge-check-'));
  try {
    const textPath = path.join(directory, 'snapshot.txt');
    const metadataPath = path.join(directory, 'snapshot.source.json');
    await writeFile(textPath, textBytes);
    await writeFile(metadataPath, metadata());

    const result = await verifySnapshotFiles({ textPath, metadataPath, now, maxAgeDays: 45 });
    assert.deepEqual(result, { snapshotHash, capturedAt, ageDays: 7 });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('fails safely when a snapshot file is missing', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'cdq-knowledge-check-'));
  try {
    await assert.rejects(
      verifySnapshotFiles({
        textPath: path.join(directory, 'missing.txt'),
        metadataPath: path.join(directory, 'missing.source.json'),
        now,
        maxAgeDays: 45
      }),
      (error) => error instanceof KnowledgeCheckError && error.message === 'knowledge files could not be read'
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('CLI validates the committed CDQ knowledge at the configured budget', async () => {
  const committedMetadata = JSON.parse(
    await readFile(path.join(root, 'knowledge', 'cdq-fraud-guard.source.json'), 'utf8')
  );
  const result = await runCli(['--max-age-days', '45']);
  assert.equal(result.code, 0, result.stderr);
  assert.equal(result.stderr, '');
  assert.match(result.stdout, new RegExp(`snapshot hash: ${committedMetadata.snapshotHash}`));
  assert.match(result.stdout, new RegExp(`captured at: ${committedMetadata.capturedAt}`));
  const ageMatch = result.stdout.match(/^age days: (\d+)$/m);
  assert.ok(ageMatch, result.stdout);
  const ageDays = Number(ageMatch[1]);
  assert.ok(Number.isInteger(ageDays));
  assert.ok(ageDays >= 0 && ageDays <= 45, `expected age in 0..45, received ${ageDays}`);
  assert.match(result.stdout, /max age days: 45/);
});

test('CLI exits two for an invalid age option', async () => {
  const result = await runCli(['--max-age-days', '0']);
  assert.equal(result.code, 2);
  assert.equal(result.stdout, '');
  assert.equal(
    result.stderr,
    'knowledge-check: invalid CLI arguments; recapture, review, and version the CDQ snapshot\n'
  );
});

test('CLI does not accept options that redirect the fixed production source paths', async () => {
  const result = await runCli(['--text-path', path.join(os.tmpdir(), 'untrusted.txt')]);
  assert.equal(result.code, 2);
  assert.equal(result.stdout, '');
  assert.equal(
    result.stderr,
    'knowledge-check: invalid CLI arguments; recapture, review, and version the CDQ snapshot\n'
  );
});

test('CI checks knowledge freshness before Maven verification', async () => {
  const workflowSteps = parseWorkflowSteps(await readFile(workflowPath, 'utf8'));
  const unitTestCommand = 'node --test scripts/check-knowledge-freshness.test.mjs';
  const productionCheckCommand = 'node scripts/check-knowledge-freshness.mjs --max-age-days 45';
  const mavenVerifyCommand = './mvnw --batch-mode verify';

  const unitTestIndex = requireUnconditionalRunStep(workflowSteps, unitTestCommand);
  const productionCheckIndex = requireUnconditionalRunStep(workflowSteps, productionCheckCommand);
  const mavenVerifyIndex = requireUnconditionalRunStep(workflowSteps, mavenVerifyCommand);
  assert.ok(unitTestIndex < productionCheckIndex, 'knowledge unit test must run before its production check');
  assert.ok(productionCheckIndex < mavenVerifyIndex, 'knowledge production check must run before Maven verification');
});
