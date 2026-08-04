import { appendMessage, appendSources, clearFeedback, setBusy, showError, showStatus } from './chat-view.mjs';

const blankMessage = 'Enter a question before sending.';
const requestError = "We couldn't get an answer right now. Please try again.";
const problemMessages = new Map([
  ['504:Request timed out', 'The assistant took too long. Please try again.'],
  ['503:Answer not verified', 'The answer could not be verified. Please try again.'],
  ['503:Dependency unavailable', 'A required service is unavailable. Please try again.']
]);

async function problemMessage(response) {
  try {
    const problem = await response.json();
    if (!problem || typeof problem !== 'object' || Array.isArray(problem) || typeof problem.title !== 'string') return requestError;
    return problemMessages.get(`${response.status}:${problem.title}`) ?? requestError;
  } catch {
    return requestError;
  }
}

function isChatResponse(value) {
  return value
    && typeof value.answer === 'string'
    && value.answer.trim().length > 0
    && Array.isArray(value.sources)
    && value.sources.every((source) => source
      && typeof source === 'object'
      && !Array.isArray(source)
      && typeof source.label === 'string'
      && source.label.trim().length > 0
      && typeof source.url === 'string');
}

export function createChatController({ document, fetch, ui }) {
  let busy = false;

  async function submit() {
    if (busy) return;
    const message = ui.textarea.value.trim();
    if (!message) {
      showError(ui.error, blankMessage);
      ui.textarea.focus();
      return;
    }
    clearFeedback(ui.status, ui.error);
    appendMessage(document, ui.transcript, 'You', message);
    busy = true;
    setBusy(ui, true);
    showStatus(ui.status, 'Thinking…');
    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message })
      });
      if (!response.ok) {
        showError(ui.error, await problemMessage(response));
        return;
      }
      const data = await response.json();
      if (!isChatResponse(data)) throw new Error('Malformed response');
      appendMessage(document, ui.transcript, 'Assistant', data.answer);
      appendSources(document, ui.transcript, data.sources);
      ui.textarea.value = '';
    } catch {
      showError(ui.error, requestError);
    } finally {
      busy = false;
      setBusy(ui, false);
      showStatus(ui.status, '');
      ui.textarea.focus();
    }
  }

  return { submit };
}

export function initialiseChatPage(document = globalThis.document, fetch = globalThis.fetch) {
  const ui = {
    transcript: document.getElementById('transcript'),
    status: document.getElementById('chat-status'),
    error: document.getElementById('chat-error'),
    form: document.getElementById('chat-form'),
    textarea: document.getElementById('message'),
    send: document.getElementById('send'),
    promptButtons: [...document.querySelectorAll('[data-prompt]')]
  };
  const controller = createChatController({ document, fetch, ui });
  ui.form.addEventListener('submit', (event) => {
    event.preventDefault();
    void controller.submit();
  });
  ui.textarea.addEventListener('keydown', (event) => {
    const isComposing = event.isComposing || event.keyCode === 229;
    if (event.key === 'Enter' && !event.shiftKey && !isComposing) {
      event.preventDefault();
      void controller.submit();
    }
  });
  for (const button of ui.promptButtons) {
    button.addEventListener('click', () => {
      ui.textarea.value = button.getAttribute('data-prompt');
      ui.textarea.focus();
    });
  }
  return controller;
}

if (typeof globalThis.document !== 'undefined') {
  initialiseChatPage();
}
