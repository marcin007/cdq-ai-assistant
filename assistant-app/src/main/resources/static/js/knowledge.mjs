import {
  clearKnowledgeFeedback,
  renderKnowledge,
  renderKnowledgeUnavailable,
  setKnowledgeBusy,
  showKnowledgeError,
  showKnowledgeStatus
} from './knowledge-view.mjs';

const endpoint = '/api/knowledge/cdq';
const genericError = 'The knowledge panel could not complete that action. Refresh the panel.';
const unavailableError = 'Knowledge status is unavailable. Retry.';
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const sha256Pattern = /^[0-9a-f]{64}$/;
const instantPattern = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?Z$/;
const scanOutcomes = ['UNCHANGED', 'CHANGES_DETECTED', 'FAILED'];
const sourceFailureCodes = [
  'SOURCE_UNAVAILABLE',
  'SOURCE_TIMEOUT',
  'SOURCE_RESPONSE_INVALID',
  'SOURCE_CONTENT_INVALID'
];

const failureCopy = {
  SOURCE_UNAVAILABLE: 'The CDQ website could not be reached. Try again.',
  SOURCE_TIMEOUT: 'The CDQ website did not respond in time. Try again.',
  SOURCE_RESPONSE_INVALID: 'The CDQ website returned an unexpected response.',
  SOURCE_CONTENT_INVALID: 'The CDQ product content could not be safely identified.',
  VERSION_NOT_FOUND: 'This version is no longer available for that action. Refresh the panel.',
  VERSION_STATE_CONFLICT: 'This version is no longer available for that action. Refresh the panel.',
  INGEST_UNAVAILABLE: 'Ingest could not be completed. The active knowledge was not changed.'
};

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function hasString(value, key, allowNull = false) {
  return Object.hasOwn(value, key)
    && (typeof value[key] === 'string' || (allowNull && value[key] === null));
}

function isInstant(value) {
  if (typeof value !== 'string') return false;
  const match = instantPattern.exec(value);
  if (!match) return false;
  const [, year, month, day, hour, minute, second] = match;
  const instant = new Date(0);
  instant.setUTCFullYear(Number(year), Number(month) - 1, Number(day));
  instant.setUTCHours(Number(hour), Number(minute), Number(second), 0);
  return instant.getUTCFullYear() === Number(year)
    && instant.getUTCMonth() === Number(month) - 1
    && instant.getUTCDate() === Number(day)
    && instant.getUTCHours() === Number(hour)
    && instant.getUTCMinutes() === Number(minute)
    && instant.getUTCSeconds() === Number(second);
}

function isSourceUrl(value) {
  if (typeof value !== 'string') return false;
  try {
    const parsed = new URL(value);
    return ['http:', 'https:'].includes(parsed.protocol)
      && parsed.username === ''
      && parsed.password === '';
  } catch {
    return false;
  }
}

function isVersion(value) {
  return isObject(value)
    && hasString(value, 'id')
    && uuidPattern.test(value.id)
    && hasString(value, 'snapshotHash')
    && sha256Pattern.test(value.snapshotHash)
    && isInstant(value.capturedAt)
    && isInstant(value.activatedAt);
}

function isScan(value) {
  if (!isObject(value)
      || !isInstant(value.scannedAt)
      || !hasString(value, 'outcome')
      || !scanOutcomes.includes(value.outcome)
      || !hasString(value, 'failureCode', true)) return false;
  return value.outcome === 'FAILED'
    ? sourceFailureCodes.includes(value.failureCode)
    : value.failureCode === null;
}

function isDiff(value) {
  if (!(isObject(value)
    && Number.isInteger(value.addedLines)
    && value.addedLines >= 0
    && Number.isInteger(value.removedLines)
    && value.removedLines >= 0
    && Array.isArray(value.lines)
    && value.lines.every((line) => isObject(line)
      && hasString(line, 'type')
      && hasString(line, 'text')
      && ['ADDED', 'REMOVED', 'UNCHANGED'].includes(line.type)))) return false;
  return value.addedLines === value.lines.filter((line) => line.type === 'ADDED').length
    && value.removedLines === value.lines.filter((line) => line.type === 'REMOVED').length;
}

function isCandidate(value) {
  if (!(isObject(value)
    && hasString(value, 'id')
    && uuidPattern.test(value.id)
    && hasString(value, 'status')
    && ['PENDING_REVIEW', 'APPROVED'].includes(value.status)
    && hasString(value, 'snapshotHash')
    && sha256Pattern.test(value.snapshotHash)
    && isInstant(value.capturedAt)
    && hasString(value, 'reviewedAt', true)
    && hasString(value, 'reviewComment', true)
    && (value.reviewComment === null || value.reviewComment.length <= 500)
    && isDiff(value.diff))) return false;
  if (value.status === 'PENDING_REVIEW') {
    return value.reviewedAt === null && value.reviewComment === null;
  }
  return isInstant(value.reviewedAt);
}

function isActions(value, candidate) {
  if (!isObject(value)
      || typeof value.canReject !== 'boolean'
      || typeof value.canApprove !== 'boolean'
      || typeof value.canIngest !== 'boolean') return false;
  if (candidate === null) {
    return !value.canReject && !value.canApprove && !value.canIngest;
  }
  if (candidate.status === 'PENDING_REVIEW') {
    return value.canReject && value.canApprove && !value.canIngest;
  }
  return value.canReject && !value.canApprove && value.canIngest;
}

function isKnowledgeResponse(value) {
  return isObject(value)
    && isSourceUrl(value.sourceUrl)
    && (value.active === null || isVersion(value.active))
    && (value.lastScan === null || isScan(value.lastScan))
    && (value.candidate === null || isCandidate(value.candidate))
    && isActions(value.actions, value.candidate);
}

async function body(response) {
  const value = await response.json();
  if (!response.ok) {
    throw new Error(isObject(value) && typeof value.code === 'string' ? value.code : 'REQUEST_FAILED');
  }
  if (!isKnowledgeResponse(value)) throw new Error('MALFORMED_RESPONSE');
  return value;
}

function errorMessage(error) {
  return failureCopy[error.message] ?? genericError;
}

export function createKnowledgeController({ document, fetch, ui }) {
  let busyAction = null;
  let state = null;
  let stateAvailable = null;

  function render() {
    const busy = busyAction !== null;
    if (stateAvailable) {
      renderKnowledge(ui, state, busy);
    } else if (stateAvailable === false) {
      renderKnowledgeUnavailable(ui);
    } else {
      ui.discard.disabled = true;
      ui.approve.disabled = true;
      ui.ingest.disabled = true;
    }
    ui.check.textContent = busyAction === 'scan'
      ? 'Checking…'
      : busyAction === 'load'
        ? 'Loading…'
        : stateAvailable === false ? 'Retry' : 'Check website';
    ui.check.disabled = busy;
    setKnowledgeBusy(ui, busy);
  }

  async function fetchAuthoritativeState() {
    return body(await fetch(endpoint));
  }

  async function request(url, options, status, successStatus = '', refreshOnFailure = true, action = 'update') {
    if (busyAction !== null) return;
    clearKnowledgeFeedback(ui);
    busyAction = action;
    let succeeded = false;
    render();
    showKnowledgeStatus(ui, status);
    try {
      state = await body(await fetch(url, options));
      stateAvailable = true;
      succeeded = true;
    } catch (error) {
      const safeError = errorMessage(error);
      if (refreshOnFailure) {
        try {
          state = await fetchAuthoritativeState();
          stateAvailable = true;
          showKnowledgeError(ui, safeError);
        } catch {
          state = null;
          stateAvailable = false;
          showKnowledgeError(ui, unavailableError);
        }
      } else {
        state = null;
        stateAvailable = false;
        showKnowledgeError(ui, unavailableError);
      }
    } finally {
      busyAction = null;
      render();
      const completion = succeeded
        ? (typeof successStatus === 'function' ? successStatus(state) : successStatus)
        : '';
      showKnowledgeStatus(ui, completion);
    }
  }

  function load() {
    return request(endpoint, undefined, 'Loading knowledge status…', '', false, 'load');
  }

  function scan() {
    return request(
      `${endpoint}/scan`,
      { method: 'POST' },
      'Checking the CDQ website…',
      (current) => current.lastScan?.outcome === 'CHANGES_DETECTED'
        ? 'Scan completed. Changes detected.'
        : 'Scan completed. No changes detected.',
      true,
      'scan'
    );
  }

  function candidateAction(action, permission, options, successStatus = '') {
    if (!stateAvailable || !state?.candidate || !state.actions[permission]) return undefined;
    return request(
      `${endpoint}/versions/${state.candidate.id}/${action}`,
      options,
      'Updating knowledge review…',
      successStatus
    );
  }

  function approve() {
    return candidateAction('approve', 'canApprove', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ comment: ui.comment.value.trim() })
    }, 'Change approved.');
  }

  function discard() {
    return candidateAction('reject', 'canReject', { method: 'POST' }, 'Change discarded.');
  }

  function ingest() {
    return candidateAction(
      'ingest',
      'canIngest',
      { method: 'POST' },
      'Ingest completed\nThe approved version is now active.'
    );
  }

  function primaryAction() {
    return stateAvailable === true ? scan() : load();
  }

  return { load, primaryAction, scan, approve, discard, ingest };
}

export function initialiseKnowledgePanel(document = globalThis.document, fetch = globalThis.fetch) {
  const ui = {
    document,
    panel: document.getElementById('knowledge-panel'),
    source: document.getElementById('knowledge-source'),
    active: document.getElementById('knowledge-active'),
    lastScan: document.getElementById('knowledge-last-scan'),
    status: document.getElementById('knowledge-status'),
    error: document.getElementById('knowledge-error'),
    check: document.getElementById('knowledge-check'),
    result: document.getElementById('knowledge-result'),
    diff: document.getElementById('knowledge-diff'),
    comment: document.getElementById('knowledge-comment'),
    discard: document.getElementById('knowledge-discard'),
    approve: document.getElementById('knowledge-approve'),
    ingest: document.getElementById('knowledge-ingest')
  };
  const controller = createKnowledgeController({ document, fetch, ui });
  ui.check.addEventListener('click', () => { void controller.primaryAction(); });
  ui.discard.addEventListener('click', () => { void controller.discard(); });
  ui.approve.addEventListener('click', () => { void controller.approve(); });
  ui.ingest.addEventListener('click', () => { void controller.ingest(); });
  void controller.load();
  return controller;
}

if (typeof globalThis.document !== 'undefined') {
  initialiseKnowledgePanel();
}
