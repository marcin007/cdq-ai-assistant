#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { lstat, mkdir, rename, unlink, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  CANONICAL_PROMPTS,
  EvaluationValidationError,
  MODEL,
  validateAnswerPayload
} from './evaluation-contract.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const evaluationRoot = path.join(root, 'evaluation');

class SafeError extends Error {}
class CliError extends SafeError {}

function usage() {
  return 'Usage: node scripts/evaluate.mjs [--base-url <URL>] [--output evaluation/answers.md]';
}

function parseArguments(argv) {
  let baseUrl = 'http://127.0.0.1:8080';
  let output = path.join(evaluationRoot, 'answers.md');
  for (let index = 0; index < argv.length; index += 1) {
    const option = argv[index];
    const value = argv[index + 1];
    if ((option !== '--base-url' && option !== '--output') || value === undefined) {
      throw new CliError(usage());
    }
    if (option === '--base-url') {
      baseUrl = value;
    } else {
      output = path.resolve(root, value);
    }
    index += 1;
  }

  let parsedBaseUrl;
  try {
    parsedBaseUrl = new URL(baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`);
  } catch {
    throw new CliError(usage());
  }
  if (!['http:', 'https:'].includes(parsedBaseUrl.protocol)
      || parsedBaseUrl.username
      || parsedBaseUrl.password
      || parsedBaseUrl.search
      || parsedBaseUrl.hash) {
    throw new CliError(usage());
  }

  if (output !== path.join(evaluationRoot, 'answers.md')) {
    throw new CliError(usage());
  }
  return { baseUrl: parsedBaseUrl, output };
}

async function validateOutputTarget(output) {
  try {
    const directoryStat = await lstat(evaluationRoot);
    if (directoryStat.isSymbolicLink() || !directoryStat.isDirectory()) {
      throw new CliError(usage());
    }
  } catch (error) {
    if (error instanceof CliError) {
      throw error;
    }
    if (error?.code !== 'ENOENT') {
      throw new CliError(usage());
    }
    return;
  }

  try {
    const outputStat = await lstat(output);
    if (outputStat.isSymbolicLink() || !outputStat.isFile()) {
      throw new CliError(usage());
    }
  } catch (error) {
    if (error instanceof CliError) {
      throw error;
    }
    if (error?.code !== 'ENOENT') {
      throw new CliError(usage());
    }
  }
}

function readRepositoryState(excludedPaths) {
  try {
    const commit = execFileSync('git', ['rev-parse', 'HEAD'], {
      cwd: root,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore']
    }).trim();
    if (!/^[0-9a-f]{40}$/i.test(commit)) {
      throw new Error('invalid commit');
    }
    const statusArguments = [
      'status',
      '--porcelain',
      '--untracked-files=normal',
      '--',
      '.',
      ...excludedPaths.map((excludedPath) => (
        `:(exclude,literal)${path.relative(root, excludedPath)}`
      ))
    ];
    const worktreeChanges = execFileSync(
      'git',
      statusArguments,
      {
        cwd: root,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore']
      }
    ).trim();
    if (worktreeChanges !== '') {
      throw new SafeError('non-ignored worktree is dirty');
    }
    return commit;
  } catch (error) {
    if (error instanceof SafeError) {
      throw error;
    }
    throw new SafeError('Git commit could not be recorded');
  }
}

async function validateRuntimeAttestation(baseUrl, expectedCommit) {
  const controller = new AbortController();
  const deadline = setTimeout(() => controller.abort(), 10_000);
  let response;
  try {
    response = await fetch(new URL('actuator/info', baseUrl), {
      headers: { Accept: 'application/json' },
      signal: controller.signal
    });
  } catch {
    throw new SafeError('runtime build attestation could not be verified');
  } finally {
    clearTimeout(deadline);
  }
  if (response.status !== 200) {
    throw new SafeError('runtime build attestation could not be verified');
  }

  let payload;
  try {
    payload = await response.json();
  } catch {
    throw new SafeError('runtime build attestation could not be verified');
  }
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)
      || !payload.build || typeof payload.build !== 'object' || Array.isArray(payload.build)
      || typeof payload.build.commit !== 'string'
      || !/^[0-9a-f]{40}$/i.test(payload.build.commit)
      || payload.build.commit.toLowerCase() !== expectedCommit.toLowerCase()
      || payload.build.worktreeClean !== true) {
    throw new SafeError('runtime build attestation does not match local repository');
  }
}

async function ask(baseUrl, prompt) {
  const startedAt = new Date().toISOString();
  const started = process.hrtime.bigint();
  const controller = new AbortController();
  const deadline = setTimeout(() => controller.abort(), 50_000);
  let response;
  try {
    response = await fetch(new URL('api/chat', baseUrl), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: prompt.question }),
      signal: controller.signal
    });
  } catch (error) {
    if (error?.name === 'AbortError') {
      throw new SafeError('request timed out');
    }
    throw new SafeError('request failed');
  } finally {
    clearTimeout(deadline);
  }
  const latencyMs = Number(process.hrtime.bigint() - started) / 1_000_000;
  if (response.status !== 200) {
    throw new SafeError(`HTTP ${response.status}`);
  }

  let payload;
  try {
    payload = await response.json();
  } catch {
    throw new SafeError('response is not valid JSON');
  }
  validateAnswerPayload(payload, prompt);
  return {
    id: prompt.id,
    question: prompt.question,
    startedAt,
    latencyMs: Math.round(latencyMs * 10) / 10,
    answer: payload.answer,
    sources: payload.sources
  };
}

function escapeHtml(value) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

function buildReport(evaluatedAt, commit, results) {
  const lines = [
    '# Live Evaluation Answers',
    '',
    `- Evaluated at (UTC): \`${evaluatedAt}\``,
    `- Git commit: \`${commit}\``,
    `- Chat model: \`${MODEL.chatModel}\``,
    `- Embedding model: \`${MODEL.embeddingModel}\``,
    `- Temperature: \`${MODEL.temperature}\``,
    `- Thinking: \`${MODEL.thinking}\``,
    `- Maximum output tokens per model call: \`${MODEL.maxOutputTokens}\``,
    '- Full non-ignored worktree: `clean`',
    '',
    '> This report contains observed responses from one live local run. Current weather is dynamic; rerun the evaluation for current results.',
    ''
  ];
  results.forEach((result, index) => {
    lines.push(
      `## ${index + 1}. ${result.id}`,
      '',
      '**Prompt**',
      '',
      `<pre>${escapeHtml(result.question)}</pre>`,
      '',
      `- Requested at (UTC): \`${result.startedAt}\``,
      `- Latency: \`${result.latencyMs} ms\``,
      '',
      '**Answer**',
      '',
      `<pre>${escapeHtml(result.answer)}</pre>`,
      '',
      '**Sources**',
      '',
      `<pre>${escapeHtml(JSON.stringify(result.sources, null, 2))}</pre>`,
      ''
    );
  });
  return `${lines.join('\n')}\n`;
}

async function writeAtomically(output, report, beforeRename) {
  const outputDirectory = path.dirname(output);
  const temporary = path.join(outputDirectory, `.answers.${process.pid}.tmp`);
  await mkdir(outputDirectory, { recursive: true, mode: 0o700 });
  try {
    await writeFile(temporary, report, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
    await beforeRename(temporary);
    await rename(temporary, output);
  } catch (error) {
    try {
      await unlink(temporary);
    } catch {
      // The temporary file may not exist; no final report was changed.
    }
    if (error instanceof SafeError) {
      throw error;
    }
    throw new SafeError('evaluation report could not be written');
  }
}

async function main() {
  let options;
  try {
    options = parseArguments(process.argv.slice(2));
    await validateOutputTarget(options.output);
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 2;
    return;
  }

  let commit;
  try {
    commit = readRepositoryState([options.output]);
    await validateRuntimeAttestation(options.baseUrl, commit);
  } catch (error) {
    process.stderr.write(`evaluate: ${error.message}\n`);
    process.exitCode = 1;
    return;
  }

  const evaluatedAt = new Date().toISOString();
  const results = [];
  for (const prompt of CANONICAL_PROMPTS) {
    try {
      results.push(await ask(options.baseUrl, prompt));
    } catch (error) {
      const reason = error instanceof SafeError || error instanceof EvaluationValidationError
        ? error.message
        : 'unexpected validation failure';
      process.stderr.write(`evaluate: ${prompt.id}: ${reason}\n`);
      process.exitCode = 1;
      return;
    }
  }

  try {
    await writeAtomically(
      options.output,
      buildReport(evaluatedAt, commit, results),
      async (temporary) => {
        await validateRuntimeAttestation(options.baseUrl, commit);
        await validateOutputTarget(options.output);
        const finalCommit = readRepositoryState([options.output, temporary]);
        if (finalCommit.toLowerCase() !== commit.toLowerCase()) {
          throw new SafeError('Git commit changed during evaluation');
        }
      }
    );
    process.stdout.write(`Evaluation report written to ${path.relative(root, options.output)}\n`);
  } catch (error) {
    process.stderr.write(`evaluate: ${error.message}\n`);
    process.exitCode = 1;
  }
}

await main();
