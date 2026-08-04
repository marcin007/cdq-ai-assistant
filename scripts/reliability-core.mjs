const SAFE_FAILURE_CATEGORIES = new Set([
  'TIMEOUT',
  'REQUEST_FAILED',
  'HTTP_4XX',
  'HTTP_5XX',
  'MALFORMED_RESPONSE',
  'SOURCE_VALIDATION',
  'SEMANTIC_VALIDATION'
]);

const SAFE_SOURCE_KINDS = new Set(['CDQ_RAG', 'REST_COUNTRIES', 'WEATHER']);
const SAFE_CASE_ID = /^[a-z][a-z0-9-]*$/;
const FAILURE_MESSAGE_CATEGORIES = new Map([
  ['source validation failed', 'SOURCE_VALIDATION'],
  ['semantic validation failed', 'SEMANTIC_VALIDATION'],
  ['response is malformed', 'MALFORMED_RESPONSE'],
  ['response malformed', 'MALFORMED_RESPONSE']
]);

function safeFailureCategory(category) {
  if (typeof category !== 'string') return 'REQUEST_FAILED';
  if (SAFE_FAILURE_CATEGORIES.has(category)) return category;
  return FAILURE_MESSAGE_CATEGORIES.get(category.toLowerCase()) ?? 'REQUEST_FAILED';
}

function assertRunCount(runs) {
  if (!Number.isInteger(runs) || runs < 1 || runs > 10) {
    throw new RangeError('runs must be an integer from 1 through 10');
  }
}

function assertCaseId(caseId) {
  if (typeof caseId !== 'string' || !SAFE_CASE_ID.test(caseId)) {
    throw new TypeError('invalid reliability case id');
  }
}

function compareCaseIds(left, right) {
  return left.caseId < right.caseId ? -1 : left.caseId > right.caseId ? 1 : 0;
}

function normalizeSuccess(caseId, run, response) {
  if (!response || typeof response !== 'object' || Array.isArray(response)
      || !Number.isFinite(response.latencyMs) || response.latencyMs < 0) {
    throw new ReliabilityCaseError('MALFORMED_RESPONSE');
  }
  if (!Array.isArray(response.sources) || !response.sources.every((source) => SAFE_SOURCE_KINDS.has(source))) {
    throw new ReliabilityCaseError('SOURCE_VALIDATION');
  }
  return Object.freeze({
    run,
    caseId,
    passed: true,
    latencyMs: response.latencyMs,
    sources: Object.freeze([...response.sources])
  });
}

function validateResult(result) {
  if (!result || typeof result !== 'object' || Array.isArray(result)
      || !Number.isInteger(result.run) || result.run < 1 || result.run > 10) {
    throw new TypeError('invalid reliability result');
  }
  if (typeof result.caseId !== 'string' || !SAFE_CASE_ID.test(result.caseId)) {
    throw new TypeError('invalid reliability result');
  }
  if (result.passed === true) {
    if (!Number.isFinite(result.latencyMs) || result.latencyMs < 0
        || !Array.isArray(result.sources) || !result.sources.every((source) => SAFE_SOURCE_KINDS.has(source))) {
      throw new TypeError('invalid reliability result');
    }
    return true;
  }
  if ((Object.hasOwn(result, 'passed') && result.passed !== false)
      || !SAFE_FAILURE_CATEGORIES.has(result.category)) {
    throw new TypeError('invalid reliability result');
  }
  return false;
}

function validateCompleteMatrix(results, runs) {
  if (results.length === 0) throw new TypeError('invalid reliability matrix');
  const runsByCase = new Map();
  for (const result of results) {
    if (result.run > runs) throw new TypeError('invalid reliability matrix');
    const seenRuns = runsByCase.get(result.caseId) ?? new Set();
    if (seenRuns.has(result.run)) throw new TypeError('invalid reliability matrix');
    seenRuns.add(result.run);
    runsByCase.set(result.caseId, seenRuns);
  }
  for (const seenRuns of runsByCase.values()) {
    if (seenRuns.size !== runs) throw new TypeError('invalid reliability matrix');
  }
}

function percentile(sortedValues, fraction) {
  if (sortedValues.length === 0) return null;
  return sortedValues[Math.ceil(sortedValues.length * fraction) - 1];
}

function sameSummary(summary, expected) {
  return summary && typeof summary === 'object'
    && summary.total === expected.total
    && summary.passed === expected.passed
    && summary.passRate === expected.passRate
    && summary.p50LatencyMs === expected.p50LatencyMs
    && summary.p95LatencyMs === expected.p95LatencyMs
    && Array.isArray(summary.perCase)
    && summary.perCase.length === expected.perCase.length
    && summary.perCase.every((entry, index) => entry
      && entry.caseId === expected.perCase[index].caseId
      && entry.passed === expected.perCase[index].passed
      && entry.total === expected.perCase[index].total
      && entry.passRate === expected.perCase[index].passRate);
}

function validateMetadata(metadata) {
  if (!metadata || typeof metadata !== 'object'
      || typeof metadata.evaluatedAt !== 'string'
      || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(metadata.evaluatedAt)
      || !/^[0-9a-f]{40}$/i.test(metadata.gitCommit)
      || metadata.chatModel !== 'qwen3:4b-instruct-2507-q4_K_M'
      || metadata.embeddingModel !== 'qwen3-embedding:0.6b') {
    throw new TypeError('invalid reliability report metadata');
  }
  assertRunCount(metadata.runs);
}

export class ReliabilityCaseError extends Error {
  constructor(category = 'REQUEST_FAILED') {
    super('reliability case failed');
    this.name = 'ReliabilityCaseError';
    this.category = safeFailureCategory(category);
  }
}

export async function runReliabilityMatrix({ prompts, runs, ask }) {
  assertRunCount(runs);
  if (!Array.isArray(prompts) || typeof ask !== 'function') {
    throw new TypeError('invalid reliability matrix input');
  }

  const results = [];
  for (let run = 1; run <= runs; run += 1) {
    for (const prompt of prompts) {
      assertCaseId(prompt?.id);
      try {
        const response = await ask(prompt);
        results.push(normalizeSuccess(prompt.id, run, response));
      } catch (error) {
        const category = error instanceof ReliabilityCaseError
          ? safeFailureCategory(error.category)
          : 'REQUEST_FAILED';
        results.push(Object.freeze({ run, caseId: prompt.id, category }));
      }
    }
  }
  return Object.freeze(results);
}

export function summarizeReliability(results) {
  if (!Array.isArray(results)) throw new TypeError('invalid reliability results');

  const cases = new Map();
  const latencies = [];
  let passed = 0;
  for (const result of results) {
    const success = validateResult(result);
    const caseSummary = cases.get(result.caseId) ?? { caseId: result.caseId, passed: 0, total: 0 };
    caseSummary.total += 1;
    if (success) {
      passed += 1;
      caseSummary.passed += 1;
      latencies.push(result.latencyMs);
    }
    cases.set(result.caseId, caseSummary);
  }

  latencies.sort((left, right) => left - right);
  const total = results.length;
  const perCase = [...cases.values()]
    .sort(compareCaseIds)
    .map((entry) => Object.freeze({
      ...entry,
      passRate: entry.total === 0 ? 0 : (entry.passed / entry.total) * 100
    }));
  return Object.freeze({
    total,
    passed,
    passRate: total === 0 ? 0 : (passed / total) * 100,
    p50LatencyMs: percentile(latencies, 0.5),
    p95LatencyMs: percentile(latencies, 0.95),
    perCase: Object.freeze(perCase)
  });
}

export function buildReliabilityReport(metadata, summary, results) {
  validateMetadata(metadata);
  const expectedSummary = summarizeReliability(results);
  validateCompleteMatrix(results, metadata.runs);
  if (!sameSummary(summary, expectedSummary)) {
    throw new TypeError('invalid reliability summary');
  }

  const latency = (value) => value === null ? 'n/a' : String(value);
  const lines = [
    '# Live Reliability Report',
    '',
    `- Evaluated at (UTC): \`${metadata.evaluatedAt}\``,
    `- Git commit: \`${metadata.gitCommit}\``,
    `- Chat model: \`${metadata.chatModel}\``,
    `- Embedding model: \`${metadata.embeddingModel}\``,
    `- Runs per case: \`${metadata.runs}\``,
    `- Overall: \`${expectedSummary.passed}/${expectedSummary.total}\``,
    `- Latency p50 / p95: \`${latency(expectedSummary.p50LatencyMs)} ms\` / \`${latency(expectedSummary.p95LatencyMs)} ms\``,
    '',
    '> Scope: This report measures the observed reliability of the whole local system, not only the model. Availability of dynamic upstream services contributes to these observations.',
    '',
    '## Per-case results',
    '',
    '| Case | Passed | Total | Pass rate |',
    '| --- | ---: | ---: | ---: |',
    ...expectedSummary.perCase.map((entry) => `| ${entry.caseId} | ${entry.passed} | ${entry.total} | ${entry.passRate}% |`),
    '',
    '## Successful observations',
    '',
    '| Run | Case | Latency | Attested source kinds |',
    '| ---: | --- | ---: | --- |',
    ...results
      .filter((result) => result.passed === true)
      .slice()
      .sort((left, right) => left.run - right.run || compareCaseIds(left, right))
      .map((result) => {
        const sources = result.sources.join(', ') || 'none';
        return `| ${result.run} | ${result.caseId} | ${result.latencyMs} ms | ${sources} |`;
      }),
    '',
    '## Observed failures',
    ''
  ];
  const failures = results
    .filter((result) => result.passed !== true)
    .slice()
    .sort((left, right) => left.run - right.run || compareCaseIds(left, right));
  if (failures.length === 0) {
    lines.push('No failures were observed in this run.');
  } else {
    lines.push(
      '| Run | Case | Safe category |',
      '| ---: | --- | --- |',
      ...failures.map((result) => `| ${result.run} | ${result.caseId} | ${result.category} |`)
    );
  }
  return `${lines.join('\n')}\n`;
}
