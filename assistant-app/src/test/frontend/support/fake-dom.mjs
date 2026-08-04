export class FakeEvent {
  constructor(type, options = {}) {
    this.type = type;
    Object.assign(this, options);
    this.defaultPrevented = false;
  }

  preventDefault() {
    this.defaultPrevented = true;
  }
}

export class FakeElement {
  constructor(document, tagName) {
    this.document = document;
    this.tagName = tagName.toUpperCase();
    this.children = [];
    this.attributes = new Map();
    this.listeners = new Map();
    this.value = '';
    this.hidden = false;
    this.disabled = false;
    this.focused = false;
    this._text = '';
  }

  append(...nodes) {
    for (const node of nodes) {
      this.children.push(node);
      node.parentNode = this;
    }
  }

  replaceChildren(...nodes) {
    this.children = [];
    this._text = '';
    this.append(...nodes);
  }

  set textContent(value) {
    this.children = [];
    this._text = String(value);
  }

  get textContent() {
    return this._text + this.children.map((child) => child.textContent).join('');
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
    if (name === 'id') {
      this.document.elementsById.set(String(value), this);
    }
  }

  getAttribute(name) {
    return this.attributes.get(name) ?? null;
  }

  hasAttribute(name) {
    return this.attributes.has(name);
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) ?? [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  dispatchEvent(event) {
    for (const listener of this.listeners.get(event.type) ?? []) {
      listener(event);
    }
    return !event.defaultPrevented;
  }

  focus() {
    this.focused = true;
    this.document.activeElement = this;
  }
}

export class FakeDocument {
  constructor() {
    this.elementsById = new Map();
    this.elements = [];
    this.activeElement = null;
  }

  createElement(tagName) {
    const element = new FakeElement(this, tagName);
    this.elements.push(element);
    return element;
  }

  getElementById(id) {
    return this.elementsById.get(id) ?? null;
  }

  querySelectorAll(selector) {
    if (selector === '[data-prompt]') {
      return this.elements.filter((element) => element.hasAttribute('data-prompt'));
    }
    return [];
  }
}

export function createChatPageDocument(prompts) {
  const document = new FakeDocument();
  const elements = {};
  for (const [tagName, id] of [
    ['div', 'transcript'], ['p', 'chat-status'], ['p', 'chat-error'],
    ['form', 'chat-form'], ['textarea', 'message'], ['button', 'send']
  ]) {
    const element = document.createElement(tagName);
    element.setAttribute('id', id);
    elements[id] = element;
  }
  elements.send.textContent = 'Send';
  elements.transcript.setAttribute('aria-busy', 'false');
  elements['chat-error'].hidden = true;
  elements.promptButtons = prompts.map((prompt) => {
    const button = document.createElement('button');
    button.setAttribute('data-prompt', prompt);
    button.textContent = prompt;
    return button;
  });
  return { document, elements };
}

export function createKnowledgePanelDocument() {
  const document = new FakeDocument();
  const elements = {};
  for (const [tagName, id] of [
    ['section', 'knowledge-panel'], ['p', 'knowledge-source'], ['p', 'knowledge-active'],
    ['p', 'knowledge-last-scan'], ['p', 'knowledge-status'], ['p', 'knowledge-error'],
    ['button', 'knowledge-check'], ['div', 'knowledge-result'], ['div', 'knowledge-diff'],
    ['textarea', 'knowledge-comment'], ['button', 'knowledge-discard'],
    ['button', 'knowledge-approve'], ['button', 'knowledge-ingest'],
    ['textarea', 'message'], ['button', 'send']
  ]) {
    const element = document.createElement(tagName);
    element.setAttribute('id', id);
    elements[id] = element;
  }
  elements['knowledge-panel'].setAttribute('aria-busy', 'false');
  elements['knowledge-check'].textContent = 'Check website';
  elements['knowledge-discard'].textContent = 'Discard change';
  elements['knowledge-approve'].textContent = 'Approve change';
  elements['knowledge-ingest'].textContent = 'Ingest approved version';
  elements['knowledge-status'].setAttribute('role', 'status');
  elements['knowledge-status'].hidden = true;
  elements['knowledge-error'].setAttribute('role', 'alert');
  elements['knowledge-error'].hidden = true;
  elements['knowledge-comment'].hidden = true;
  elements['knowledge-discard'].hidden = true;
  elements['knowledge-approve'].hidden = true;
  elements['knowledge-ingest'].hidden = true;
  return { document, elements };
}
