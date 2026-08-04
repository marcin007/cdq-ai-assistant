import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';

import {
  ReportAssertionError,
  assertPgVectorReport,
  parseTestSuite,
  runCli,
  verifyReportFile
} from './assert-pgvector-it-ran.mjs';

const passing = '<testsuite name="com.cdq.assistant.rag.CdqPgVectorIT" tests="1" skipped="0" failures="0" errors="0"></testsuite>';
const skipped = '<testsuite name="com.cdq.assistant.rag.CdqPgVectorIT" tests="1" skipped="1" failures="0" errors="0"></testsuite>';
const failed = '<testsuite name="com.cdq.assistant.rag.CdqPgVectorIT" tests="1" skipped="0" failures="1" errors="0"></testsuite>';
const empty = '<testsuite name="com.cdq.assistant.rag.CdqPgVectorIT" tests="0" skipped="0" failures="0" errors="0"></testsuite>';
const failsafeCompatible = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:noNamespaceSchemaLocation="https://maven.apache.org/surefire/maven-failsafe-plugin/xsd/failsafe-test-report.xsd" version="3.0.2" name="com.cdq.assistant.rag.CdqPgVectorIT" time="0.123" tests="1" errors="0" skipped="0" failures="0" flakes="0">',
  '  <properties>',
  '    <property name="java.version" value="21"/>',
  '  </properties>',
  '  <testcase name="migratesAndRetrieves" classname="com.cdq.assistant.rag.CdqPgVectorIT" time="0.1">',
  '    <system-out><![CDATA[diagnostic text with a literal <testsuite token and unescaped & data]]></system-out>',
  '  </testcase>',
  '</testsuite>'
].join('\n');

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const workflowPath = path.join(root, '.github', 'workflows', 'ci.yml');

function parseWorkflowSteps(workflow) {
  const lines = workflow.split(/\r?\n/);
  const stepsHeaderIndex = lines.findIndex((line) => /^\s*steps:\s*(?:#.*)?$/.test(line));
  assert.notEqual(stepsHeaderIndex, -1, 'expected CI workflow to define steps');

  const stepsIndent = lines[stepsHeaderIndex].match(/^\s*/)[0].length;
  const rawSteps = [];
  let stepIndent;
  let currentStep;

  for (const line of lines.slice(stepsHeaderIndex + 1)) {
    if (line.trim() === '' || line.trimStart().startsWith('#')) continue;
    const indentation = line.match(/^\s*/)[0].length;
    if (indentation <= stepsIndent) break;

    const listItem = /^(\s*)-\s+(.+)$/.exec(line);
    if (listItem && (stepIndent === undefined || indentation === stepIndent)) {
      stepIndent ??= indentation;
      if (currentStep) rawSteps.push(currentStep);
      currentStep = { inlineField: listItem[2], childLines: [] };
    } else if (currentStep) {
      currentStep.childLines.push(line);
    }
  }
  if (currentStep) rawSteps.push(currentStep);

  return rawSteps.map(({ inlineField, childLines }) => {
    const fieldLines = [inlineField];
    const nonBlankChildren = childLines.filter((line) => line.trim() !== '');
    const fieldIndent = nonBlankChildren.length === 0
      ? undefined
      : Math.min(...nonBlankChildren.map((line) => line.match(/^\s*/)[0].length));
    fieldLines.push(...nonBlankChildren
      .filter((line) => line.match(/^\s*/)[0].length === fieldIndent)
      .map((line) => line.trim()));

    const runField = fieldLines.find((line) => /^run:\s*/.test(line));
    return {
      run: runField?.replace(/^run:\s*/, '').trim(),
      conditional: fieldLines.some((line) => /^if:\s*/.test(line)),
      continueOnError: fieldLines.some((line) => /^continue-on-error:\s*/.test(line))
    };
  });
}

function requireUnconditionalRunStep(workflowSteps, command) {
  const matches = workflowSteps
    .map((step, index) => ({ step, index }))
    .filter(({ step }) => step.run === command);
  assert.equal(matches.length, 1, `expected CI to run exactly once: ${command}`);
  assert.equal(matches[0].step.conditional, false, `expected CI step to be unconditional: ${command}`);
  assert.equal(matches[0].step.continueOnError, false, `expected CI step to fail closed: ${command}`);
  return matches[0].index;
}

function collectOutput() {
  let value = '';
  return {
    write(chunk) {
      value += chunk;
    },
    value() {
      return value;
    }
  };
}

async function withTemporaryReport(contents, run) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'cdq-pgvector-report-'));
  const reportPath = path.join(directory, 'TEST-com.cdq.assistant.rag.CdqPgVectorIT.xml');
  try {
    if (contents !== undefined) {
      await writeFile(reportPath, contents, 'utf8');
    }
    await run(pathToFileURL(reportPath));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

test('parses exact numeric counts from the passing CdqPgVectorIT suite', () => {
  assert.deepEqual(parseTestSuite(passing), {
    tests: 1,
    skipped: 0,
    failures: 0,
    errors: 0
  });
});

for (const { name, xml, message } of [
  { name: 'a skipped suite', xml: skipped, message: 'pgvector integration test was skipped' },
  { name: 'a failed suite', xml: failed, message: 'pgvector integration test did not pass' },
  { name: 'an empty suite', xml: empty, message: 'pgvector integration test did not execute' },
  {
    name: 'a suite with a wrong name',
    xml: passing.replace('CdqPgVectorIT', 'AnotherIT'),
    message: 'unexpected testsuite name'
  },
  {
    name: 'duplicate testsuite elements',
    xml: `${passing}${passing}`,
    message: 'expected exactly one testsuite element'
  },
  {
    name: 'a negative numeric attribute',
    xml: passing.replace('tests="1"', 'tests="-1"'),
    message: 'invalid testsuite attribute: tests'
  },
  {
    name: 'a non-numeric attribute',
    xml: passing.replace('skipped="0"', 'skipped="zero"'),
    message: 'invalid testsuite attribute: skipped'
  },
  {
    name: 'an integer above the safe range',
    xml: passing.replace('tests="1"', 'tests="9007199254740992"'),
    message: 'invalid testsuite attribute: tests'
  },
  {
    name: 'an extremely large decimal integer',
    xml: passing.replace('tests="1"', 'tests="' + '9'.repeat(400) + '"'),
    message: 'invalid testsuite attribute: tests'
  },
  {
    name: 'a missing attribute',
    xml: passing.replace(' errors="0"', ''),
    message: 'missing testsuite attribute: errors'
  },
  {
    name: 'required attributes embedded in another attribute value',
    xml: '<testsuite decoy=\' name="com.cdq.assistant.rag.CdqPgVectorIT" tests="1" skipped="0" failures="0" errors="0"\'></testsuite>',
    message: 'missing testsuite attribute: name'
  },
  {
    name: 'a duplicate attribute',
    xml: passing.replace(' tests="1"', ' tests="1" tests="1"'),
    message: 'duplicate testsuite attribute: tests'
  },
  {
    name: 'malformed text',
    xml: 'not a Failsafe XML report',
    message: 'malformed Failsafe report'
  },
  {
    name: 'an unclosed nested element',
    xml: passing.replace('</testsuite>', '<testcase></testsuite>'),
    message: 'malformed Failsafe report'
  },
  {
    name: 'mismatched nested elements',
    xml: passing.replace('</testsuite>', '<properties><property/></testcase></testsuite>'),
    message: 'malformed Failsafe report'
  },
  {
    name: 'an unterminated CDATA section',
    xml: passing.replace('</testsuite>', '<system-out><![CDATA[unterminated</system-out></testsuite>'),
    message: 'malformed Failsafe report'
  }
]) {
  test(`rejects ${name} with a fixed error`, () => {
    assert.throws(
      () => assertPgVectorReport(xml),
      (error) => error instanceof ReportAssertionError && error.message === message
    );
  });
}

test('accepts a passing CdqPgVectorIT suite', () => {
  assert.doesNotThrow(() => assertPgVectorReport(passing));
});

test('accepts the Maven Failsafe XML structure and ignores markup-like CDATA text', () => {
  assert.deepEqual(parseTestSuite(failsafeCompatible), {
    tests: 1,
    skipped: 0,
    failures: 0,
    errors: 0
  });
  assert.doesNotThrow(() => assertPgVectorReport(failsafeCompatible));
});

test('verifyReportFile resolves for a passing temporary report', async () => {
  await withTemporaryReport(passing, async (reportPath) => {
    await assert.doesNotReject(() => verifyReportFile(reportPath));
  });
});

test('verifyReportFile rejects a missing temporary report', async () => {
  await withTemporaryReport(undefined, async (reportPath) => {
    await assert.rejects(() => verifyReportFile(reportPath));
  });
});

test('runCli reports a missing report without exposing file details', async () => {
  await withTemporaryReport(undefined, async (reportPath) => {
    const stdout = collectOutput();
    const stderr = collectOutput();

    const code = await runCli({ argv: [], reportPath, stdout, stderr });

    assert.equal(code, 1);
    assert.equal(stdout.value(), '');
    assert.equal(stderr.value(), 'pgvector-proof: report missing or invalid\n');
  });
});

test('runCli confirms the passing report', async () => {
  await withTemporaryReport(passing, async (reportPath) => {
    const stdout = collectOutput();
    const stderr = collectOutput();

    const code = await runCli({ argv: [], reportPath, stdout, stderr });

    assert.equal(code, 0);
    assert.equal(stdout.value(), 'pgvector-proof: CdqPgVectorIT executed and passed without skips\n');
    assert.equal(stderr.value(), '');
  });
});

test('runCli rejects arguments before reading the report', async () => {
  const stdout = collectOutput();
  const stderr = collectOutput();

  const code = await runCli({
    argv: ['--report', '/unreadable/path.xml'],
    reportPath: pathToFileURL('/unreadable/path.xml'),
    stdout,
    stderr
  });

  assert.equal(code, 2);
  assert.equal(stdout.value(), '');
  assert.equal(stderr.value(), 'pgvector-proof: arguments are not supported\n');
});

test('CI runs the pgvector proof immediately after Maven verification', async () => {
  const workflowSteps = parseWorkflowSteps(await readFile(workflowPath, 'utf8'));
  const unitTestCommand = 'node --test scripts/assert-pgvector-it-ran.test.mjs';
  const mavenVerifyCommand = './mvnw --batch-mode verify';
  const proofCommand = 'node scripts/assert-pgvector-it-ran.mjs';

  const unitTestIndex = requireUnconditionalRunStep(workflowSteps, unitTestCommand);
  const mavenVerifyIndex = requireUnconditionalRunStep(workflowSteps, mavenVerifyCommand);
  const proofIndex = requireUnconditionalRunStep(workflowSteps, proofCommand);
  assert.ok(unitTestIndex < mavenVerifyIndex, 'pgvector unit test must run before Maven verification');
  assert.equal(proofIndex, mavenVerifyIndex + 1, 'pgvector proof must run immediately after Maven verification');
});
