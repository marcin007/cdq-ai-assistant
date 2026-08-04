export class FakeNode {
  constructor(tagName) {
    this.tagName = tagName.toUpperCase();
    this.className = "";
    this.dataset = {};
    this.children = [];
    this.attributes = new Map();
    this.listeners = new Map();
    this.open = false;
    this.type = "";
    this.ownText = "";
    this.classList = {
      toggle: (name, enabled) => {
        const names = new Set(this.className.split(/\s+/).filter(Boolean));
        if (enabled) {
          names.add(name);
        } else {
          names.delete(name);
        }
        this.className = [...names].join(" ");
      },
    };
  }

  set textContent(value) {
    this.ownText = String(value);
    this.children = [];
  }

  get textContent() {
    return [this.ownText, ...this.children.map((child) => child.textContent)]
      .filter(Boolean)
      .join(" ");
  }

  append(...children) {
    this.children.push(...children);
  }

  replaceChildren(...children) {
    this.children = [...children];
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  getAttribute(name) {
    return this.attributes.get(name) ?? null;
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener);
  }

  click() {
    this.listeners.get("click")?.({ currentTarget: this, target: this });
  }
}

export class FakeDocument {
  constructor() {
    this.containers = new Map(
      [
        "[data-source-list]",
        "[data-architecture]",
        "[data-source-detail]",
        "[data-scenario-list]",
        "[data-scenario-detail]",
        "[data-decision-list]",
      ].map((selector) => [selector, new FakeNode("div")]),
    );
  }

  createElement(tagName) {
    return new FakeNode(tagName);
  }

  querySelector(selector) {
    return this.containers.get(selector) ?? null;
  }
}
