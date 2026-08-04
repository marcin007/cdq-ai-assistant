import { pathToFileURL } from 'node:url';

import { CANONICAL_PROMPTS, validateAnswerPayload } from './evaluation-contract.mjs';

const prompt = CANONICAL_PROMPTS.find(({ id }) => id === 'germany-capital-weather');

export class SmokeChatError extends Error {
  constructor(category) {
    super(category);
    this.name = 'SmokeChatError';
  }
}

export async function runSmoke({
  fetchImpl = globalThis.fetch,
  baseUrl = 'http://127.0.0.1:8080/',
  timeoutMs = 50_000
} = {}) {
  if (!prompt) throw new SmokeChatError('CONTRACT_MISSING');
  const controller = new AbortController();
  const deadline = setTimeout(() => controller.abort(), timeoutMs);
  try {
    let response;
    try {
      response = await fetchImpl(new URL('api/chat', baseUrl), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: prompt.question }),
        signal: controller.signal
      });
    } catch (error) {
      throw new SmokeChatError(error?.name === 'AbortError' ? 'TIMEOUT' : 'REQUEST_FAILED');
    }
    if (response.status !== 200) throw new SmokeChatError(`HTTP_${response.status}`);
    let payload;
    try {
      payload = await response.json();
    } catch (error) {
      throw new SmokeChatError(error?.name === 'AbortError' ? 'TIMEOUT' : 'INVALID_RESPONSE');
    }
    try {
      validateAnswerPayload(payload, prompt);
    } catch {
      throw new SmokeChatError('INVALID_RESPONSE');
    }
    return { id: prompt.id };
  } finally {
    clearTimeout(deadline);
  }
}

async function main() {
  try {
    const result = await runSmoke();
    process.stdout.write(`smoke-chat: passed ${result.id}\n`);
  } catch (error) {
    const category = error instanceof SmokeChatError ? error.message : 'UNEXPECTED_FAILURE';
    process.stderr.write(`smoke-chat: failed ${category}\n`);
    process.exitCode = 1;
  }
}

const entryUrl = process.argv[1] ? pathToFileURL(process.argv[1]).href : '';
if (import.meta.url === entryUrl) await main();
