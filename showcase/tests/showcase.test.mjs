import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { initializeShowcase } from "../assets/app.mjs";
import { DECISIONS, SCENARIOS, SOURCES } from "../assets/scenarios.mjs";
import { FakeDocument } from "./fake-dom.mjs";

const root = new URL("../../", import.meta.url);

async function text(path) {
  return readFile(new URL(path, root), "utf8");
}

test("README follows the recruiter evaluation path", async () => {
  const readme = await text("README.md");
  const headings = [
    "## What this project demonstrates",
    "## Architecture",
    "## Quick start",
    "## Required scenarios",
    "## Verification",
    "## Reliability boundaries",
    "## Project structure",
    "## AI-assisted development",
  ];

  let previous = -1;
  for (const heading of headings) {
    const current = readme.indexOf(heading);
    assert.ok(current > previous, `${heading} must appear in the expected order`);
    previous = current;
  }

  assert.match(readme, /Interactive architecture walkthrough/);
  assert.match(readme, /\[Recruitment task\]\(RECRUITMENT_TASK\.md\)/);
  assert.match(readme, /No fabricated live results/i);
  assert.match(readme, /qwen3:4b-instruct-2507-q4_K_M/);
  assert.match(readme, /qwen3-embedding:0\.6b/);
  assert.doesNotMatch(readme, /Interview guide|repository-guide|docs\/superpowers|design-qa/i);
});

test("public repository tracks only the two approved Markdown documents", () => {
  const repository = fileURLToPath(root);
  const tracked = execFileSync("git", ["ls-files", "*.md", "*.MD"], {
    cwd: repository,
    encoding: "utf8",
  }).trim().split("\n").filter(Boolean);

  assert.deepEqual(tracked, ["README.md", "RECRUITMENT_TASK.md"]);
});

test("README links each reliability boundary to executable proof", async () => {
  const readme = await text("README.md");
  const boundaries = readme.slice(
    readme.indexOf("## Reliability boundaries"),
    readme.indexOf("## Project structure"),
  );

  for (const risk of [
    "Unsourced factual answer",
    "One passing run hides instability",
    "Skipped pgvector integration",
    "Stale RAG knowledge",
  ]) {
    assert.ok(boundaries.includes(risk), `missing risk label: ${risk}`);
  }
  for (const proof of [
    /\[Evidence requirement test\]\(assistant-app\/src\/test\/java\/com\/cdq\/assistant\/chat\/application\/EvidenceRequirementPolicyTest\.java\)/,
    /\[Repeated reliability evaluator\]\(scripts\/reliability\.mjs\)/,
    /\[pgvector proof script\]\(scripts\/assert-pgvector-it-ran\.mjs\)/,
    /\[Knowledge freshness script\]\(scripts\/check-knowledge-freshness\.mjs\)/,
  ]) {
    assert.match(boundaries, proof, `missing executable proof link: ${proof}`);
  }
  assert.match(boundaries, /does not prove every generated sentence/);
});

test("README runs the pgvector execution proof after Maven verification", async () => {
  const readme = await text("README.md");

  assert.match(
    readme,
    /\.\/mvnw --batch-mode verify\nnode scripts\/assert-pgvector-it-ran\.mjs/,
  );
});

test("README distinguishes both safe 503 outcomes", async () => {
  const readme = await text("README.md");
  assert.match(readme, /Answer not verified/);
  assert.match(readme, /Dependency unavailable/);
});

test("showcase represents all required sources and scenarios", async () => {
  assert.deepEqual(
    SOURCES.map(({ kind }) => kind),
    ["CDQ_RAG", "REST_COUNTRIES", "WEATHER"],
  );
  assert.equal(SCENARIOS.length, 6);
  assert.deepEqual(
    SCENARIOS.map(({ expectedSources }) => expectedSources),
    [
      ["REST_COUNTRIES"],
      ["WEATHER"],
      ["REST_COUNTRIES", "WEATHER"],
      ["REST_COUNTRIES"],
      ["CDQ_RAG"],
      ["REST_COUNTRIES", "WEATHER"],
    ],
  );
});

test("showcase controls render and switch every source and scenario", () => {
  const document = new FakeDocument();
  initializeShowcase(document);

  const sourceButtons = document.querySelector("[data-source-list]").children;
  const architecture = document.querySelector("[data-architecture]");
  const sourceDetail = document.querySelector("[data-source-detail]");
  assert.equal(sourceButtons.length, SOURCES.length);

  SOURCES.forEach((source, selectedIndex) => {
    sourceButtons[selectedIndex].click();
    sourceButtons.forEach((button, index) => {
      assert.equal(button.getAttribute("aria-pressed"), String(index === selectedIndex));
      assert.equal(button.className.includes("is-selected"), index === selectedIndex);
    });
    assert.match(architecture.textContent, new RegExp(source.tool));
    assert.match(sourceDetail.textContent, new RegExp(source.label));
    assert.match(sourceDetail.textContent, new RegExp(source.upstream));
  });

  const scenarioButtons = document.querySelector("[data-scenario-list]").children;
  const scenarioDetail = document.querySelector("[data-scenario-detail]");
  assert.equal(scenarioButtons.length, SCENARIOS.length);

  SCENARIOS.forEach((scenario, selectedIndex) => {
    scenarioButtons[selectedIndex].click();
    scenarioButtons.forEach((button, index) => {
      assert.equal(button.getAttribute("aria-pressed"), String(index === selectedIndex));
      assert.equal(button.className.includes("is-selected"), index === selectedIndex);
    });
    assert.match(scenarioDetail.textContent, new RegExp(scenario.question.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
    for (const source of scenario.expectedSources) {
      assert.match(scenarioDetail.textContent, new RegExp(source));
    }
    assert.match(scenarioDetail.textContent, /stores no answer or weather value/);
  });

  const decisions = document.querySelector("[data-decision-list]").children;
  assert.equal(decisions.length, DECISIONS.length);
  assert.equal(decisions[0].tagName, "DETAILS");
  assert.equal(decisions[0].open, true);
  assert.equal(decisions[0].children[0].tagName, "SUMMARY");
  assert.match(decisions[0].textContent, new RegExp(DECISIONS[0].title));
});

test("static showcase data contains no recorded temperature", async () => {
  const source = await text("showcase/assets/scenarios.mjs");
  const forbiddenTemperatureFields = [
    "current" + "Temperature",
    "temperature" + "Value",
  ];

  assert.doesNotMatch(source, /-?\d+(?:\.\d+)?\s*°?C\b/i);
  for (const field of forbiddenTemperatureFields) {
    assert.doesNotMatch(source, new RegExp(field));
  }
});

test("showcase page exposes semantic and interactive landmarks", async () => {
  const html = await text("showcase/index.html");
  for (const marker of [
    "<header",
    "<nav",
    "<main",
    "<footer",
    'id="sources"',
    'id="architecture"',
    'id="scenarios"',
    'id="decisions"',
    'id="verification"',
    'aria-live="polite"',
    "Interactive architecture walkthrough",
    "This is not a live assistant",
  ]) {
    assert.ok(html.includes(marker), `missing ${marker}`);
  }
  assert.doesNotMatch(html, /<input|contenteditable|api\/chat/i);
});

test("all local assets referenced by the page exist", async () => {
  const html = await text("showcase/index.html");
  const references = [...html.matchAll(/(?:href|src)="(\.\/assets\/[^"]+)"/g)]
    .map(([, reference]) => reference);

  assert.deepEqual(
    references,
    ["./assets/styles.css", "./assets/app.mjs"],
  );
  await Promise.all(
    references.map((reference) =>
      readFile(new URL(reference, new URL("showcase/index.html", root)), "utf8"),
    ),
  );
  await text("showcase/assets/scenarios.mjs");
});

test("Pages workflow publishes only the showcase directory", async () => {
  const workflow = await text(".github/workflows/pages.yml");
  for (const required of [
    "pages: write",
    "id-token: write",
    "actions/configure-pages@v6",
    "actions/upload-pages-artifact@v5",
    "path: showcase",
    "actions/deploy-pages@v5",
  ]) {
    assert.ok(workflow.includes(required), `missing ${required}`);
  }
});

test("main CI runs the showcase contract", async () => {
  const workflow = await text(".github/workflows/ci.yml");
  assert.match(workflow, /node --test showcase\/tests\/showcase\.test\.mjs/);
});
