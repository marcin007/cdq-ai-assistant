import assert from 'node:assert/strict';
import test from 'node:test';

import { runSmoke, SmokeChatError } from './smoke-chat.mjs';

const success = {
  answer: 'Berlin is the capital of Germany. The current temperature in Berlin is 18 °C.',
  sources: [
    { kind: 'REST_COUNTRIES', label: 'REST Countries v5', url: 'https://restcountries.com/' },
    { kind: 'WEATHER', label: 'WeatherAPI via semdin/mcp-weather', url: 'https://github.com/semdin/mcp-weather' }
  ]
};

test('sends only the canonical capital-weather prompt and returns no answer data', async () => {
  let request;
  const result = await runSmoke({
    fetchImpl: async (url, options) => {
      request = { url: String(url), options };
      return { ok: true, status: 200, json: async () => success };
    }
  });
  assert.equal(request.url, 'http://127.0.0.1:8080/api/chat');
  assert.deepEqual(JSON.parse(request.options.body), {
    message: 'What is the temperature of the capital of Germany currently?'
  });
  assert.deepEqual(result, { id: 'germany-capital-weather' });
});

test('maps transport and invalid-response failures to safe categories', async () => {
  await assert.rejects(
    runSmoke({ fetchImpl: async () => { throw new Error('raw-provider fake-api-key'); } }),
    (error) => error instanceof SmokeChatError
      && error.message === 'REQUEST_FAILED'
      && !error.message.includes('fake-api-key')
  );
  await assert.rejects(
    runSmoke({ fetchImpl: async () => ({ ok: false, status: 504 }) }),
    { name: 'SmokeChatError', message: 'HTTP_504' }
  );
  await assert.rejects(
    runSmoke({ fetchImpl: async () => ({
      ok: true,
      status: 200,
      json: async () => { throw new Error('raw body fake-api-key'); }
    }) }),
    (error) => error instanceof SmokeChatError
      && error.message === 'INVALID_RESPONSE'
      && !error.message.includes('raw body')
      && !error.message.includes('fake-api-key')
  );
  const aborted = new Error('raw abort detail');
  aborted.name = 'AbortError';
  await assert.rejects(
    runSmoke({ fetchImpl: async () => { throw aborted; } }),
    { name: 'SmokeChatError', message: 'TIMEOUT' }
  );
});

test('rejects every successful status other than HTTP 200 without reading its body', async () => {
  let bodyRead = false;

  await assert.rejects(
    runSmoke({ fetchImpl: async () => ({
      ok: true,
      status: 201,
      statusText: 'raw provider detail fake-api-key',
      json: async () => {
        bodyRead = true;
        return success;
      }
    }) }),
    (error) => error instanceof SmokeChatError
      && error.message === 'HTTP_201'
      && !error.message.includes('raw provider detail')
      && !error.message.includes('fake-api-key')
  );
  assert.equal(bodyRead, false);
});

test('keeps the deadline active while the response body is pending', async () => {
  const timeoutMs = 20;
  const startedAt = Date.now();
  let watchdog;
  const watchdogFailure = new Promise((resolve, reject) => {
    watchdog = setTimeout(() => reject(new Error('smoke test watchdog expired')), 250);
  });

  try {
    await assert.rejects(
      Promise.race([
        runSmoke({
          timeoutMs,
          fetchImpl: async (url, { signal }) => ({
            ok: true,
            status: 200,
            json: async () => new Promise((resolve, reject) => {
              signal.addEventListener('abort', () => {
                const error = new Error('raw body timeout fake-api-key');
                error.name = 'AbortError';
                reject(error);
              }, { once: true });
            })
          })
        }),
        watchdogFailure
      ]),
      (error) => error instanceof SmokeChatError
        && error.message === 'TIMEOUT'
        && !error.message.includes('raw body timeout')
        && !error.message.includes('fake-api-key')
    );
    assert.ok(Date.now() - startedAt < 200, 'body timeout did not settle promptly');
  } finally {
    clearTimeout(watchdog);
  }
});
