import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { existsSync, writeFileSync } from 'node:fs';
import {
  copyFile,
  lstat,
  mkdtemp,
  mkdir,
  readFile,
  realpath,
  rm,
  symlink,
  writeFile
} from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  ReliabilityCaseError,
  buildReliabilityReport,
  runReliabilityMatrix,
  summarizeReliability
} from './reliability-core.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourceReliability = path.join(root, 'scripts', 'reliability.mjs');
const sourceReliabilityCore = path.join(root, 'scripts', 'reliability-core.mjs');

const countriesSource = Object.freeze({
  kind: 'REST_COUNTRIES',
  label: 'REST Countries v5',
  url: 'https://restcountries.com/'
});
const weatherSource = Object.freeze({
  kind: 'WEATHER',
  label: 'WeatherAPI via semdin/mcp-weather',
  url: 'https://github.com/semdin/mcp-weather'
});
const cliPrompts = Object.freeze([
  Object.freeze({ id: 'germany-capital', question: 'What is Germany\'s capital?', kinds: ['REST_COUNTRIES'] }),
  Object.freeze({ id: 'munich-weather', question: 'What is Munich\'s temperature?', kinds: ['WEATHER'] })
]);
const cliSuccesses = Object.freeze([
  Object.freeze({ answer: 'Berlin is the capital city of Germany.', sources: [countriesSource] }),
  Object.freeze({ answer: 'The current temperature in Munich is 18 °C.', sources: [weatherSource] })
]);

const fixtureContract = `
export const MODEL = Object.freeze({
  chatModel: 'qwen3:4b-instruct-2507-q4_K_M',
  embeddingModel: 'qwen3-embedding:0.6b'
});
export class EvaluationValidationError extends Error {}
export const RELIABILITY_PROMPTS = Object.freeze(${JSON.stringify(cliPrompts)});
const sources = ${JSON.stringify({ REST_COUNTRIES: countriesSource, WEATHER: weatherSource })};
export function validateAnswerPayload(payload, prompt) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)
      || typeof payload.answer !== 'string' || payload.answer.trim() === '') {
    throw new EvaluationValidationError('response answer is malformed');
  }
  if (!Array.isArray(payload.sources)
      || payload.sources.length !== prompt.kinds.length
      || payload.sources.some((source, index) => {
        const expected = sources[prompt.kinds[index]];
        return !source || source.kind !== prompt.kinds[index]
          || source.label !== expected.label || source.url !== expected.url;
      })) {
    throw new EvaluationValidationError('source kind/order validation failed');
  }
  const valid = prompt.id === 'germany-capital'
    ? /Berlin is the capital(?: city)? of Germany/i.test(payload.answer)
    : /temperature in Munich is [-+]?\\d+(?:[.,]\\d+)? *(?:°?C|degrees? Celsius)/i.test(payload.answer);
  if (!valid) throw new EvaluationValidationError('answer failed semantic validation');
}
`;

function gitHead(workspace) {
  return execFileSync('git', ['rev-parse', 'HEAD'], {
    cwd: workspace,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore']
  }).trim();
}

function runtimeAttestation(workspace, overrides = {}) {
  return {
    build: {
      commit: gitHead(workspace),
      worktreeClean: true,
      ...overrides
    }
  };
}

function runReliability(workspace, args, { shortenTimeouts = false, killAfterMs } = {}) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [path.join(workspace, 'scripts', 'reliability.mjs'), ...args], {
      cwd: workspace,
      env: shortenTimeouts
        ? {
            ...process.env,
            NODE_OPTIONS: `--import=${pathToFileURL(path.join(workspace, 'scripts', 'short-timeouts.mjs')).href}`
          }
        : process.env,
      stdio: ['ignore', 'pipe', 'pipe']
    });
    const killDeadline = killAfterMs === undefined
      ? undefined
      : setTimeout(() => child.kill('SIGKILL'), killAfterMs);
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.on('close', (code, signal) => {
      if (killDeadline !== undefined) clearTimeout(killDeadline);
      setTimeout(() => resolve({ code, signal, stdout, stderr }), 0);
    });
  });
}

async function makeCliWorkspace(label) {
  const workspace = await mkdtemp(path.join(os.tmpdir(), `cdq-reliability-${label}-`));
  await mkdir(path.join(workspace, 'scripts'), { recursive: true });
  await mkdir(path.join(workspace, 'evaluation'), { recursive: true });
  await copyFile(sourceReliabilityCore, path.join(workspace, 'scripts', 'reliability-core.mjs'));
  if (existsSync(sourceReliability)) {
    await copyFile(sourceReliability, path.join(workspace, 'scripts', 'reliability.mjs'));
  }
  await writeFile(path.join(workspace, 'scripts', 'evaluation-contract.mjs'), fixtureContract);
  await writeFile(
    path.join(workspace, 'scripts', 'short-timeouts.mjs'),
    `const originalSetTimeout = globalThis.setTimeout;\n`
      + `globalThis.setTimeout = (callback, delay, ...args) => originalSetTimeout(callback, Math.min(delay, 100), ...args);\n`
  );
  await writeFile(path.join(workspace, 'README.md'), 'fixture repository\n');
  execFileSync('git', ['init', '-q'], { cwd: workspace });
  execFileSync('git', ['config', 'user.email', 'test@example.invalid'], { cwd: workspace });
  execFileSync('git', ['config', 'user.name', 'Reliability test'], { cwd: workspace });
  execFileSync('git', ['add', '.'], { cwd: workspace });
  execFileSync('git', ['commit', '-qm', 'fixture'], { cwd: workspace });
  return realpath(workspace);
}

async function startCliFixture(responseForIndex, runtimeInfoForIndex = (_index, workspace) => ({
  body: runtimeAttestation(workspace)
})) {
  const received = [];
  let infoRequestCount = 0;
  let active = 0;
  let maximumActive = 0;
  let stalledResponseCloseCount = 0;
  let workspace;
  const server = http.createServer((request, response) => {
    active += 1;
    maximumActive = Math.max(maximumActive, active);
    let body = '';
    request.setEncoding('utf8');
    request.on('data', (chunk) => { body += chunk; });
    request.on('end', async () => {
      try {
        let fixture;
        if (request.method === 'GET' && request.url === '/actuator/info') {
          fixture = runtimeInfoForIndex(infoRequestCount, workspace);
          infoRequestCount += 1;
        } else if (request.method === 'POST' && request.url === '/api/chat') {
          const parsed = JSON.parse(body);
          received.push(parsed.message);
          fixture = responseForIndex(received.length - 1, parsed.message);
        } else {
          fixture = { status: 404, body: { error: 'not found' } };
        }
        if (fixture.delayMs) {
          await new Promise((resolve) => setTimeout(resolve, fixture.delayMs));
        }
        response.writeHead(fixture.status ?? 200, { 'Content-Type': 'application/json' });
        if (fixture.bodyDelayMs) {
          response.flushHeaders();
          await new Promise((resolve) => setTimeout(resolve, fixture.bodyDelayMs));
        }
        if (fixture.stallBody) {
          response.once('close', () => { stalledResponseCloseCount += 1; });
          response.flushHeaders();
          return;
        }
        response.end(fixture.rawBody ?? JSON.stringify(fixture.body));
      } catch {
        response.writeHead(500, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ error: 'fixture failure' }));
      } finally {
        active -= 1;
      }
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    received,
    attachWorkspace: (value) => { workspace = value; },
    infoRequests: () => infoRequestCount,
    maximumActive: () => maximumActive,
    stalledResponseCloses: () => stalledResponseCloseCount,
    close: () => new Promise((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
      server.closeAllConnections();
    })
  };
}

function responseForMessage(message) {
  const index = cliPrompts.findIndex((prompt) => prompt.question === message);
  return { body: cliSuccesses[index] };
}

const metadata = Object.freeze({
  evaluatedAt: '2026-08-02T12:34:56.000Z',
  gitCommit: '0123456789abcdef0123456789abcdef01234567',
  chatModel: 'qwen3:4b-instruct-2507-q4_K_M',
  embeddingModel: 'qwen3-embedding:0.6b',
  runs: 3
});

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

test('README distinguishes strict live answers from repeated reliability evidence', async () => {
  const content = await readFile(path.join(root, 'README.md'), 'utf8');
  assert.match(content, /node scripts\/smoke-chat\.mjs/);
  assert.match(content, /node scripts\/reliability\.mjs/);
  assert.match(content, /evaluation\/reliability\.md/);
  assert.match(content, /three repetitions/);
  assert.match(content, /offline fixtures[^.\n]*do not create live reports/i);
  assert.match(content, /source-kind order/i);
  assert.match(content, /availability of dynamic upstream services/i);
});

test('CI runs the unconditional reliability suite once immediately after the evaluator suite', async () => {
  const workflowSteps = parseWorkflowSteps(
    await readFile(path.join(root, '.github', 'workflows', 'ci.yml'), 'utf8')
  );
  const evaluator = 'node --test scripts/evaluate.test.mjs';
  const reliability = 'node --test scripts/reliability.test.mjs';

  const evaluatorIndex = requireUnconditionalRunStep(workflowSteps, evaluator);
  const reliabilityIndex = requireUnconditionalRunStep(workflowSteps, reliability);
  assert.equal(
    reliabilityIndex,
    evaluatorIndex + 1,
    'reliability suite must run immediately after evaluator tests'
  );
});

test('runs every case sequentially for every repetition and retains failures', async () => {
  let active = 0;
  let maximumActive = 0;
  const results = await runReliabilityMatrix({
    prompts: [{ id: 'a' }, { id: 'b' }],
    runs: 2,
    ask: async (prompt) => {
      active += 1;
      maximumActive = Math.max(maximumActive, active);
      await new Promise((resolve) => setTimeout(resolve, 2));
      active -= 1;
      if (prompt.id === 'b') throw new ReliabilityCaseError('semantic validation failed');
      return { latencyMs: 10, sources: ['REST_COUNTRIES'] };
    }
  });

  assert.equal(maximumActive, 1);
  assert.equal(results.length, 4);
  assert.equal(results.filter((result) => result.passed).length, 2);
  assert.deepEqual(results.map((result) => [result.run, result.caseId]), [
    [1, 'a'], [1, 'b'], [2, 'a'], [2, 'b']
  ]);
});

test('rejects runs outside one through ten before asking any case', async () => {
  for (const runs of [0, 11, 1.5, Number.NaN]) {
    let asks = 0;
    await assert.rejects(
      runReliabilityMatrix({
        prompts: [{ id: 'a' }],
        runs,
        ask: async () => { asks += 1; }
      }),
      RangeError
    );
    assert.equal(asks, 0);
  }
});

test('stores frozen failure entries with only an allowlisted safe category', async () => {
  const [result] = await runReliabilityMatrix({
    prompts: [{ id: 'case-a' }],
    runs: 1,
    ask: async () => {
      throw new Error('raw provider failure: answer=confidential');
    }
  });

  assert.deepEqual(Object.keys(result).sort(), ['caseId', 'category', 'run']);
  assert.equal(result.category, 'REQUEST_FAILED');
  assert.equal(Object.isFrozen(result), true);
  assert.throws(() => { result.category = 'raw provider failure: answer=confidential'; }, TypeError);
  assert.equal(result.category, 'REQUEST_FAILED');
});

test('normalizes a mutated reliability error back to a safe category', async () => {
  const unsafeError = new ReliabilityCaseError('HTTP_5XX');
  unsafeError.category = 'answer=confidential';
  const [result] = await runReliabilityMatrix({
    prompts: [{ id: 'case-a' }],
    runs: 1,
    ask: async () => { throw unsafeError; }
  });

  assert.equal(result.category, 'REQUEST_FAILED');
});

test('keeps successful result data immutable without retaining mutable source arrays', async () => {
  const sources = ['REST_COUNTRIES'];
  const [result] = await runReliabilityMatrix({
    prompts: [{ id: 'case-a' }],
    runs: 1,
    ask: async () => ({ latencyMs: 12, sources })
  });

  sources.push('WEATHER');
  assert.deepEqual(result.sources, ['REST_COUNTRIES']);
  assert.equal(Object.isFrozen(result), true);
  assert.equal(Object.isFrozen(result.sources), true);
});

test('calculates total and per-case pass rates plus p50 and p95', () => {
  const summary = summarizeReliability([
    { caseId: 'a', run: 1, passed: true, latencyMs: 10, sources: [] },
    { caseId: 'a', run: 2, passed: true, latencyMs: 20, sources: [] },
    { caseId: 'b', run: 1, passed: false, category: 'HTTP_5XX' },
    { caseId: 'b', run: 2, passed: true, latencyMs: 40, sources: [] }
  ]);

  assert.equal(summary.passed, 3);
  assert.equal(summary.total, 4);
  assert.equal(summary.passRate, 75);
  assert.equal(summary.p50LatencyMs, 20);
  assert.equal(summary.p95LatencyMs, 40);
  assert.deepEqual(summary.perCase, [
    { caseId: 'a', passed: 2, total: 2, passRate: 100 },
    { caseId: 'b', passed: 1, total: 2, passRate: 50 }
  ]);
});

test('renders a deterministic report without answers or raw exception messages', () => {
  const results = [
    Object.freeze({ caseId: 'b', run: 2, passed: true, latencyMs: 40, sources: Object.freeze(['WEATHER']) }),
    Object.freeze({ caseId: 'a', run: 2, passed: true, latencyMs: 20, sources: Object.freeze(['REST_COUNTRIES']) }),
    Object.freeze({ caseId: 'b', run: 1, category: 'HTTP_5XX' }),
    Object.freeze({ caseId: 'a', run: 1, passed: true, latencyMs: 10, sources: Object.freeze(['REST_COUNTRIES']) })
  ];
  const report = buildReliabilityReport({ ...metadata, runs: 2 }, summarizeReliability(results), results);

  assert.equal(report, `# Live Reliability Report\n\n- Evaluated at (UTC): \`2026-08-02T12:34:56.000Z\`\n- Git commit: \`0123456789abcdef0123456789abcdef01234567\`\n- Chat model: \`qwen3:4b-instruct-2507-q4_K_M\`\n- Embedding model: \`qwen3-embedding:0.6b\`\n- Runs per case: \`2\`\n- Overall: \`3/4\`\n- Latency p50 / p95: \`20 ms\` / \`40 ms\`\n\n> Scope: This report measures the observed reliability of the whole local system, not only the model. Availability of dynamic upstream services contributes to these observations.\n\n## Per-case results\n\n| Case | Passed | Total | Pass rate |\n| --- | ---: | ---: | ---: |\n| a | 2 | 2 | 100% |\n| b | 1 | 2 | 50% |\n\n## Successful observations\n\n| Run | Case | Latency | Attested source kinds |\n| ---: | --- | ---: | --- |\n| 1 | a | 10 ms | REST_COUNTRIES |\n| 2 | a | 20 ms | REST_COUNTRIES |\n| 2 | b | 40 ms | WEATHER |\n\n## Observed failures\n\n| Run | Case | Safe category |\n| ---: | --- | --- |\n| 1 | b | HTTP_5XX |\n`);
  assert.doesNotMatch(report, /answer=confidential|raw provider failure/i);
});

test('renders the same successful observations for any valid result order', () => {
  const results = [
    { caseId: 'b', run: 2, passed: true, latencyMs: 40, sources: ['REST_COUNTRIES', 'WEATHER'] },
    { caseId: 'a', run: 1, passed: true, latencyMs: 10, sources: ['REST_COUNTRIES'] },
    { caseId: 'a', run: 2, passed: true, latencyMs: 20, sources: ['REST_COUNTRIES'] },
    { caseId: 'b', run: 1, passed: true, latencyMs: 30, sources: ['REST_COUNTRIES', 'WEATHER'] }
  ];
  const reordered = results.toReversed();
  const report = buildReliabilityReport(
    { ...metadata, runs: 2 },
    summarizeReliability(results),
    results
  );

  assert.equal(
    report,
    buildReliabilityReport({ ...metadata, runs: 2 }, summarizeReliability(reordered), reordered)
  );
  assert.match(
    report,
    /\| 1 \| a \| 10 ms \| REST_COUNTRIES \|\n\| 1 \| b \| 30 ms \| REST_COUNTRIES, WEATHER \|\n\| 2 \| a \| 20 ms \| REST_COUNTRIES \|\n\| 2 \| b \| 40 ms \| REST_COUNTRIES, WEATHER \|/
  );
});

test('preserves attested source order within each successful observation', () => {
  const canonical = [
    { caseId: 'a', run: 1, passed: true, latencyMs: 10, sources: ['REST_COUNTRIES', 'WEATHER'] }
  ];
  const reversed = [
    { caseId: 'a', run: 1, passed: true, latencyMs: 10, sources: ['WEATHER', 'REST_COUNTRIES'] }
  ];
  const canonicalReport = buildReliabilityReport(
    { ...metadata, runs: 1 },
    summarizeReliability(canonical),
    canonical
  );
  const reversedReport = buildReliabilityReport(
    { ...metadata, runs: 1 },
    summarizeReliability(reversed),
    reversed
  );

  assert.match(canonicalReport, /\| 1 \| a \| 10 ms \| REST_COUNTRIES, WEATHER \|/);
  assert.match(reversedReport, /\| 1 \| a \| 10 ms \| WEATHER, REST_COUNTRIES \|/);
  assert.notEqual(canonicalReport, reversedReport);
});

test('does not render answer, response body, raw error, or secret fields from successes', () => {
  const results = [{
    caseId: 'a',
    run: 1,
    passed: true,
    latencyMs: 10,
    sources: ['REST_COUNTRIES'],
    answer: 'answer=confidential',
    responseBody: 'response-body-secret',
    rawError: 'raw-error-secret',
    secret: 'credential-secret'
  }];
  const report = buildReliabilityReport(
    { ...metadata, runs: 1 },
    summarizeReliability(results),
    results
  );

  assert.doesNotMatch(report, /answer=confidential|response-body-secret|raw-error-secret|credential-secret/);
});

test('rejects successful observations with source kinds outside the safe allowlist', () => {
  const results = [
    { caseId: 'a', run: 1, passed: true, latencyMs: 10, sources: ['answer=confidential'] }
  ];

  assert.throws(
    () => buildReliabilityReport({ ...metadata, runs: 1 }, {}, results),
    /invalid reliability result/
  );
});

test('uses a fixed no-failure message and rejects unsafe report inputs', () => {
  const results = [
    Object.freeze({ caseId: 'a', run: 1, passed: true, latencyMs: 10, sources: Object.freeze([]) })
  ];
  const summary = summarizeReliability(results);
  const report = buildReliabilityReport({ ...metadata, runs: 1 }, summary, results);

  assert.match(report, /whole local system, not only the model/);
  assert.match(report, /Availability of dynamic upstream services contributes to these observations\./);
  assert.match(report, /\| 1 \| a \| 10 ms \| none \|/);
  assert.match(report, /No failures were observed in this run\./);
  assert.throws(
    () => buildReliabilityReport({ ...metadata, chatModel: 'answer=confidential' }, summary, results),
    /invalid reliability report metadata/
  );
  assert.throws(
    () => buildReliabilityReport(metadata, summary, [
      { caseId: 'answer=confidential', run: 1, category: 'not-safe' }
    ]),
    /invalid reliability result/
  );
});

test('rejects an empty report matrix instead of claiming zero observed failures', () => {
  assert.throws(
    () => buildReliabilityReport({ ...metadata, runs: 1 }, summarizeReliability([]), []),
    /invalid reliability matrix/
  );
});

test('rejects results with a run beyond the reported run count', () => {
  const results = [
    { caseId: 'a', run: 1, passed: true, latencyMs: 10, sources: [] },
    { caseId: 'a', run: 2, passed: true, latencyMs: 20, sources: [] }
  ];

  assert.throws(
    () => buildReliabilityReport({ ...metadata, runs: 1 }, summarizeReliability(results), results),
    /invalid reliability matrix/
  );
});

test('rejects a report matrix with a missing run', () => {
  const results = [
    { caseId: 'a', run: 1, passed: true, latencyMs: 10, sources: [] },
    { caseId: 'a', run: 3, passed: true, latencyMs: 30, sources: [] }
  ];

  assert.throws(
    () => buildReliabilityReport(metadata, summarizeReliability(results), results),
    /invalid reliability matrix/
  );
});

test('rejects a report matrix with a duplicate run for one case', () => {
  const results = [
    { caseId: 'a', run: 1, passed: true, latencyMs: 10, sources: [] },
    { caseId: 'a', run: 1, passed: true, latencyMs: 20, sources: [] }
  ];

  assert.throws(
    () => buildReliabilityReport({ ...metadata, runs: 2 }, summarizeReliability(results), results),
    /invalid reliability matrix/
  );
});

test('CLI runs two prompts twice sequentially and atomically writes a mode-0600 report', async () => {
  const workspace = await makeCliWorkspace('success');
  const output = path.join(workspace, 'evaluation', 'reliability.md');
  const fixture = await startCliFixture((index, message) => ({
    ...responseForMessage(message),
    delayMs: index === 0 ? 10 : 1
  }));
  fixture.attachWorkspace(workspace);
  try {
    const result = await runReliability(workspace, ['--base-url', fixture.baseUrl, '--runs', '2']);

    assert.equal(result.code, 0, result.stderr);
    assert.equal(result.signal, null);
    assert.deepEqual(fixture.received, [
      cliPrompts[0].question,
      cliPrompts[1].question,
      cliPrompts[0].question,
      cliPrompts[1].question
    ]);
    assert.equal(fixture.maximumActive(), 1);
    assert.equal(fixture.infoRequests(), 2);
    const report = await readFile(output, 'utf8');
    assert.match(report, /^# Live Reliability Report/m);
    assert.match(report, /Runs per case: `2`/);
    assert.match(report, /Overall: `4\/4`/);
    assert.match(report, /\| germany-capital \| 2 \| 2 \| 100% \|/);
    assert.match(report, /\| munich-weather \| 2 \| 2 \| 100% \|/);
    assert.doesNotMatch(report, /Berlin is|temperature in Munich|What is Germany/);
    assert.equal((await lstat(output)).mode & 0o777, 0o600);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('CLI latency includes a finite headers-first delayed response body', async () => {
  const workspace = await makeCliWorkspace('body-latency');
  const output = path.join(workspace, 'evaluation', 'reliability.md');
  const fixture = await startCliFixture((index, message) => ({
    ...responseForMessage(message),
    bodyDelayMs: index === 0 ? 200 : 0
  }));
  fixture.attachWorkspace(workspace);
  try {
    const result = await runReliability(workspace, ['--base-url', fixture.baseUrl, '--runs', '1']);

    assert.equal(result.code, 0, result.stderr);
    const report = await readFile(output, 'utf8');
    const latency = report.match(/Latency p50 \/ p95: `([^ ]+) ms` \/ `([^ ]+) ms`/);
    assert.ok(latency);
    assert.ok(Number(latency[2]) >= 150, `reported p95 was ${latency[2]} ms`);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('CLI continues after an individual 503 and exits 1 with a completed safe report', async () => {
  const workspace = await makeCliWorkspace('http-503');
  const output = path.join(workspace, 'evaluation', 'reliability.md');
  const fixture = await startCliFixture((index, message) => index === 1
    ? { status: 503, stallBody: true }
    : responseForMessage(message));
  fixture.attachWorkspace(workspace);
  try {
    const result = await runReliability(
      workspace,
      ['--base-url', fixture.baseUrl, '--runs', '2'],
      { shortenTimeouts: true, killAfterMs: 2_000 }
    );

    assert.equal(result.code, 1, `signal=${result.signal} stderr=${result.stderr}`);
    assert.equal(result.signal, null);
    assert.equal(fixture.received.length, 4);
    assert.equal(fixture.infoRequests(), 2);
    assert.equal(fixture.stalledResponseCloses(), 1);
    const report = await readFile(output, 'utf8');
    assert.match(report, /Overall: `3\/4`/);
    assert.match(report, /\| 1 \| munich-weather \| HTTP_5XX \|/);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('CLI aborts an attestation error response with a stalled body before safe exit', async () => {
  const workspace = await makeCliWorkspace('attestation-error-body');
  const output = path.join(workspace, 'evaluation', 'reliability.md');
  const sentinel = 'previous verified reliability report\n';
  await writeFile(output, sentinel, { mode: 0o600 });
  const fixture = await startCliFixture(
    (_index, message) => responseForMessage(message),
    () => ({ status: 503, stallBody: true })
  );
  fixture.attachWorkspace(workspace);
  try {
    const result = await runReliability(
      workspace,
      ['--base-url', fixture.baseUrl, '--runs', '2'],
      { shortenTimeouts: true, killAfterMs: 2_000 }
    );

    assert.equal(result.code, 1, `signal=${result.signal} stderr=${result.stderr}`);
    assert.equal(result.signal, null);
    assert.deepEqual(fixture.received, []);
    assert.equal(fixture.infoRequests(), 1);
    assert.equal(fixture.stalledResponseCloses(), 1);
    assert.equal(await readFile(output, 'utf8'), sentinel);
    assert.match(result.stderr, /^reliability: runtime build attestation could not be verified\n$/);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('CLI maps malformed JSON and semantic failures to fixed categories without leaking observations', async () => {
  const workspace = await makeCliWorkspace('safe-categories');
  const output = path.join(workspace, 'evaluation', 'reliability.md');
  const fixture = await startCliFixture((index, message) => {
    if (index === 0) return { rawBody: '{"answer":"raw answer secret"' };
    if (index === 1) {
      return {
        body: {
          answer: 'raw semantic secret: Munich weather is unavailable',
          sources: [weatherSource]
        }
      };
    }
    return responseForMessage(message);
  });
  fixture.attachWorkspace(workspace);
  try {
    const result = await runReliability(workspace, ['--base-url', fixture.baseUrl, '--runs', '2']);

    assert.equal(result.code, 1);
    assert.equal(fixture.received.length, 4);
    const report = await readFile(output, 'utf8');
    assert.match(report, /\| 1 \| germany-capital \| MALFORMED_RESPONSE \|/);
    assert.match(report, /\| 1 \| munich-weather \| SEMANTIC_VALIDATION \|/);
    assert.doesNotMatch(`${report}\n${result.stderr}`, /raw answer secret|raw semantic secret|unavailable/i);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('CLI attestation timeout remains active while a headers-first response body stalls', async () => {
  const workspace = await makeCliWorkspace('attestation-body-timeout');
  const output = path.join(workspace, 'evaluation', 'reliability.md');
  const sentinel = 'previous verified reliability report\n';
  await writeFile(output, sentinel, { mode: 0o600 });
  const fixture = await startCliFixture(
    (_index, message) => responseForMessage(message),
    () => ({ stallBody: true })
  );
  fixture.attachWorkspace(workspace);
  try {
    const result = await runReliability(
      workspace,
      ['--base-url', fixture.baseUrl, '--runs', '2'],
      { shortenTimeouts: true, killAfterMs: 2_000 }
    );

    assert.equal(result.code, 1, `signal=${result.signal} stderr=${result.stderr}`);
    assert.equal(result.signal, null);
    assert.deepEqual(fixture.received, []);
    assert.equal(fixture.infoRequests(), 1);
    assert.equal(await readFile(output, 'utf8'), sentinel);
    assert.match(result.stderr, /^reliability: runtime build attestation could not be verified\n$/);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('CLI chat timeout remains active while a headers-first response body stalls and later cases continue', async () => {
  const workspace = await makeCliWorkspace('chat-body-timeout');
  const output = path.join(workspace, 'evaluation', 'reliability.md');
  const fixture = await startCliFixture((index, message) => index === 0
    ? { stallBody: true }
    : responseForMessage(message));
  fixture.attachWorkspace(workspace);
  try {
    const result = await runReliability(
      workspace,
      ['--base-url', fixture.baseUrl, '--runs', '2'],
      { shortenTimeouts: true, killAfterMs: 2_000 }
    );

    assert.equal(result.code, 1, `signal=${result.signal} stderr=${result.stderr}`);
    assert.equal(result.signal, null);
    assert.equal(fixture.received.length, 4);
    assert.equal(fixture.infoRequests(), 2);
    const report = await readFile(output, 'utf8');
    assert.match(report, /\| 1 \| germany-capital \| TIMEOUT \|/);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('CLI runtime commit mismatch blocks chat and preserves the previous report', async () => {
  const workspace = await makeCliWorkspace('runtime-mismatch');
  const output = path.join(workspace, 'evaluation', 'reliability.md');
  const sentinel = 'previous verified reliability report\n';
  await writeFile(output, sentinel, { mode: 0o600 });
  const fixture = await startCliFixture(
    (_index, message) => responseForMessage(message),
    (_index, attachedWorkspace) => {
      const commit = gitHead(attachedWorkspace);
      const replacement = commit.startsWith('0') ? '1' : '0';
      return { body: runtimeAttestation(attachedWorkspace, { commit: `${replacement}${commit.slice(1)}` }) };
    }
  );
  fixture.attachWorkspace(workspace);
  try {
    const result = await runReliability(workspace, ['--base-url', fixture.baseUrl, '--runs', '2']);

    assert.equal(result.code, 1);
    assert.deepEqual(fixture.received, []);
    assert.equal(fixture.infoRequests(), 1);
    assert.equal(await readFile(output, 'utf8'), sentinel);
    assert.match(result.stderr, /^reliability: runtime build attestation does not match local repository\n$/);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('CLI dirty worktree blocks runtime and chat requests and preserves the previous report', async () => {
  const workspace = await makeCliWorkspace('dirty');
  const output = path.join(workspace, 'evaluation', 'reliability.md');
  const sentinel = 'previous verified reliability report\n';
  await writeFile(output, sentinel, { mode: 0o600 });
  await writeFile(path.join(workspace, 'README.md'), 'dirty fixture repository\n');
  const fixture = await startCliFixture((_index, message) => responseForMessage(message));
  fixture.attachWorkspace(workspace);
  try {
    const result = await runReliability(workspace, ['--base-url', fixture.baseUrl, '--runs', '2']);

    assert.equal(result.code, 1);
    assert.deepEqual(fixture.received, []);
    assert.equal(fixture.infoRequests(), 0);
    assert.equal(await readFile(output, 'utf8'), sentinel);
    assert.match(result.stderr, /^reliability: non-ignored worktree is dirty\n$/);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('CLI rejects invalid runs, alternate output, and credential-bearing base URLs with exit 2', async () => {
  const workspace = await makeCliWorkspace('invalid-cli');
  const fixture = await startCliFixture((_index, message) => responseForMessage(message));
  fixture.attachWorkspace(workspace);
  const address = new URL(fixture.baseUrl);
  const scenarios = [
    ['--runs', '0'],
    ['--runs', '11'],
    ['--output', 'evaluation/other.md'],
    ['--base-url', `http://user:credential-secret@${address.host}`],
    ['--base-url', `${fixture.baseUrl}?token=query-secret`],
    ['--base-url', `${fixture.baseUrl}#fragment-secret`],
    ['--base-url', `${fixture.baseUrl}?`],
    ['--base-url', `${fixture.baseUrl}#`]
  ];
  try {
    for (const args of scenarios) {
      const result = await runReliability(workspace, args);
      assert.equal(result.code, 2, `${args.join(' ')}: ${result.stderr}`);
      assert.match(result.stderr, /^Usage: node scripts\/reliability\.mjs \[--base-url <URL>\] \[--runs <1-10>\]\n$/);
      assert.doesNotMatch(result.stderr, /credential-secret|query-secret|fragment-secret/);
    }
    assert.deepEqual(fixture.received, []);
    assert.equal(fixture.infoRequests(), 0);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('CLI rejects a symlink at evaluation/reliability.md without following it', async () => {
  const workspace = await makeCliWorkspace('symlink');
  const output = path.join(workspace, 'evaluation', 'reliability.md');
  const readme = path.join(workspace, 'README.md');
  const sentinel = await readFile(readme, 'utf8');
  await symlink(readme, output);
  const fixture = await startCliFixture((_index, message) => responseForMessage(message));
  fixture.attachWorkspace(workspace);
  try {
    const result = await runReliability(workspace, ['--base-url', fixture.baseUrl, '--runs', '2']);

    assert.equal(result.code, 2);
    assert.match(result.stderr, /^Usage:/);
    assert.deepEqual(fixture.received, []);
    assert.equal(fixture.infoRequests(), 0);
    assert.equal(await readFile(readme, 'utf8'), sentinel);
    assert.equal((await lstat(output)).isSymbolicLink(), true);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('CLI preserves the previous report when Git commit changes before atomic rename', async () => {
  const workspace = await makeCliWorkspace('git-drift');
  const output = path.join(workspace, 'evaluation', 'reliability.md');
  const sentinel = 'previous verified reliability report\n';
  await writeFile(output, sentinel, { mode: 0o600 });
  const fixture = await startCliFixture(
    (_index, message) => responseForMessage(message),
    (index, attachedWorkspace) => {
      const body = runtimeAttestation(attachedWorkspace);
      if (index === 1) {
        writeFileSync(path.join(attachedWorkspace, 'README.md'), 'committed during final attestation\n');
        execFileSync('git', ['add', 'README.md'], { cwd: attachedWorkspace });
        execFileSync('git', ['commit', '-qm', 'drift'], { cwd: attachedWorkspace });
      }
      return { body };
    }
  );
  fixture.attachWorkspace(workspace);
  try {
    const result = await runReliability(workspace, ['--base-url', fixture.baseUrl, '--runs', '2']);

    assert.equal(result.code, 1);
    assert.equal(fixture.received.length, 4);
    assert.equal(fixture.infoRequests(), 2);
    assert.equal(await readFile(output, 'utf8'), sentinel);
    assert.match(result.stderr, /^reliability: Git commit changed during evaluation\n$/);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('CLI preserves the previous report when runtime attestation changes before atomic rename', async () => {
  const workspace = await makeCliWorkspace('runtime-drift');
  const output = path.join(workspace, 'evaluation', 'reliability.md');
  const sentinel = 'previous verified reliability report\n';
  await writeFile(output, sentinel, { mode: 0o600 });
  const fixture = await startCliFixture(
    (_index, message) => responseForMessage(message),
    (index, attachedWorkspace) => {
      const commit = gitHead(attachedWorkspace);
      const replacement = commit.startsWith('0') ? '1' : '0';
      return {
        body: runtimeAttestation(
          attachedWorkspace,
          index === 0 ? {} : { commit: `${replacement}${commit.slice(1)}` }
        )
      };
    }
  );
  fixture.attachWorkspace(workspace);
  try {
    const result = await runReliability(workspace, ['--base-url', fixture.baseUrl, '--runs', '2']);

    assert.equal(result.code, 1);
    assert.equal(fixture.received.length, 4);
    assert.equal(fixture.infoRequests(), 2);
    assert.equal(await readFile(output, 'utf8'), sentinel);
    assert.match(result.stderr, /^reliability: runtime build attestation does not match local repository\n$/);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});
