#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { lstat, mkdir, rename, unlink, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  EvaluationValidationError,
  MODEL,
  RELIABILITY_PROMPTS,
  validateAnswerPayload
} from './evaluation-contract.mjs';
import {
  ReliabilityCaseError,
  buildReliabilityReport,
  runReliabilityMatrix,
  summarizeReliability
} from './reliability-core.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const evaluationRoot = path.join(root, 'evaluation');
const output = path.join(evaluationRoot, 'reliability.md');
const answersOutput = path.join(evaluationRoot, 'answers.md');

class SafeError extends Error {}
class CliError extends SafeError {}

function usage() {
  return 'Usage: node scripts/reliability.mjs [--base-url <URL>] [--runs <1-10>]';
}

function parseArguments(argv) {
  let baseUrl = 'http://127.0.0.1:8080';
  let runs = 3;
  const seen = new Set();
  for (let index = 0; index < argv.length; index += 1) {
    const option = argv[index];
    const value = argv[index + 1];
    if (!['--base-url', '--runs'].includes(option)
        || seen.has(option)
        || value === undefined) {
      throw new CliError(usage());
    }
    seen.add(option);
    if (option === '--base-url') {
      baseUrl = value;
    } else {
      if (!/^(?:[1-9]|10)$/.test(value)) throw new CliError(usage());
      runs = Number(value);
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
      || parsedBaseUrl.hostname === ''
      || parsedBaseUrl.username
      || parsedBaseUrl.password
      || parsedBaseUrl.search
      || parsedBaseUrl.hash) {
    throw new CliError(usage());
  }
  return { baseUrl: parsedBaseUrl, runs };
}

async function validateOutputTarget() {
  try {
    const directoryStat = await lstat(evaluationRoot);
    if (directoryStat.isSymbolicLink() || !directoryStat.isDirectory()) {
      throw new CliError(usage());
    }
  } catch (error) {
    if (error instanceof CliError) throw error;
    if (error?.code !== 'ENOENT') throw new CliError(usage());
    return;
  }

  try {
    const outputStat = await lstat(output);
    if (outputStat.isSymbolicLink() || !outputStat.isFile()) {
      throw new CliError(usage());
    }
  } catch (error) {
    if (error instanceof CliError) throw error;
    if (error?.code !== 'ENOENT') throw new CliError(usage());
  }
}

function readRepositoryState(transientPaths = []) {
  try {
    const commit = execFileSync('git', ['rev-parse', 'HEAD'], {
      cwd: root,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore']
    }).trim();
    if (!/^[0-9a-f]{40}$/i.test(commit)) throw new Error('invalid commit');

    const excludedPaths = [answersOutput, output, ...transientPaths];
    const worktreeChanges = execFileSync(
      'git',
      [
        'status',
        '--porcelain',
        '--untracked-files=normal',
        '--',
        '.',
        ...excludedPaths.map((excludedPath) => (
          `:(exclude,literal)${path.relative(root, excludedPath)}`
        ))
      ],
      {
        cwd: root,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore']
      }
    ).trim();
    if (worktreeChanges !== '') throw new SafeError('non-ignored worktree is dirty');
    return commit;
  } catch (error) {
    if (error instanceof SafeError) throw error;
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
      redirect: 'error',
      signal: controller.signal
    });
  } catch {
    clearTimeout(deadline);
    throw new SafeError('runtime build attestation could not be verified');
  }
  if (response.status !== 200) {
    controller.abort();
    clearTimeout(deadline);
    throw new SafeError('runtime build attestation could not be verified');
  }

  let payload;
  try {
    payload = await response.json();
  } catch {
    throw new SafeError('runtime build attestation could not be verified');
  } finally {
    clearTimeout(deadline);
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

function validationCategory(error) {
  if (!(error instanceof EvaluationValidationError)) return 'REQUEST_FAILED';
  if (error.message === 'answer failed semantic validation') return 'SEMANTIC_VALIDATION';
  if (error.message === 'response sources are malformed'
      || error.message === 'source kind/order validation failed') {
    return 'SOURCE_VALIDATION';
  }
  return 'MALFORMED_RESPONSE';
}

async function ask(baseUrl, prompt) {
  const started = process.hrtime.bigint();
  const controller = new AbortController();
  const deadline = setTimeout(() => controller.abort(), 50_000);
  let response;
  try {
    response = await fetch(new URL('api/chat', baseUrl), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: prompt.question }),
      redirect: 'error',
      signal: controller.signal
    });
  } catch (error) {
    clearTimeout(deadline);
    if (error?.name === 'AbortError') throw new ReliabilityCaseError('TIMEOUT');
    throw new ReliabilityCaseError('REQUEST_FAILED');
  }

  if (response.status >= 400 && response.status <= 499) {
    controller.abort();
    clearTimeout(deadline);
    throw new ReliabilityCaseError('HTTP_4XX');
  }
  if (response.status >= 500 && response.status <= 599) {
    controller.abort();
    clearTimeout(deadline);
    throw new ReliabilityCaseError('HTTP_5XX');
  }
  if (response.status !== 200) {
    controller.abort();
    clearTimeout(deadline);
    throw new ReliabilityCaseError('REQUEST_FAILED');
  }

  let payload;
  try {
    payload = await response.json();
  } catch (error) {
    if (controller.signal.aborted || error?.name === 'AbortError') {
      throw new ReliabilityCaseError('TIMEOUT');
    }
    throw new ReliabilityCaseError('MALFORMED_RESPONSE');
  } finally {
    clearTimeout(deadline);
  }
  try {
    validateAnswerPayload(payload, prompt);
  } catch (error) {
    throw new ReliabilityCaseError(validationCategory(error));
  }
  const latencyMs = Number(process.hrtime.bigint() - started) / 1_000_000;
  return {
    latencyMs: Math.round(latencyMs * 10) / 10,
    sources: payload.sources.map((source) => source.kind)
  };
}

async function writeAtomically(report, beforeRename) {
  const temporary = path.join(evaluationRoot, `.reliability.${process.pid}.tmp`);
  await mkdir(evaluationRoot, { recursive: true, mode: 0o700 });
  try {
    await writeFile(temporary, report, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
    await beforeRename(temporary);
    await rename(temporary, output);
  } catch (error) {
    try {
      await unlink(temporary);
    } catch {
      // The temporary file may not exist; the previous report remains unchanged.
    }
    if (error instanceof SafeError) throw error;
    throw new SafeError('reliability report could not be written');
  }
}

async function main() {
  let options;
  try {
    options = parseArguments(process.argv.slice(2));
    await validateOutputTarget();
  } catch {
    process.stderr.write(`${usage()}\n`);
    process.exitCode = 2;
    return;
  }

  let commit;
  try {
    commit = readRepositoryState();
    await validateRuntimeAttestation(options.baseUrl, commit);
  } catch (error) {
    const reason = error instanceof SafeError ? error.message : 'preflight failed';
    process.stderr.write(`reliability: ${reason}\n`);
    process.exitCode = 1;
    return;
  }

  const evaluatedAt = new Date().toISOString();
  let results;
  let report;
  try {
    results = await runReliabilityMatrix({
      prompts: RELIABILITY_PROMPTS,
      runs: options.runs,
      ask: (prompt) => ask(options.baseUrl, prompt)
    });
    const summary = summarizeReliability(results);
    report = buildReliabilityReport({
      evaluatedAt,
      gitCommit: commit,
      chatModel: MODEL.chatModel,
      embeddingModel: MODEL.embeddingModel,
      runs: options.runs
    }, summary, results);
  } catch {
    process.stderr.write('reliability: reliability evaluation could not be completed\n');
    process.exitCode = 1;
    return;
  }

  try {
    await writeAtomically(report, async (temporary) => {
      await validateRuntimeAttestation(options.baseUrl, commit);
      await validateOutputTarget();
      const finalCommit = readRepositoryState([temporary]);
      if (finalCommit.toLowerCase() !== commit.toLowerCase()) {
        throw new SafeError('Git commit changed during evaluation');
      }
    });
    process.stdout.write('Reliability report written to evaluation/reliability.md\n');
    process.exitCode = results.every((result) => result.passed === true) ? 0 : 1;
  } catch (error) {
    const reason = error instanceof SafeError ? error.message : 'reliability report could not be written';
    process.stderr.write(`reliability: ${reason}\n`);
    process.exitCode = 1;
  }
}

await main();
