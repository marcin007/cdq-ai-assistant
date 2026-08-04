const noChangesTitle = 'No changes detected';
const noChangesDetail = 'The active knowledge matches the CDQ website. There is nothing to approve or ingest.';

function replaceText(element, text) {
  element.textContent = text;
}

function metadata(label, value) {
  return value ? `${label}: ${value}` : `${label}: Not available`;
}

function hashMetadata(ui, hash) {
  const abbreviation = ui.document.createElement('span');
  abbreviation.textContent = `${hash.slice(0, 8)}…`;
  abbreviation.setAttribute('title', `SHA-256: ${hash}`);
  abbreviation.setAttribute('aria-label', `SHA-256: ${hash}`);
  return abbreviation;
}

function diffPrefix(type) {
  if (type === 'ADDED') return '+';
  if (type === 'REMOVED') return '−';
  return ' ';
}

export function clearKnowledgeFeedback(ui) {
  showKnowledgeStatus(ui, '');
  ui.error.textContent = '';
  ui.error.hidden = true;
}

export function showKnowledgeStatus(ui, message) {
  ui.status.textContent = message;
  ui.status.hidden = message === '';
}

export function showKnowledgeError(ui, message) {
  ui.error.textContent = message;
  ui.error.hidden = false;
}

export function setKnowledgeBusy(ui, busy) {
  ui.panel.setAttribute('aria-busy', String(busy));
}

export function renderKnowledgeUnavailable(ui) {
  replaceText(ui.source, 'Source: Status unavailable');
  replaceText(ui.active, 'Active knowledge: Status unavailable');
  replaceText(ui.lastScan, 'Last scan: Status unavailable');
  ui.result.hidden = true;
  ui.result.replaceChildren();
  ui.diff.hidden = true;
  ui.diff.replaceChildren();
  ui.comment.value = '';
  ui.comment.hidden = true;
  ui.discard.hidden = true;
  ui.approve.hidden = true;
  ui.ingest.hidden = true;
  ui.discard.disabled = true;
  ui.approve.disabled = true;
  ui.ingest.disabled = true;
}

export function renderKnowledge(ui, state, busy) {
  const { active, lastScan, candidate, actions } = state;
  replaceText(ui.source, `Source: ${state.sourceUrl}`);
  if (active) {
    replaceText(ui.active, 'Active snapshot: ');
    ui.active.append(hashMetadata(ui, active.snapshotHash));
    const activeDetails = ui.document.createElement('span');
    activeDetails.textContent = ` · ${metadata('Captured', active.capturedAt)} · ${metadata('Activated', active.activatedAt)}`;
    ui.active.append(activeDetails);
  } else {
    replaceText(ui.active, 'Active knowledge: Not available');
  }
  replaceText(ui.lastScan, lastScan
    ? `${metadata('Last scan', lastScan.scannedAt)} · ${metadata('Outcome', lastScan.outcome)}`
    : 'Last scan: Not available');

  const unchanged = lastScan?.outcome === 'UNCHANGED';
  ui.result.hidden = !unchanged;
  if (unchanged) {
    ui.result.replaceChildren();
    const title = ui.document.createElement('p');
    title.setAttribute('class', 'knowledge-result-title');
    title.textContent = noChangesTitle;
    const detail = ui.document.createElement('p');
    detail.textContent = noChangesDetail;
    ui.result.append(title, detail);
  } else {
    ui.result.replaceChildren();
  }

  ui.diff.hidden = !candidate;
  ui.diff.replaceChildren();
  if (candidate) {
    const summary = ui.document.createElement('p');
    summary.setAttribute('class', 'knowledge-candidate-summary');
    summary.textContent = 'Candidate: ';
    summary.append(hashMetadata(ui, candidate.snapshotHash));
    const candidateDetails = ui.document.createElement('span');
    candidateDetails.textContent = ` · ${candidate.status} · ${metadata('Captured', candidate.capturedAt)}`
      + ` · Added: ${candidate.diff.addedLines} · Removed: ${candidate.diff.removedLines}`;
    summary.append(candidateDetails);
    const lines = ui.document.createElement('div');
    lines.setAttribute('class', 'knowledge-diff-lines');
    lines.setAttribute('role', 'region');
    lines.setAttribute('aria-label', 'Knowledge changes');
    lines.setAttribute('tabindex', '0');
    for (const line of candidate.diff.lines) {
      const row = ui.document.createElement('p');
      row.setAttribute('class', `diff-line diff-line--${line.type.toLowerCase()}`);
      row.textContent = `${diffPrefix(line.type)} ${line.text}`;
      lines.append(row);
    }
    ui.diff.append(summary, lines);
  }

  if (!candidate) ui.comment.value = '';
  ui.comment.hidden = !candidate || !actions.canApprove;
  ui.discard.hidden = !candidate;
  ui.approve.hidden = !candidate;
  ui.ingest.hidden = !candidate;
  ui.check.disabled = busy;
  ui.discard.disabled = busy || !candidate || !actions.canReject;
  ui.approve.disabled = busy || !candidate || !actions.canApprove;
  ui.ingest.disabled = busy || !candidate || !actions.canIngest;
}
