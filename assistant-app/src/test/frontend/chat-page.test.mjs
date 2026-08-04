import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { initialiseChatPage } from '../../main/resources/static/js/chat.mjs';
import { FakeEvent, createChatPageDocument } from './support/fake-dom.mjs';

const prompts = [
  'What is the capital city of Germany?',
  'What is the temperature currently in Munich?',
  'What is the temperature of the capital of Germany currently?',
  'What do you know about Berlin?',
  'Which CDQ Fraud Guard features help prevent payment fraud?',
  'What is Japan’s capital and what is the current temperature there?'
];

const friendlyError = "We couldn't get an answer right now. Please try again.";
const timeoutError = 'The assistant took too long. Please try again.';
const groundingError = 'The answer could not be verified. Please try again.';
const dependencyError = 'A required service is unavailable. Please try again.';

function successfulFetch(answer = 'Berlin is the capital of Germany.', sources = []) {
  return async (url, options) => {
    assert.equal(url, '/api/chat');
    assert.equal(options.method, 'POST');
    assert.deepEqual(JSON.parse(options.body), { message: 'Question' });
    return { ok: true, json: async () => ({ answer, sources }) };
  };
}

function findByTag(element, tagName) {
  const matches = [];
  const visit = (node) => {
    if (node.tagName === tagName.toUpperCase()) matches.push(node);
    node.children.forEach(visit);
  };
  visit(element);
  return matches;
}

test('six approved prompts fill and focus the question without submitting', async () => {
  const html = await readFile(new URL('../../main/resources/static/index.html', import.meta.url), 'utf8');
  assert.equal((html.match(/data-prompt=/g) ?? []).length, 6);
  for (const prompt of prompts) assert.match(html, new RegExp(prompt.replace(/[?]/g, '\\?')));
  for (const id of ['main-content', 'chat-title', 'transcript', 'chat-status', 'chat-error', 'chat-form', 'message', 'send']) {
    assert.match(html, new RegExp(`id="${id}"`));
  }
  assert.match(html, /<script type="module" src="\/js\/chat\.mjs"><\/script>/);
  const form = html.match(/<form\b[^>]*>/)?.[0] ?? '';
  assert.match(form, /\bmethod="post"/);
  assert.match(form, /\baction="\/api\/chat"/);
  assert.doesNotMatch(form, /\bname=/);

  const { document, elements } = createChatPageDocument(prompts);
  let requests = 0;
  initialiseChatPage(document, async () => { requests += 1; throw new Error('must not fetch'); });
  for (let index = 0; index < prompts.length; index += 1) {
    elements.promptButtons[index].dispatchEvent(new FakeEvent('click'));
    assert.equal(elements.message.value, prompts[index]);
    assert.equal(document.activeElement, elements.message);
  }
  assert.equal(requests, 0);
});

test('Enter during IME composition keeps native behavior and does not submit', () => {
  const { document, elements } = createChatPageDocument(prompts);
  let requests = 0;
  initialiseChatPage(document, async () => {
    requests += 1;
    return { ok: true, json: async () => ({ answer: 'Unexpected', sources: [] }) };
  });
  elements.message.value = 'Question';

  for (const event of [
    new FakeEvent('keydown', { key: 'Enter', shiftKey: false, isComposing: true }),
    new FakeEvent('keydown', { key: 'Enter', shiftKey: false, isComposing: false, keyCode: 229 })
  ]) {
    elements.message.dispatchEvent(event);
    assert.equal(event.defaultPrevented, false);
  }
  assert.equal(requests, 0);
});

test('Enter submits once while Shift+Enter keeps native newline behavior and busy blocks duplicates', async () => {
  const { document, elements } = createChatPageDocument(prompts);
  let resolveRequest;
  let requests = 0;
  const request = new Promise((resolve) => { resolveRequest = resolve; });
  initialiseChatPage(document, async (url, options) => {
    requests += 1;
    assert.equal(url, '/api/chat');
    assert.deepEqual(JSON.parse(options.body), { message: 'Question' });
    return request;
  });
  elements.message.value = '  Question  ';
  const shiftEnter = new FakeEvent('keydown', { key: 'Enter', shiftKey: true });
  elements.message.dispatchEvent(shiftEnter);
  assert.equal(shiftEnter.defaultPrevented, false);
  assert.equal(requests, 0);

  const enter = new FakeEvent('keydown', { key: 'Enter', shiftKey: false });
  elements.message.dispatchEvent(enter);
  assert.equal(enter.defaultPrevented, true);
  assert.equal(requests, 1);
  assert.equal(elements.message.disabled, true);
  assert.equal(elements.send.disabled, true);
  assert.equal(elements.promptButtons.every((button) => button.disabled), true);
  assert.equal(elements.transcript.getAttribute('aria-busy'), 'true');
  assert.equal(elements['chat-status'].textContent, 'Thinking…');

  const secondSubmit = new FakeEvent('submit');
  elements['chat-form'].dispatchEvent(secondSubmit);
  assert.equal(secondSubmit.defaultPrevented, true);
  assert.equal(requests, 1);

  resolveRequest({ ok: true, json: async () => ({ answer: 'Done', sources: [] }) });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(elements.message.disabled, false);
  assert.equal(elements.send.textContent, 'Send');
});

test('a successful response renders untrusted API answer and source label as text with a safe link', async () => {
  const { document, elements } = createChatPageDocument(prompts);
  initialiseChatPage(document, successfulFetch('<img src=x onerror=alert(1)>', [{
    kind: 'REST_COUNTRIES', label: '<script>alert(1)</script>', url: 'https://example.test/source'
  }]));
  elements.message.value = 'Question';
  const submit = new FakeEvent('submit');
  elements['chat-form'].dispatchEvent(submit);
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(submit.defaultPrevented, true);
  assert.match(elements.transcript.textContent, /<img src=x onerror=alert\(1\)>/);
  assert.match(elements.transcript.textContent, /<script>alert\(1\)<\/script>/);
  assert.equal(findByTag(elements.transcript, 'img').length, 0);
  assert.equal(findByTag(elements.transcript, 'script').length, 0);
  const links = findByTag(elements.transcript, 'a');
  assert.equal(links.length, 1);
  assert.equal(links[0].textContent, '<script>alert(1)</script>');
  assert.equal(links[0].getAttribute('href'), 'https://example.test/source');
  assert.equal(links[0].getAttribute('target'), '_blank');
  assert.equal(links[0].getAttribute('rel'), 'noopener noreferrer');
  assert.equal(elements.message.value, '');
});

test('known RFC 9457 failures use fixed safe copy without rendering backend detail', async () => {
  const cases = [
    [504, 'Request timed out', timeoutError],
    [503, 'Answer not verified', groundingError],
    [503, 'Dependency unavailable', dependencyError]
  ];
  for (const [status, title, expected] of cases) {
    const failure = createChatPageDocument(prompts);
    initialiseChatPage(failure.document, async () => ({
      ok: false,
      status,
      json: async () => ({
        status,
        title,
        detail: 'raw-provider-body fake-api-key'
      })
    }));
    failure.elements.message.value = 'Question';
    failure.elements['chat-form'].dispatchEvent(new FakeEvent('submit'));
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(failure.elements['chat-error'].textContent, expected);
    assert.doesNotMatch(failure.elements['chat-error'].textContent, /raw-provider|fake-api-key/);
    assert.equal(failure.elements.message.value, 'Question');
  }
});

test('unsafe sources become non-links and failed, malformed, or non-OK requests leave text for retry', async () => {
  const unsafe = createChatPageDocument(prompts);
  initialiseChatPage(unsafe.document, successfulFetch('Safe answer', [{ kind: 'OTHER', label: 'Unsafe', url: 'javascript:alert(1)' }]));
  unsafe.elements.message.value = 'Question';
  unsafe.elements['chat-form'].dispatchEvent(new FakeEvent('submit'));
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(findByTag(unsafe.elements.transcript, 'a').length, 0);
  assert.match(unsafe.elements.transcript.textContent, /Unsafe/);

  for (const response of [
    async () => { throw new Error('offline'); },
    async () => ({ ok: false, json: async () => ({}) }),
    async () => ({
      ok: false,
      status: 503,
      json: async () => ({ title: 'Unknown failure', detail: 'backend detail' })
    }),
    async () => ({
      ok: false,
      status: 504,
      json: async () => ({ title: 'Answer not verified', detail: 'backend detail' })
    }),
    async () => ({
      ok: false,
      status: 504,
      json: async () => ({ title: ['Request timed out'], detail: 'backend detail' })
    }),
    async () => ({ ok: false, status: 503, json: async () => { throw new Error('invalid JSON'); } }),
    async () => ({ ok: true, json: async () => ({ answer: 7, sources: [] }) })
  ]) {
    const failure = createChatPageDocument(prompts);
    initialiseChatPage(failure.document, response);
    failure.elements.message.value = 'Question';
    failure.elements['chat-form'].dispatchEvent(new FakeEvent('submit'));
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(failure.elements['chat-error'].textContent, friendlyError);
    assert.equal(failure.elements['chat-error'].hidden, false);
    assert.equal(failure.elements.message.value, 'Question');
    assert.equal(failure.elements.transcript.getAttribute('aria-busy'), 'false');
    assert.equal(failure.elements.message.disabled, false);
  }
});

test('blank answers and incomplete source records are malformed responses, not successful rendering', async () => {
  const malformedBodies = [
    { answer: '', sources: [] },
    { answer: '   ', sources: [] },
    { answer: 'Answer', sources: [null] },
    { answer: 'Answer', sources: [{ label: '', url: 'https://example.test' }] },
    { answer: 'Answer', sources: [{ label: ' ', url: 'https://example.test' }] },
    { answer: 'Answer', sources: [{ label: 'Source' }] },
    { answer: 'Answer', sources: [{ label: 'Source', url: null }] },
    { answer: 'Answer', sources: [{ label: 'Source', url: 42 }] }
  ];
  for (const body of malformedBodies) {
    const failure = createChatPageDocument(prompts);
    initialiseChatPage(failure.document, async () => ({ ok: true, json: async () => body }));
    failure.elements.message.value = 'Question';
    failure.elements['chat-form'].dispatchEvent(new FakeEvent('submit'));
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(failure.elements['chat-error'].textContent, friendlyError);
    assert.equal(failure.elements.message.value, 'Question');
    assert.equal(failure.elements.transcript.textContent, 'YouQuestion');
    assert.equal(findByTag(failure.elements.transcript, 'a').length, 0);
    assert.equal(failure.elements.transcript.getAttribute('aria-busy'), 'false');
  }
});

test('a fresh document has no transcript and the browser modules avoid persistence and unsafe HTML APIs', async () => {
  const originalLocalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  const originalSessionStorage = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, get: () => { throw new Error('storage read'); } });
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, get: () => { throw new Error('storage read'); } });
  try {
    const first = createChatPageDocument(prompts);
    Object.defineProperty(first.document, 'cookie', { get: () => { throw new Error('cookie read'); } });
    initialiseChatPage(first.document, successfulFetch());
    first.elements.promptButtons[0].dispatchEvent(new FakeEvent('click'));
    assert.equal(first.elements.message.value, prompts[0]);
    first.elements.message.value = 'Question';
    first.elements['chat-form'].dispatchEvent(new FakeEvent('submit'));
    await new Promise((resolve) => setImmediate(resolve));
    assert.ok(first.elements.transcript.children.length > 0);

    const refreshed = createChatPageDocument(prompts);
    Object.defineProperty(refreshed.document, 'cookie', { get: () => { throw new Error('cookie read'); } });
    initialiseChatPage(refreshed.document, successfulFetch());
    assert.equal(refreshed.elements.transcript.children.length, 0);
  } finally {
    if (originalLocalStorage) Object.defineProperty(globalThis, 'localStorage', originalLocalStorage);
    else delete globalThis.localStorage;
    if (originalSessionStorage) Object.defineProperty(globalThis, 'sessionStorage', originalSessionStorage);
    else delete globalThis.sessionStorage;
  }

  const modules = await Promise.all([
    readFile(new URL('../../main/resources/static/js/chat.mjs', import.meta.url), 'utf8'),
    readFile(new URL('../../main/resources/static/js/chat-view.mjs', import.meta.url), 'utf8'),
    readFile(new URL('../../main/resources/static/js/knowledge.mjs', import.meta.url), 'utf8'),
    readFile(new URL('../../main/resources/static/js/knowledge-view.mjs', import.meta.url), 'utf8')
  ]);
  for (const forbidden of ['localStorage', 'sessionStorage', 'document.cookie', 'history', 'location', 'innerHTML', 'insertAdjacentHTML']) {
    assert.equal(modules.some((module) => module.includes(forbidden)), false, `${forbidden} must not be used`);
  }
});
