export function appendMessage(document, transcript, role, text) {
  const message = document.createElement('article');
  message.setAttribute('class', `message message--${role.toLowerCase()}`);
  const heading = document.createElement('p');
  heading.setAttribute('class', 'message-role');
  heading.textContent = role;
  const content = document.createElement('p');
  content.setAttribute('class', 'message-text');
  content.textContent = text;
  message.append(heading, content);
  transcript.append(message);
}

export function safeExternalUrl(value) {
  if (typeof value !== 'string') return null;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' ? url.href : null;
  } catch {
    return null;
  }
}

export function appendSources(document, transcript, sources) {
  const items = sources.filter((source) => typeof source?.label === 'string' && source.label.trim());
  if (items.length === 0) return;
  const list = document.createElement('ul');
  list.setAttribute('class', 'sources');
  list.setAttribute('aria-label', 'Sources');
  for (const source of items) {
    const chip = document.createElement('li');
    chip.setAttribute('class', 'source-chip');
    const url = safeExternalUrl(source.url);
    if (url) {
      const link = document.createElement('a');
      link.textContent = source.label;
      link.setAttribute('href', url);
      link.setAttribute('target', '_blank');
      link.setAttribute('rel', 'noopener noreferrer');
      chip.append(link);
    } else {
      chip.textContent = source.label;
    }
    list.append(chip);
  }
  transcript.append(list);
}

export function setBusy(ui, busy) {
  ui.transcript.setAttribute('aria-busy', String(busy));
  ui.textarea.disabled = busy;
  ui.send.disabled = busy;
  ui.send.textContent = busy ? 'Sending…' : 'Send';
  for (const button of ui.promptButtons) button.disabled = busy;
}

export function showStatus(status, message) {
  status.textContent = message;
}

export function showError(error, message) {
  error.textContent = message;
  error.hidden = !message;
}

export function clearFeedback(status, error) {
  showStatus(status, '');
  showError(error, '');
}
