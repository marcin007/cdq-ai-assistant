import { DECISIONS, SCENARIOS, SOURCES } from "./scenarios.mjs";

function element(document, tag, className, text) {
  const node = document.createElement(tag);
  if (className) {
    node.className = className;
  }
  if (text !== undefined) {
    node.textContent = text;
  }
  return node;
}

function replaceChildren(parent, children) {
  parent.replaceChildren(...children);
}

function setPressed(buttons, selectedId) {
  for (const button of buttons) {
    const selected = button.dataset.id === selectedId;
    button.setAttribute("aria-pressed", String(selected));
    button.classList.toggle("is-selected", selected);
  }
}

function sourceCard(document, source, selectSource) {
  const button = element(document, "button", "source-card");
  button.type = "button";
  button.dataset.id = source.id;
  button.setAttribute("aria-pressed", "false");
  button.addEventListener("click", () => selectSource(source.id));

  const kind = element(document, "span", "source-kind", source.kind);
  const title = element(document, "span", "source-title", source.label);
  const transport = element(document, "span", "source-transport", source.transport);
  const action = element(document, "span", "source-action", "Inspect this source");
  button.append(kind, title, transport, action);
  return button;
}

function flowStep(document, number, label, value, className = "") {
  const item = element(document, "li", `flow-step ${className}`.trim());
  const index = element(document, "span", "flow-index", String(number).padStart(2, "0"));
  const copy = element(document, "span", "flow-copy");
  copy.append(
    element(document, "span", "flow-label", label),
    element(document, "strong", "", value),
  );
  item.append(index, copy);
  return item;
}

function renderSource(document, source, sourceButtons) {
  setPressed(sourceButtons, source.id);

  const flow = document.querySelector("[data-architecture]");
  replaceChildren(flow, [
    flowStep(document, 1, "Question", "Browser chat"),
    flowStep(document, 2, "Orchestration", "Spring AI + qwen3:4b"),
    flowStep(document, 3, "Selected tool", source.tool, "flow-step-accent"),
    flowStep(document, 4, "Transport", source.transport),
    flowStep(document, 5, "Ground truth", source.upstream),
    flowStep(document, 6, "Response", "Answer + executed source record"),
  ]);

  const detail = document.querySelector("[data-source-detail]");
  replaceChildren(detail, [
    element(document, "p", "label", source.kind),
    element(document, "h3", "", source.label),
    element(
      document,
      "p",
      "",
      `${source.tool} reaches ${source.upstream} through ${source.transport}. The source is recorded only after the tool callback succeeds.`,
    ),
  ]);
}

function scenarioButton(document, scenario, index, selectScenario) {
  const button = element(document, "button", "scenario-button");
  button.type = "button";
  button.dataset.id = scenario.id;
  button.setAttribute("aria-pressed", "false");
  button.addEventListener("click", () => selectScenario(scenario.id));
  button.append(
    element(document, "span", "scenario-number", String(index + 1).padStart(2, "0")),
    element(document, "span", "", scenario.question),
  );
  return button;
}

function renderScenario(document, scenario, scenarioButtons) {
  setPressed(scenarioButtons, scenario.id);

  const detail = document.querySelector("[data-scenario-detail]");
  const title = element(document, "h3", "", scenario.question);
  const badges = element(document, "div", "source-badges");
  for (const sourceKind of scenario.expectedSources) {
    badges.append(element(document, "span", "source-badge", sourceKind));
  }

  const heading = element(document, "p", "label", "Expected request path");
  const steps = element(document, "ol", "scenario-steps");
  scenario.steps.forEach((step, index) => {
    const item = element(document, "li", "");
    item.append(
      element(document, "span", "step-number", String(index + 1)),
      element(document, "span", "", step),
    );
    steps.append(item);
  });

  const note = element(
    document,
    "p",
    "static-note",
    "The live assistant generates the final wording at request time. This page stores no answer or weather value.",
  );
  replaceChildren(detail, [badges, title, heading, steps, note]);
}

function renderDecisions(document) {
  const list = document.querySelector("[data-decision-list]");
  const items = DECISIONS.map((decision, index) => {
    const details = element(document, "details", "decision-card");
    if (index === 0) {
      details.open = true;
    }
    const summary = element(document, "summary", "", decision.title);
    const copy = element(document, "p", "", decision.summary);
    details.append(summary, copy);
    return details;
  });
  replaceChildren(list, items);
}

export function initializeShowcase(document) {
  const sourceList = document.querySelector("[data-source-list]");
  const sourceButtons = SOURCES.map((source) =>
    sourceCard(document, source, (selectedId) => {
      const selected = SOURCES.find(({ id }) => id === selectedId);
      renderSource(document, selected, sourceButtons);
    }),
  );
  replaceChildren(sourceList, sourceButtons);

  const scenarioList = document.querySelector("[data-scenario-list]");
  const scenarioButtons = SCENARIOS.map((scenario, index) =>
    scenarioButton(document, scenario, index, (selectedId) => {
      const selected = SCENARIOS.find(({ id }) => id === selectedId);
      renderScenario(document, selected, scenarioButtons);
    }),
  );
  replaceChildren(scenarioList, scenarioButtons);

  renderDecisions(document);
  renderSource(document, SOURCES[0], sourceButtons);
  renderScenario(document, SCENARIOS[0], scenarioButtons);
}

if (typeof globalThis.document !== "undefined") {
  initializeShowcase(globalThis.document);
}
