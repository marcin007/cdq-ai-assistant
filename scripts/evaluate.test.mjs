import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import {
  copyFile,
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
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sourceContract = path.join(root, 'scripts', 'evaluation-contract.mjs');
const sourceEvaluator = path.join(root, 'scripts', 'evaluate.mjs');

test('shared evaluation contract exposes six canonical and twelve reliability cases', async () => {
  const contract = await import('./evaluation-contract.mjs');
  assert.equal(contract.CANONICAL_PROMPTS.length, 6);
  assert.equal(contract.RELIABILITY_PROMPTS.length, 12);
  assert.deepEqual(
    [...new Set(contract.CANONICAL_PROMPTS.flatMap((prompt) => prompt.kinds))].sort(),
    ['CDQ_RAG', 'REST_COUNTRIES', 'WEATHER']
  );
  assert.deepEqual(
    contract.RELIABILITY_PROMPTS.slice(6).map(({ id, question, kinds }) => ({
      id,
      question,
      kinds
    })),
    [
      {
        id: 'germany-capital-paraphrase',
        question: 'Name Germany\'s capital city.',
        kinds: ['REST_COUNTRIES']
      },
      {
        id: 'munich-weather-paraphrase',
        question: 'How many degrees Celsius is it in Munich right now?',
        kinds: ['WEATHER']
      },
      {
        id: 'germany-capital-weather-paraphrase',
        question: 'Find Germany\'s capital, then report its current temperature.',
        kinds: ['REST_COUNTRIES', 'WEATHER']
      },
      {
        id: 'berlin-country-paraphrase',
        question: 'Which country has Berlin as its capital?',
        kinds: ['REST_COUNTRIES']
      },
      {
        id: 'cdq-payment-fraud-paraphrase',
        question: 'How does CDQ Fraud Guard reduce payment fraud risk?',
        kinds: ['CDQ_RAG']
      },
      {
        id: 'japan-capital-weather-paraphrase',
        question: 'Use Japan\'s capital to tell me the current temperature there.',
        kinds: ['REST_COUNTRIES', 'WEATHER']
      }
    ]
  );
});

const prompts = [
  'What is the capital city of Germany?',
  'What is the temperature currently in Munich?',
  'What is the temperature of the capital of Germany currently?',
  'What do you know about Berlin?',
  'Which CDQ Fraud Guard features help prevent payment fraud?',
  'What is Japan’s capital and what is the current temperature there?'
];

const promptIds = [
  'germany-capital',
  'munich-weather',
  'germany-capital-weather',
  'berlin-country',
  'cdq-payment-fraud',
  'japan-capital-weather'
];

const source = {
  countries: {
    kind: 'REST_COUNTRIES',
    label: 'REST Countries v5',
    url: 'https://restcountries.com/'
  },
  weather: {
    kind: 'WEATHER',
    label: 'WeatherAPI via semdin/mcp-weather',
    url: 'https://github.com/semdin/mcp-weather'
  },
  cdq: {
    kind: 'CDQ_RAG',
    label: 'CDQ Fraud Guard',
    url: 'https://www.cdq.com/products/cdq-fraud-guard'
  }
};

const successfulResponses = [
  { answer: 'Berlin is the capital city of Germany.', sources: [source.countries] },
  { answer: 'The current temperature in Munich is 18.4 °C.', sources: [source.weather] },
  {
    answer: 'Germany’s capital is Berlin, where the current temperature is 17 C.',
    sources: [source.countries, source.weather]
  },
  {
    answer: 'Berlin is the capital and largest city of Germany.',
    sources: [source.countries]
  },
  {
    answer: 'CDQ Fraud Guard helps prevent fraud with Bank Account Verification, Trust Scores, and alerts.',
    sources: [source.cdq]
  },
  {
    answer: 'Japan’s capital is Tokyo, where the current temperature is 24°C.',
    sources: [source.countries, source.weather]
  }
];

function runEvaluator(workspace, args) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [path.join(workspace, 'scripts', 'evaluate.mjs'), ...args], {
      cwd: workspace,
      stdio: ['ignore', 'pipe', 'pipe']
    });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('close', (code, signal) => {
      setTimeout(() => resolve({ code, signal, stdout, stderr }), 0);
    });
  });
}

function gitHead(workspace) {
  return execFileSync('git', ['rev-parse', 'HEAD'], {
    cwd: workspace,
    encoding: 'utf8'
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

async function startFixture(responseForIndex, runtimeInfoForIndex) {
  const received = [];
  let infoRequestCount = 0;
  let active = 0;
  let maximumActive = 0;
  const server = http.createServer((request, response) => {
    active += 1;
    maximumActive = Math.max(maximumActive, active);
    let body = '';
    request.setEncoding('utf8');
    request.on('data', (chunk) => {
      body += chunk;
    });
    request.on('end', () => {
      try {
        let fixture;
        if (request.method === 'GET' && request.url === '/actuator/info') {
          fixture = runtimeInfoForIndex(infoRequestCount);
          infoRequestCount += 1;
        } else if (request.method === 'POST' && request.url === '/api/chat') {
          const parsed = JSON.parse(body);
          received.push(parsed.message);
          fixture = responseForIndex(received.length - 1);
        } else {
          fixture = { status: 404, body: { error: 'not found' } };
        }
        response.writeHead(fixture.status ?? 200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(fixture.body));
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
    infoRequests: () => infoRequestCount,
    maximumActive: () => maximumActive,
    close: () => new Promise((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
      server.closeAllConnections();
    })
  };
}

async function makeWorkspace(label) {
  const workspace = await mkdtemp(path.join(os.tmpdir(), `cdq-evaluate-${label}-`));
  await mkdir(path.join(workspace, 'scripts'), { recursive: true });
  await mkdir(path.join(workspace, 'evaluation'), { recursive: true });
  await copyFile(sourceContract, path.join(workspace, 'scripts', 'evaluation-contract.mjs'));
  await copyFile(sourceEvaluator, path.join(workspace, 'scripts', 'evaluate.mjs'));
  await writeFile(path.join(workspace, 'README.md'), 'fixture repository\n');
  execFileSync('git', ['init', '-q'], { cwd: workspace });
  execFileSync('git', ['config', 'user.email', 'test@example.invalid'], { cwd: workspace });
  execFileSync('git', ['config', 'user.name', 'Evaluator test'], { cwd: workspace });
  execFileSync('git', ['add', 'README.md', 'scripts/evaluation-contract.mjs', 'scripts/evaluate.mjs'], { cwd: workspace });
  execFileSync('git', ['commit', '-qm', 'fixture'], { cwd: workspace });
  return realpath(workspace);
}

test('records all six approved prompts sequentially with provenance only after validation', async () => {
  const workspace = await makeWorkspace('success');
  const output = path.join(workspace, 'evaluation', 'answers.md');
  const fixture = await startFixture(
    (index) => ({ body: successfulResponses[index] }),
    () => ({ body: runtimeAttestation(workspace) })
  );
  try {
    const result = await runEvaluator(workspace, [
      '--base-url', fixture.baseUrl,
      '--output', output
    ]);
    assert.equal(result.code, 0, result.stderr);
    assert.equal(result.signal, null);
    assert.deepEqual(fixture.received, prompts);
    assert.equal(fixture.infoRequests(), 2);
    assert.equal(fixture.maximumActive(), 1);

    const report = await readFile(output, 'utf8');
    const commit = gitHead(workspace);
    assert.match(report, /^# Live Evaluation Answers/m);
    assert.match(report, /Evaluated at \(UTC\): `\d{4}-\d{2}-\d{2}T/);
    assert.match(report, new RegExp(`Git commit: \`${commit}\``));
    assert.match(report, /Chat model: `qwen3:4b-instruct-2507-q4_K_M`/);
    assert.match(report, /Embedding model: `qwen3-embedding:0\.6b`/);
    assert.match(report, /Temperature: `0\.1`/);
    assert.match(report, /Thinking: `unavailable \(Instruct-only\)`/);
    assert.match(report, /Maximum output tokens per model call: `256`/);
    assert.match(report, /Full non-ignored worktree: `clean`/);
    for (const prompt of prompts) {
      assert.match(report, new RegExp(prompt.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
    }
    assert.match(report, /"kind": "REST_COUNTRIES"/);
    assert.match(report, /"kind": "WEATHER"/);
    assert.match(report, /"kind": "CDQ_RAG"/);
    assert.match(report, /Latency: `\d+(?:\.\d+)? ms`/);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('accepts natural possessive-capital wording with explicitly associated temperatures', async () => {
  const workspace = await makeWorkspace('natural-wording');
  const output = path.join(workspace, 'evaluation', 'answers.md');
  const naturalResponses = [
    { ...successfulResponses[0], answer: "Berlin is Germany's capital." },
    successfulResponses[1],
    {
      ...successfulResponses[2],
      answer: 'Berlin is Germany’s capital. The current temperature in Berlin is 17 C.'
    },
    {
      ...successfulResponses[3],
      answer: "Berlin is Germany's capital and largest city."
    },
    successfulResponses[4],
    {
      ...successfulResponses[5],
      answer: "Tokyo is Japan's capital. The current temperature in Tokyo is 24°C."
    }
  ];
  const fixture = await startFixture(
    (index) => ({ body: naturalResponses[index] }),
    () => ({ body: runtimeAttestation(workspace) })
  );
  try {
    const result = await runEvaluator(workspace, [
      '--base-url', fixture.baseUrl,
      '--output', output
    ]);
    assert.equal(result.code, 0, result.stderr);
    assert.deepEqual(fixture.received, prompts);
    assert.equal(fixture.infoRequests(), 2);
    assert.match(await readFile(output, 'utf8'), /# Live Evaluation Answers/);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('rejects adversarial answers that break the required entity relationships', async (t) => {
  const scenarios = [
    {
      label: 'negated-germany-capital',
      index: 0,
      answer: 'Berlin is not the capital city of Germany; Munich is.'
    },
    {
      label: 'quoted-false-germany-capital',
      index: 0,
      answer: 'The claim "Berlin is the capital city of Germany" is false.'
    },
    {
      label: 'munich-temperature-unavailable',
      index: 1,
      answer: 'The current temperature in Munich is unavailable in Celsius.'
    },
    {
      label: 'nearby-city-temperature-pronoun',
      index: 1,
      answer: 'Munich is near Augsburg, where it is currently 18 °C.'
    },
    {
      label: 'wrong-city-for-germany-capital-temperature',
      index: 2,
      answer: 'Germany’s capital is Berlin, but Munich is currently 18 °C.'
    },
    {
      label: 'negated-berlin-capital',
      index: 3,
      answer: 'Berlin is not the capital city of Germany.'
    },
    {
      label: 'misleading-cdq-denial',
      index: 4,
      answer: 'CDQ Fraud Guard does not prevent fraud; Bank Account Verification and Trust Score are unavailable.'
    },
    {
      label: 'feature-from-another-product',
      index: 4,
      answer: 'CDQ Fraud Guard prevents fraud. Bank Account Verification belongs to another product.'
    },
    {
      label: 'api-substring-is-not-a-feature',
      index: 4,
      answer: 'CDQ Fraud Guard prevents fraud in capital markets.'
    },
    {
      label: 'wrong-city-for-japan-capital-temperature',
      index: 5,
      answer: 'Japan’s capital is Tokyo, while Osaka is currently 24 °C.'
    }
  ];

  for (const scenario of scenarios) {
    await t.test(scenario.label, async () => {
      const workspace = await makeWorkspace(scenario.label);
      const output = path.join(workspace, 'evaluation', 'answers.md');
      const fixture = await startFixture(
        (index) => ({
          body: index === scenario.index
            ? { ...successfulResponses[index], answer: scenario.answer }
            : successfulResponses[index]
        }),
        () => ({ body: runtimeAttestation(workspace) })
      );
      try {
        const result = await runEvaluator(workspace, [
          '--base-url', fixture.baseUrl,
          '--output', output
        ]);
        assert.equal(result.code, 1);
        assert.deepEqual(fixture.received, prompts.slice(0, scenario.index + 1));
        assert.equal(fixture.infoRequests(), 1);
        assert.match(
          result.stderr,
          new RegExp(`evaluate: ${promptIds[scenario.index]}: answer failed semantic validation`)
        );
        assert.doesNotMatch(result.stderr, new RegExp(scenario.answer.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
        await assert.rejects(readFile(output, 'utf8'), { code: 'ENOENT' });
      } finally {
        await fixture.close();
        await rm(workspace, { recursive: true, force: true });
      }
    });
  }
});

test('a semantic failure on request five leaves an existing report byte-identical', async () => {
  const workspace = await makeWorkspace('semantic-failure');
  const output = path.join(workspace, 'evaluation', 'answers.md');
  const sentinel = 'previous verified live report\n';
  await writeFile(output, sentinel, { mode: 0o600 });
  const fixture = await startFixture(
    (index) => ({
      body: index === 4
        ? { answer: 'No product information was found.', sources: [source.cdq] }
        : successfulResponses[index]
    }),
    () => ({ body: runtimeAttestation(workspace) })
  );
  try {
    const result = await runEvaluator(workspace, [
      '--base-url', fixture.baseUrl,
      '--output', output
    ]);
    assert.equal(result.code, 1);
    assert.deepEqual(fixture.received, prompts.slice(0, 5));
    assert.match(result.stderr, /evaluate: cdq-payment-fraud: answer failed semantic validation/);
    assert.doesNotMatch(result.stderr, /No product information was found/);
    assert.equal(await readFile(output, 'utf8'), sentinel);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('a non-canonical source record is rejected before a report is written', async () => {
  const workspace = await makeWorkspace('source-failure');
  const output = path.join(workspace, 'evaluation', 'answers.md');
  const fixture = await startFixture(
    (index) => ({
      body: index === 0
        ? {
            ...successfulResponses[0],
            sources: [{ ...source.countries, label: 'Unverified countries source' }]
          }
        : successfulResponses[index]
    }),
    () => ({ body: runtimeAttestation(workspace) })
  );
  try {
    const result = await runEvaluator(workspace, [
      '--base-url', fixture.baseUrl,
      '--output', output
    ]);
    assert.equal(result.code, 1);
    assert.deepEqual(fixture.received, prompts.slice(0, 1));
    assert.match(result.stderr, /evaluate: germany-capital: response sources are malformed/);
    await assert.rejects(readFile(output, 'utf8'), { code: 'ENOENT' });
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('dirty tracked files block requests and leave an existing report byte-identical', async () => {
  const workspace = await makeWorkspace('dirty');
  const output = path.join(workspace, 'evaluation', 'answers.md');
  const sentinel = 'previous verified live report\n';
  await writeFile(output, sentinel, { mode: 0o600 });
  await writeFile(path.join(workspace, 'README.md'), 'dirty fixture repository\n');
  const fixture = await startFixture(
    (index) => ({ body: successfulResponses[index] }),
    () => ({ body: runtimeAttestation(workspace) })
  );
  try {
    const result = await runEvaluator(workspace, [
      '--base-url', fixture.baseUrl,
      '--output', output
    ]);
    assert.equal(result.code, 1);
    assert.deepEqual(fixture.received, []);
    assert.match(result.stderr, /evaluate: non-ignored worktree is dirty/);
    assert.equal(await readFile(output, 'utf8'), sentinel);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('an unexpected untracked file blocks requests and preserves the existing report', async () => {
  const workspace = await makeWorkspace('untracked-dirty');
  const output = path.join(workspace, 'evaluation', 'answers.md');
  const sentinel = 'previous verified live report\n';
  await writeFile(output, sentinel, { mode: 0o600 });
  await writeFile(path.join(workspace, 'unexpected.txt'), 'not part of the evaluated build\n');
  const fixture = await startFixture(
    (index) => ({ body: successfulResponses[index] }),
    () => ({ body: runtimeAttestation(workspace) })
  );
  try {
    const result = await runEvaluator(workspace, [
      '--base-url', fixture.baseUrl,
      '--output', output
    ]);
    assert.equal(result.code, 1);
    assert.deepEqual(fixture.received, []);
    assert.equal(fixture.infoRequests(), 0);
    assert.match(result.stderr, /evaluate: non-ignored worktree is dirty/);
    assert.equal(await readFile(output, 'utf8'), sentinel);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('a mismatched or dirty runtime build blocks chat requests and report creation', async (t) => {
  const scenarios = [
    {
      label: 'runtime-commit-mismatch',
      runtimeOverrides: (workspace) => {
        const commit = gitHead(workspace);
        const replacement = commit.startsWith('0') ? '1' : '0';
        return { commit: `${replacement}${commit.slice(1)}` };
      }
    },
    {
      label: 'runtime-dirty',
      runtimeOverrides: () => ({ worktreeClean: false })
    }
  ];

  for (const scenario of scenarios) {
    await t.test(scenario.label, async () => {
      const workspace = await makeWorkspace(scenario.label);
      const output = path.join(workspace, 'evaluation', 'answers.md');
      const fixture = await startFixture(
        (index) => ({ body: successfulResponses[index] }),
        () => ({ body: runtimeAttestation(workspace, scenario.runtimeOverrides(workspace)) })
      );
      try {
        const result = await runEvaluator(workspace, [
          '--base-url', fixture.baseUrl,
          '--output', output
        ]);
        assert.equal(result.code, 1);
        assert.deepEqual(fixture.received, []);
        assert.equal(fixture.infoRequests(), 1);
        assert.match(result.stderr, /evaluate: runtime build attestation does not match local repository/);
        await assert.rejects(readFile(output, 'utf8'), { code: 'ENOENT' });
      } finally {
        await fixture.close();
        await rm(workspace, { recursive: true, force: true });
      }
    });
  }
});

test('a local worktree change during the six requests preserves the existing report', async () => {
  const workspace = await makeWorkspace('local-drift');
  const output = path.join(workspace, 'evaluation', 'answers.md');
  const sentinel = 'previous verified live report\n';
  await writeFile(output, sentinel, { mode: 0o600 });
  const fixture = await startFixture(
    (index) => {
      if (index === 5) {
        writeFileSync(path.join(workspace, 'README.md'), 'changed while evaluation was running\n');
      }
      return { body: successfulResponses[index] };
    },
    () => ({ body: runtimeAttestation(workspace) })
  );
  try {
    const result = await runEvaluator(workspace, [
      '--base-url', fixture.baseUrl,
      '--output', output
    ]);
    assert.equal(result.code, 1);
    assert.deepEqual(fixture.received, prompts);
    assert.equal(fixture.infoRequests(), 2);
    assert.match(result.stderr, /evaluate: non-ignored worktree is dirty/);
    assert.equal(await readFile(output, 'utf8'), sentinel);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('runtime build drift after the six requests preserves the existing report', async () => {
  const workspace = await makeWorkspace('runtime-drift');
  const output = path.join(workspace, 'evaluation', 'answers.md');
  const sentinel = 'previous verified live report\n';
  await writeFile(output, sentinel, { mode: 0o600 });
  const commit = gitHead(workspace);
  const replacement = commit.startsWith('0') ? '1' : '0';
  const mismatchedCommit = `${replacement}${commit.slice(1)}`;
  const fixture = await startFixture(
    (index) => ({ body: successfulResponses[index] }),
    (index) => ({
      body: runtimeAttestation(workspace, index === 0 ? {} : { commit: mismatchedCommit })
    })
  );
  try {
    const result = await runEvaluator(workspace, [
      '--base-url', fixture.baseUrl,
      '--output', output
    ]);
    assert.equal(result.code, 1);
    assert.deepEqual(fixture.received, prompts);
    assert.equal(fixture.infoRequests(), 2);
    assert.match(result.stderr, /evaluate: runtime build attestation does not match local repository/);
    assert.equal(await readFile(output, 'utf8'), sentinel);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('a local change during the final runtime attestation preserves the existing report', async () => {
  const workspace = await makeWorkspace('final-attestation-local-drift');
  const output = path.join(workspace, 'evaluation', 'answers.md');
  const sentinel = 'previous verified live report\n';
  await writeFile(output, sentinel, { mode: 0o600 });
  const fixture = await startFixture(
    (index) => ({ body: successfulResponses[index] }),
    (index) => {
      if (index === 1) {
        writeFileSync(path.join(workspace, 'README.md'), 'changed during final attestation\n');
      }
      return { body: runtimeAttestation(workspace) };
    }
  );
  try {
    const result = await runEvaluator(workspace, [
      '--base-url', fixture.baseUrl,
      '--output', output
    ]);
    assert.equal(result.code, 1);
    assert.deepEqual(fixture.received, prompts);
    assert.equal(fixture.infoRequests(), 2);
    assert.match(result.stderr, /evaluate: non-ignored worktree is dirty/);
    assert.equal(await readFile(output, 'utf8'), sentinel);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('an alternate file under evaluation is rejected before contacting the service', async () => {
  const workspace = await makeWorkspace('alternate-output');
  const output = path.join(workspace, 'evaluation', 'README.md');
  const fixture = await startFixture(
    (index) => ({ body: successfulResponses[index] }),
    () => ({ body: runtimeAttestation(workspace) })
  );
  try {
    const result = await runEvaluator(workspace, [
      '--base-url', fixture.baseUrl,
      '--output', output
    ]);
    assert.equal(result.code, 2);
    assert.match(result.stderr, /Usage:/);
    assert.deepEqual(fixture.received, []);
    assert.equal(fixture.infoRequests(), 0);
    await assert.rejects(readFile(output, 'utf8'), { code: 'ENOENT' });
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('a symlink at evaluation/answers.md is rejected before contacting the service', async () => {
  const workspace = await makeWorkspace('symlink-output');
  const output = path.join(workspace, 'evaluation', 'answers.md');
  const readme = path.join(workspace, 'README.md');
  const sentinel = await readFile(readme, 'utf8');
  await symlink(readme, output);
  const fixture = await startFixture(
    (index) => ({ body: successfulResponses[index] }),
    () => ({ body: runtimeAttestation(workspace) })
  );
  try {
    const result = await runEvaluator(workspace, [
      '--base-url', fixture.baseUrl,
      '--output', output
    ]);
    assert.equal(result.code, 2);
    assert.match(result.stderr, /Usage:/);
    assert.deepEqual(fixture.received, []);
    assert.equal(fixture.infoRequests(), 0);
    assert.equal(await readFile(readme, 'utf8'), sentinel);
  } finally {
    await fixture.close();
    await rm(workspace, { recursive: true, force: true });
  }
});

test('invalid CLI output scope exits 2 without contacting a service', async () => {
  const workspace = await makeWorkspace('cli');
  try {
    const result = await runEvaluator(workspace, ['--output', path.join(workspace, 'README.md')]);
    assert.equal(result.code, 2);
    assert.match(result.stderr, /Usage:/);
  } finally {
    await rm(workspace, { recursive: true, force: true });
  }
});
