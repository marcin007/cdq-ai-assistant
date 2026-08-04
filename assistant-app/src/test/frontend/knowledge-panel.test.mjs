import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { initialiseKnowledgePanel } from '../../main/resources/static/js/knowledge.mjs';
import { FakeEvent, createKnowledgePanelDocument } from './support/fake-dom.mjs';

const candidateId = '00000000-0000-0000-0000-000000000002';
const activeId = '00000000-0000-0000-0000-000000000001';
const sourceUrl = 'https://www.cdq.com/products/cdq-fraud-guard';
const activeHash = 'a'.repeat(64);
const candidateHash = 'b'.repeat(64);

function flush() {
  return new Promise((resolve) => setImmediate(resolve));
}

function activeState(overrides = {}) {
  return {
    sourceUrl,
    active: {
      id: activeId,
      snapshotHash: activeHash,
      capturedAt: '2026-08-04T10:15:30Z',
      activatedAt: '2026-08-04T10:45:00Z'
    },
    lastScan: null,
    candidate: null,
    actions: { canReject: false, canApprove: false, canIngest: false },
    ...overrides
  };
}

function changedState(overrides = {}) {
  return activeState({
    lastScan: { scannedAt: '2026-08-04T11:00:00Z', outcome: 'CHANGES_DETECTED', failureCode: null },
    candidate: {
      id: candidateId,
      status: 'PENDING_REVIEW',
      snapshotHash: candidateHash,
      capturedAt: '2026-08-04T11:00:00Z',
      reviewedAt: null,
      reviewComment: null,
      diff: {
        addedLines: 1,
        removedLines: 1,
        lines: [
          { type: 'REMOVED', text: 'Old public fact' },
          { type: 'ADDED', text: 'New public fact' },
          { type: 'UNCHANGED', text: 'Stable public fact' }
        ]
      }
    },
    actions: { canReject: true, canApprove: true, canIngest: false },
    ...overrides
  });
}

function unchangedState() {
  return activeState({
    lastScan: { scannedAt: '2026-08-04T11:00:00Z', outcome: 'UNCHANGED', failureCode: null }
  });
}

function failedState() {
  return activeState({
    lastScan: {
      scannedAt: '2026-08-04T11:01:00Z',
      outcome: 'FAILED',
      failureCode: 'SOURCE_UNAVAILABLE'
    }
  });
}

function response(body, ok = true) {
  return { ok, json: async () => body };
}

function queuedFetch(...responses) {
  const calls = [];
  const fetch = async (url, options = {}) => {
    calls.push({ url, options });
    const next = responses.shift();
    if (next instanceof Error) throw next;
    return next;
  };
  return { fetch, calls };
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

function findByClass(element, className) {
  const matches = [];
  const visit = (node) => {
    const classes = (node.getAttribute('class') ?? '').split(/\s+/);
    if (classes.includes(className)) matches.push(node);
    node.children.forEach(visit);
  };
  visit(element);
  return matches;
}

test('panel precedes chat, loads active metadata, and a loading failure leaves chat usable', async () => {
  const html = await readFile(new URL('../../main/resources/static/index.html', import.meta.url), 'utf8');
  assert.ok(html.indexOf('id="knowledge-panel"') < html.indexOf('id="chat-title"'));
  for (const id of [
    'knowledge-panel', 'knowledge-source', 'knowledge-active', 'knowledge-last-scan',
    'knowledge-status', 'knowledge-error', 'knowledge-check', 'knowledge-result',
    'knowledge-diff', 'knowledge-comment', 'knowledge-discard', 'knowledge-approve', 'knowledge-ingest'
  ]) assert.match(html, new RegExp(`id="${id}"`));
  assert.match(html, /<script type="module" src="\/js\/knowledge\.mjs"><\/script>/);
  assert.match(html, /id="knowledge-status"[^>]*role="status"/);
  assert.match(html, /id="knowledge-error"[^>]*role="alert"/);
  assert.match(html, /<h1 id="page-title">Ask about CDQ Fraud Guard, countries, or the weather<\/h1>/);
  assert.match(html, /<main id="main-content"[^>]*aria-labelledby="page-title"/);
  assert.match(html, /<button id="knowledge-check" type="button">Check website<\/button>/);
  assert.match(html, /<button id="knowledge-discard" type="button" hidden>Discard change<\/button>/);
  assert.match(html, /<button id="knowledge-approve" type="button" hidden>Approve change<\/button>/);
  assert.match(html, /<button id="knowledge-ingest" type="button" hidden>Ingest approved version<\/button>/);

  const ready = createKnowledgePanelDocument();
  const readyFetch = queuedFetch(response(activeState()));
  initialiseKnowledgePanel(ready.document, readyFetch.fetch);
  await flush();
  assert.equal(readyFetch.calls[0].url, '/api/knowledge/cdq');
  assert.match(ready.elements['knowledge-source'].textContent, /cdq-fraud-guard/);
  assert.match(ready.elements['knowledge-active'].textContent, /aaaaaaaa…/);
  assert.match(ready.elements['knowledge-active'].textContent, /Captured: 2026-08-04T10:15:30Z/);
  const activeHashElement = findByTag(ready.elements['knowledge-active'], 'span')[0];
  assert.ok(activeHashElement);
  assert.equal(activeHashElement.getAttribute('title'), `SHA-256: ${activeHash}`);
  assert.equal(activeHashElement.getAttribute('aria-label'), `SHA-256: ${activeHash}`);
  assert.equal(ready.elements['knowledge-check'].disabled, false);

  const failure = createKnowledgePanelDocument();
  initialiseKnowledgePanel(failure.document, async () => { throw new Error('offline'); });
  await flush();
  assert.equal(failure.elements.message.disabled, false);
  assert.equal(failure.elements.send.disabled, false);
  assert.equal(failure.elements['knowledge-error'].hidden, false);
});

test('static UI exposes live status visually and hidden review controls stay out of layout', async () => {
  const html = await readFile(new URL('../../main/resources/static/index.html', import.meta.url), 'utf8');
  const css = await readFile(new URL('../../main/resources/static/css/chat.css', import.meta.url), 'utf8');

  const statusMarkup = html.match(/<p[^>]*id="knowledge-status"[^>]*>/)?.[0] ?? '';
  assert.match(statusMarkup, /role="status"/);
  assert.match(statusMarkup, /\shidden(?:\s|>)/);
  assert.doesNotMatch(statusMarkup, /class="[^"]*visually-hidden/);
  assert.match(css, /\[hidden\]\s*\{\s*display:\s*none\s*!important;\s*\}/);
});

test('unchanged scan explains why approval and ingest are unavailable', async () => {
  const { document, elements } = createKnowledgePanelDocument();
  const queue = queuedFetch(response(changedState()), response(unchangedState()));
  initialiseKnowledgePanel(document, queue.fetch);
  await flush();

  elements['knowledge-comment'].value = 'stale approval comment';
  elements['knowledge-check'].dispatchEvent(new FakeEvent('click'));
  assert.equal(elements['knowledge-check'].disabled, true);
  await flush();

  assert.equal(elements['knowledge-result'].children[0].textContent, 'No changes detected');
  assert.equal(
    elements['knowledge-result'].children[1].textContent,
    'The active knowledge matches the CDQ website. There is nothing to approve or ingest.'
  );
  assert.equal(elements['knowledge-comment'].hidden, true);
  assert.equal(elements['knowledge-comment'].value, '');
  assert.equal(elements['knowledge-discard'].hidden, true);
  assert.equal(elements['knowledge-approve'].hidden, true);
  assert.equal(elements['knowledge-ingest'].hidden, true);
  assert.equal(elements['knowledge-approve'].disabled, true);
  assert.equal(elements['knowledge-ingest'].disabled, true);
});

test('a pending request is not duplicated and does not block chat controls', async () => {
  const { document, elements } = createKnowledgePanelDocument();
  let resolveScan;
  const scan = new Promise((resolve) => { resolveScan = resolve; });
  const queue = queuedFetch(response(activeState()), scan);
  initialiseKnowledgePanel(document, queue.fetch);
  await flush();
  elements['knowledge-check'].dispatchEvent(new FakeEvent('click'));
  assert.equal(elements['knowledge-check'].textContent, 'Checking…');
  elements['knowledge-check'].dispatchEvent(new FakeEvent('click'));
  assert.equal(queue.calls.length, 2);
  assert.equal(elements.message.disabled, false);
  assert.equal(elements.send.disabled, false);
  resolveScan(response(unchangedState()));
  await flush();
  assert.equal(elements['knowledge-check'].textContent, 'Check website');
});

test('the initial status load marks the panel busy without disabling chat', async () => {
  const { document, elements } = createKnowledgePanelDocument();
  let resolveLoad;
  const load = new Promise((resolve) => { resolveLoad = resolve; });
  const queue = queuedFetch(load);
  initialiseKnowledgePanel(document, queue.fetch);
  assert.equal(elements['knowledge-panel'].getAttribute('aria-busy'), 'true');
  assert.equal(elements['knowledge-check'].disabled, true);
  assert.equal(elements.message.disabled, false);
  assert.equal(elements.send.disabled, false);
  resolveLoad(response(activeState()));
  await flush();
  assert.equal(elements['knowledge-panel'].getAttribute('aria-busy'), 'false');
});

test('closed server state prevents action requests even when synthetic clicks are dispatched', async () => {
  const { document, elements } = createKnowledgePanelDocument();
  const queue = queuedFetch(response(activeState()));
  initialiseKnowledgePanel(document, queue.fetch);
  await flush();
  elements['knowledge-discard'].dispatchEvent(new FakeEvent('click'));
  elements['knowledge-approve'].dispatchEvent(new FakeEvent('click'));
  elements['knowledge-ingest'].dispatchEvent(new FakeEvent('click'));
  await flush();
  assert.equal(queue.calls.length, 1);
  assert.equal(elements['knowledge-discard'].disabled, true);
  assert.equal(elements['knowledge-approve'].disabled, true);
  assert.equal(elements['knowledge-ingest'].disabled, true);
});

test('review actions use the server candidate id, trim comments, and render diff text only', async () => {
  const unsafe = changedState({
    candidate: {
      ...changedState().candidate,
      diff: {
        addedLines: 1, removedLines: 1,
        lines: [
          { type: 'ADDED', text: '<script>alert(1)</script>' },
          { type: 'REMOVED', text: '<img src=x onerror=alert(1)>' }
        ]
      }
    }
  });
  const approved = changedState({
    candidate: { ...unsafe.candidate, status: 'APPROVED', reviewedAt: '2026-08-04T11:05:00Z', reviewComment: 'approved after review' },
    actions: { canReject: true, canApprove: false, canIngest: true }
  });
  const active = activeState({ lastScan: approved.lastScan });
  const { document, elements } = createKnowledgePanelDocument();
  const queue = queuedFetch(response(unsafe), response(approved), response(active));
  initialiseKnowledgePanel(document, queue.fetch);
  await flush();
  assert.match(elements['knowledge-diff'].textContent, /<script>alert\(1\)<\/script>/);
  assert.match(elements['knowledge-diff'].textContent, /<img src=x onerror=alert\(1\)>/);
  assert.match(elements['knowledge-diff'].textContent, /Candidate: bbbbbbbb…/);
  assert.match(elements['knowledge-diff'].textContent, /Captured: 2026-08-04T11:00:00Z/);
  assert.match(elements['knowledge-diff'].textContent, /Added: 1/);
  assert.match(elements['knowledge-diff'].textContent, /Removed: 1/);
  const candidateHashElement = findByTag(elements['knowledge-diff'], 'span')[0];
  assert.ok(candidateHashElement);
  assert.equal(candidateHashElement.getAttribute('title'), `SHA-256: ${candidateHash}`);
  assert.equal(candidateHashElement.getAttribute('aria-label'), `SHA-256: ${candidateHash}`);
  const diffRegion = findByClass(elements['knowledge-diff'], 'knowledge-diff-lines')[0];
  assert.ok(diffRegion);
  assert.equal(diffRegion.getAttribute('role'), 'region');
  assert.equal(diffRegion.getAttribute('aria-label'), 'Knowledge changes');
  assert.equal(diffRegion.getAttribute('tabindex'), '0');
  assert.equal(findByTag(elements['knowledge-diff'], 'script').length, 0);
  assert.equal(findByTag(elements['knowledge-diff'], 'img').length, 0);
  elements['knowledge-comment'].value = '  approved after review  ';
  elements['knowledge-approve'].dispatchEvent(new FakeEvent('click'));
  await flush();
  assert.equal(queue.calls[1].url, `/api/knowledge/cdq/versions/${candidateId}/approve`);
  assert.equal(queue.calls[1].options.method, 'POST');
  assert.deepEqual(JSON.parse(queue.calls[1].options.body), { comment: 'approved after review' });
  assert.equal(elements['knowledge-approve'].disabled, true);
  assert.equal(elements['knowledge-ingest'].disabled, false);
  elements['knowledge-ingest'].dispatchEvent(new FakeEvent('click'));
  await flush();
  assert.equal(queue.calls[2].url, `/api/knowledge/cdq/versions/${candidateId}/ingest`);
  assert.equal(queue.calls[2].options.body, undefined);
  assert.equal(elements['knowledge-diff'].hidden, true);
});

test('a successful ingest shows the exact two-line completion and clears the approval comment', async () => {
  const approved = changedState({
    candidate: {
      ...changedState().candidate,
      status: 'APPROVED',
      reviewedAt: '2026-08-04T11:05:00Z',
      reviewComment: 'approved after review'
    },
    actions: { canReject: true, canApprove: false, canIngest: true }
  });
  const { document, elements } = createKnowledgePanelDocument();
  const queue = queuedFetch(response(approved), response(activeState({ lastScan: approved.lastScan })));
  initialiseKnowledgePanel(document, queue.fetch);
  await flush();

  elements['knowledge-comment'].value = 'approved after review';
  elements['knowledge-ingest'].dispatchEvent(new FakeEvent('click'));
  await flush();

  assert.equal(
    elements['knowledge-status'].textContent,
    'Ingest completed\nThe approved version is now active.'
  );
  assert.equal(elements['knowledge-status'].hidden, false);
  assert.equal(elements['knowledge-comment'].hidden, true);
  assert.equal(elements['knowledge-comment'].value, '');
});

test('discard clears the candidate and a reload restores the server-persisted candidate', async () => {
  const discarded = activeState({ lastScan: changedState().lastScan });
  const first = createKnowledgePanelDocument();
  const firstQueue = queuedFetch(response(changedState()), response(discarded));
  initialiseKnowledgePanel(first.document, firstQueue.fetch);
  await flush();
  first.elements['knowledge-discard'].dispatchEvent(new FakeEvent('click'));
  await flush();
  assert.equal(firstQueue.calls[1].url, `/api/knowledge/cdq/versions/${candidateId}/reject`);
  assert.equal(first.elements['knowledge-discard'].hidden, true);
  assert.equal(first.elements['knowledge-diff'].hidden, true);

  const refreshed = createKnowledgePanelDocument();
  const reloadQueue = queuedFetch(response(changedState()));
  initialiseKnowledgePanel(refreshed.document, reloadQueue.fetch);
  await flush();
  assert.match(refreshed.elements['knowledge-diff'].textContent, /New public fact/);
  assert.equal(refreshed.elements['knowledge-approve'].disabled, false);
});

test('successful scan, approval, and discard outcomes are announced', async () => {
  const scanPage = createKnowledgePanelDocument();
  const scanQueue = queuedFetch(response(activeState()), response(changedState()));
  initialiseKnowledgePanel(scanPage.document, scanQueue.fetch);
  await flush();
  scanPage.elements['knowledge-check'].dispatchEvent(new FakeEvent('click'));
  await flush();
  assert.equal(scanPage.elements['knowledge-status'].textContent, 'Scan completed. Changes detected.');

  const approved = changedState({
    candidate: {
      ...changedState().candidate,
      status: 'APPROVED',
      reviewedAt: '2026-08-04T11:05:00Z',
      reviewComment: 'reviewed'
    },
    actions: { canReject: true, canApprove: false, canIngest: true }
  });
  const approvePage = createKnowledgePanelDocument();
  const approveQueue = queuedFetch(response(changedState()), response(approved));
  initialiseKnowledgePanel(approvePage.document, approveQueue.fetch);
  await flush();
  approvePage.elements['knowledge-approve'].dispatchEvent(new FakeEvent('click'));
  await flush();
  assert.equal(approvePage.elements['knowledge-status'].textContent, 'Change approved.');

  const discardPage = createKnowledgePanelDocument();
  const discardQueue = queuedFetch(response(changedState()), response(activeState({
    lastScan: changedState().lastScan
  })));
  initialiseKnowledgePanel(discardPage.document, discardQueue.fetch);
  await flush();
  discardPage.elements['knowledge-discard'].dispatchEvent(new FakeEvent('click'));
  await flush();
  assert.equal(discardPage.elements['knowledge-status'].textContent, 'Change discarded.');
});

test('a failed scan refreshes and renders the authoritative FAILED state', async () => {
  const { document, elements } = createKnowledgePanelDocument();
  const queue = queuedFetch(
    response(unchangedState()),
    response({ code: 'SOURCE_UNAVAILABLE', detail: 'raw backend detail' }, false),
    response(failedState())
  );
  initialiseKnowledgePanel(document, queue.fetch);
  await flush();

  elements['knowledge-check'].dispatchEvent(new FakeEvent('click'));
  await flush();

  assert.deepEqual(queue.calls.map((call) => call.url), [
    '/api/knowledge/cdq',
    '/api/knowledge/cdq/scan',
    '/api/knowledge/cdq'
  ]);
  assert.match(elements['knowledge-last-scan'].textContent, /Outcome: FAILED/);
  assert.equal(elements['knowledge-result'].hidden, true);
  assert.equal(elements['knowledge-approve'].disabled, true);
  assert.equal(elements['knowledge-ingest'].disabled, true);
  assert.equal(elements['knowledge-error'].textContent, 'The CDQ website could not be reached. Try again.');
});

test('404 and 409 action failures refresh away obsolete candidate permissions', async () => {
  for (const code of ['VERSION_NOT_FOUND', 'VERSION_STATE_CONFLICT']) {
    const { document, elements } = createKnowledgePanelDocument();
    const queue = queuedFetch(
      response(changedState()),
      response({ code, detail: 'raw backend detail' }, false),
      response(activeState({ lastScan: changedState().lastScan }))
    );
    initialiseKnowledgePanel(document, queue.fetch);
    await flush();

    elements['knowledge-approve'].dispatchEvent(new FakeEvent('click'));
    await flush();

    assert.ok(queue.calls[2]);
    assert.equal(queue.calls[2].url, '/api/knowledge/cdq');
    assert.equal(elements['knowledge-diff'].hidden, true);
    assert.equal(elements['knowledge-approve'].hidden, true);
    assert.equal(elements['knowledge-approve'].disabled, true);
    assert.equal(elements['knowledge-ingest'].disabled, true);
  }
});

test('a failed authoritative refresh marks state unavailable and exposes only safe retry', async () => {
  const { document, elements } = createKnowledgePanelDocument();
  const queue = queuedFetch(
    response(changedState()),
    new Error('scan transport detail'),
    new Error('refresh transport detail'),
    response(activeState())
  );
  initialiseKnowledgePanel(document, queue.fetch);
  await flush();

  elements['knowledge-check'].dispatchEvent(new FakeEvent('click'));
  await flush();

  assert.match(elements['knowledge-active'].textContent, /Status unavailable/);
  assert.equal(elements['knowledge-check'].textContent, 'Retry');
  assert.equal(elements['knowledge-check'].disabled, false);
  assert.equal(elements['knowledge-discard'].hidden, true);
  assert.equal(elements['knowledge-approve'].hidden, true);
  assert.equal(elements['knowledge-ingest'].hidden, true);
  assert.equal(elements['knowledge-error'].textContent, 'Knowledge status is unavailable. Retry.');
  assert.equal(elements['knowledge-status'].textContent, '');
  assert.equal(elements['knowledge-status'].hidden, true);

  elements['knowledge-check'].dispatchEvent(new FakeEvent('click'));
  await flush();
  assert.equal(queue.calls[3].url, '/api/knowledge/cdq');
  assert.equal(elements['knowledge-check'].textContent, 'Check website');
});

test('safe problem codes replace every error body and malformed responses are rejected', async () => {
  const messages = {
    SOURCE_UNAVAILABLE: 'The CDQ website could not be reached. Try again.',
    SOURCE_TIMEOUT: 'The CDQ website did not respond in time. Try again.',
    SOURCE_RESPONSE_INVALID: 'The CDQ website returned an unexpected response.',
    SOURCE_CONTENT_INVALID: 'The CDQ product content could not be safely identified.',
    VERSION_NOT_FOUND: 'This version is no longer available for that action. Refresh the panel.',
    VERSION_STATE_CONFLICT: 'This version is no longer available for that action. Refresh the panel.',
    INGEST_UNAVAILABLE: 'Ingest could not be completed. The active knowledge was not changed.'
  };
  for (const [code, expected] of Object.entries(messages)) {
    const page = createKnowledgePanelDocument();
    const refreshed = code.startsWith('SOURCE_')
      ? failedState({ failureCode: code })
      : activeState();
    const queue = queuedFetch(
      response(activeState()),
      response({ code, detail: '<script>untrusted</script>' }, false),
      response(refreshed)
    );
    initialiseKnowledgePanel(page.document, queue.fetch);
    await flush();
    page.elements['knowledge-check'].dispatchEvent(new FakeEvent('click'));
    await flush();
    assert.equal(page.elements['knowledge-error'].textContent, expected);
    assert.equal(findByTag(page.elements['knowledge-error'], 'script').length, 0);
  }

  const malformed = createKnowledgePanelDocument();
  const queue = queuedFetch(response({ ...activeState(), actions: { canReject: 'false', canApprove: false, canIngest: false } }));
  initialiseKnowledgePanel(malformed.document, queue.fetch);
  await flush();
  assert.equal(malformed.elements['knowledge-error'].hidden, false);
  assert.equal(malformed.elements['knowledge-check'].disabled, false);
});

test('a malformed candidate UUID is rejected before it can target an action endpoint', async () => {
  const { document, elements } = createKnowledgePanelDocument();
  const malformed = changedState({
    candidate: { ...changedState().candidate, id: 'candidate-id-from-an-untrusted-response' }
  });
  const queue = queuedFetch(response(malformed));
  initialiseKnowledgePanel(document, queue.fetch);
  await flush();
  assert.equal(elements['knowledge-error'].hidden, false);
  assert.equal(elements['knowledge-panel'].getAttribute('aria-busy'), 'false');
  assert.equal(elements['knowledge-approve'].disabled, true);
  elements['knowledge-approve'].dispatchEvent(new FakeEvent('click'));
  await flush();
  assert.equal(queue.calls.length, 1);
});

test('closed values, lowercase hashes, timestamps, counts, and actions are validated together', async () => {
  const malformedStates = [
    activeState({ active: { ...activeState().active, snapshotHash: activeHash.toUpperCase() } }),
    activeState({ active: { ...activeState().active, capturedAt: '2026-08-04 10:15:30' } }),
    activeState({ active: { ...activeState().active, capturedAt: '2026-02-31T10:15:30Z' } }),
    activeState({ lastScan: { scannedAt: '2026-08-04T11:00:00Z', outcome: 'UNKNOWN', failureCode: null } }),
    activeState({ lastScan: { scannedAt: '2026-08-04T11:00:00Z', outcome: 'UNCHANGED', failureCode: 'SOURCE_TIMEOUT' } }),
    changedState({ candidate: { ...changedState().candidate, status: 'REJECTED' } }),
    changedState({ candidate: {
      ...changedState().candidate,
      diff: { ...changedState().candidate.diff, addedLines: -1 }
    } }),
    changedState({ candidate: {
      ...changedState().candidate,
      diff: { ...changedState().candidate.diff, addedLines: 2 }
    } }),
    changedState({ actions: { canReject: false, canApprove: false, canIngest: false } })
  ];

  for (const malformedState of malformedStates) {
    const { document, elements } = createKnowledgePanelDocument();
    initialiseKnowledgePanel(document, queuedFetch(response(malformedState)).fetch);
    await flush();
    assert.equal(elements['knowledge-error'].hidden, false);
    assert.equal(elements['knowledge-approve'].disabled, true);
    assert.equal(elements['knowledge-ingest'].disabled, true);
  }
});
