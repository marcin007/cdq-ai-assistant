import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const EXPECTED_SUITE_NAME = 'com.cdq.assistant.rag.CdqPgVectorIT';
const REPORT_PATH = new URL(
  '../assistant-app/target/failsafe-reports/TEST-com.cdq.assistant.rag.CdqPgVectorIT.xml',
  import.meta.url
);

export class ReportAssertionError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ReportAssertionError';
  }
}

function readRequiredAttribute(attributes, name) {
  if (!attributes.has(name)) {
    throw new ReportAssertionError(`missing testsuite attribute: ${name}`);
  }
  return attributes.get(name);
}

function parseNonNegativeInteger(attributes, name) {
  const value = readRequiredAttribute(attributes, name);
  const number = Number(value);
  if (!/^(?:0|[1-9][0-9]*)$/.test(value) || !Number.isSafeInteger(number)) {
    throw new ReportAssertionError(`invalid testsuite attribute: ${name}`);
  }
  return number;
}

const XML_NAME = /^[A-Za-z_][A-Za-z0-9_.:-]*/;
const COMPLETE_XML_NAME = /^[A-Za-z_][A-Za-z0-9_.:-]*$/;
const REQUIRED_SUITE_ATTRIBUTES = new Set(['name', 'tests', 'skipped', 'failures', 'errors']);

function malformedReport() {
  throw new ReportAssertionError('malformed Failsafe report');
}

function duplicateSuite() {
  throw new ReportAssertionError('expected exactly one testsuite element');
}

function validateEntityReferences(text) {
  let ampersand = text.indexOf('&');
  while (ampersand !== -1) {
    const semicolon = text.indexOf(';', ampersand + 1);
    if (semicolon === -1) malformedReport();
    const reference = text.slice(ampersand + 1, semicolon);
    if (!/^(?:amp|lt|gt|apos|quot|#[0-9]+|#x[0-9A-Fa-f]+)$/.test(reference)) {
      malformedReport();
    }
    ampersand = text.indexOf('&', semicolon + 1);
  }
}

function validateAttributes(source, isSuiteRoot) {
  const attributes = new Map();
  let cursor = 0;
  while (cursor < source.length) {
    if (!/\s/.test(source[cursor])) malformedReport();
    while (cursor < source.length && /\s/.test(source[cursor])) cursor += 1;
    if (cursor === source.length) return attributes;

    const nameMatch = XML_NAME.exec(source.slice(cursor));
    if (!nameMatch) malformedReport();
    const name = nameMatch[0];
    cursor += name.length;
    while (cursor < source.length && /\s/.test(source[cursor])) cursor += 1;
    if (source[cursor] !== '=') malformedReport();
    cursor += 1;
    while (cursor < source.length && /\s/.test(source[cursor])) cursor += 1;

    const quote = source[cursor];
    if (quote !== '"' && quote !== "'") malformedReport();
    cursor += 1;
    const valueEnd = source.indexOf(quote, cursor);
    if (valueEnd === -1) malformedReport();
    const value = source.slice(cursor, valueEnd);
    if (value.includes('<')) malformedReport();
    validateEntityReferences(value);
    cursor = valueEnd + 1;

    if (attributes.has(name)) {
      if (isSuiteRoot && REQUIRED_SUITE_ATTRIBUTES.has(name)) {
        throw new ReportAssertionError('duplicate testsuite attribute: ' + name);
      }
      malformedReport();
    }
    attributes.set(name, value);
  }
  return attributes;
}

function findTagEnd(xml, start) {
  let quote = null;
  for (let cursor = start; cursor < xml.length; cursor += 1) {
    const character = xml[cursor];
    if (quote !== null) {
      if (character === quote) quote = null;
    } else if (character === '"' || character === "'") {
      quote = character;
    } else if (character === '<') {
      malformedReport();
    } else if (character === '>') {
      return cursor;
    }
  }
  malformedReport();
}

function parseOpeningTag(source, isRootCandidate) {
  const trimmed = source.trimEnd();
  const selfClosing = trimmed.endsWith('/');
  const content = selfClosing ? trimmed.slice(0, -1) : trimmed;
  const nameMatch = XML_NAME.exec(content);
  if (!nameMatch) malformedReport();
  const name = nameMatch[0];
  const attributeSource = content.slice(name.length);
  const attributes = validateAttributes(attributeSource, isRootCandidate && name === 'testsuite');
  return { name, attributes, selfClosing };
}

function parseXmlDocument(xml) {
  if (typeof xml !== 'string') malformedReport();

  const stack = [];
  let cursor = xml.charCodeAt(0) === 0xFEFF ? 1 : 0;
  const declarationPosition = cursor;
  let rootName = null;
  let rootAttributes = null;
  let rootClosed = false;
  let xmlDeclarationSeen = false;

  while (cursor < xml.length) {
    if (xml[cursor] !== '<') {
      const nextTag = xml.indexOf('<', cursor);
      const end = nextTag === -1 ? xml.length : nextTag;
      const text = xml.slice(cursor, end);
      if (stack.length === 0 && text.trim() !== '') malformedReport();
      if (text.includes(']]>')) malformedReport();
      validateEntityReferences(text);
      cursor = end;
      continue;
    }

    if (xml.startsWith('<![CDATA[', cursor)) {
      if (stack.length === 0) malformedReport();
      const end = xml.indexOf(']]>', cursor + 9);
      if (end === -1) malformedReport();
      cursor = end + 3;
      continue;
    }

    if (xml.startsWith('<!--', cursor)) {
      const end = xml.indexOf('-->', cursor + 4);
      if (end === -1 || xml.slice(cursor + 4, end).includes('--')) malformedReport();
      cursor = end + 3;
      continue;
    }

    if (xml.startsWith('<?', cursor)) {
      const end = xml.indexOf('?>', cursor + 2);
      if (end === -1) malformedReport();
      const instruction = xml.slice(cursor + 2, end);
      const targetMatch = XML_NAME.exec(instruction);
      if (!targetMatch) malformedReport();
      const target = targetMatch[0];
      if (instruction.length > target.length && !/\s/.test(instruction[target.length])) {
        malformedReport();
      }
      if (target.toLowerCase() === 'xml') {
        if (target !== 'xml' || xmlDeclarationSeen || cursor !== declarationPosition) {
          malformedReport();
        }
        xmlDeclarationSeen = true;
      }
      cursor = end + 2;
      continue;
    }

    if (xml.startsWith('<!', cursor)) malformedReport();

    if (xml.startsWith('</', cursor)) {
      const end = xml.indexOf('>', cursor + 2);
      if (end === -1) malformedReport();
      const name = xml.slice(cursor + 2, end).trim();
      if (!COMPLETE_XML_NAME.test(name) || stack.length === 0) malformedReport();
      const expected = stack.pop();
      if (name !== expected) malformedReport();
      if (stack.length === 0) rootClosed = true;
      cursor = end + 1;
      continue;
    }

    const end = findTagEnd(xml, cursor + 1);
    const isRootCandidate = stack.length === 0 && rootName === null;
    const element = parseOpeningTag(xml.slice(cursor + 1, end), isRootCandidate);
    if (stack.length === 0) {
      if (rootName !== null) {
        if (element.name === 'testsuite') duplicateSuite();
        malformedReport();
      }
      if (element.name !== 'testsuite') malformedReport();
      rootName = element.name;
      rootAttributes = element.attributes;
    } else if (element.name === 'testsuite') {
      duplicateSuite();
    }

    if (element.selfClosing) {
      if (stack.length === 0) rootClosed = true;
    } else {
      stack.push(element.name);
    }
    cursor = end + 1;
  }

  if (rootName === null || !rootClosed || stack.length !== 0) malformedReport();
  return rootAttributes;
}

function parseSuite(xml) {
  const attributes = parseXmlDocument(xml);
  return {
    name: readRequiredAttribute(attributes, 'name'),
    tests: parseNonNegativeInteger(attributes, 'tests'),
    skipped: parseNonNegativeInteger(attributes, 'skipped'),
    failures: parseNonNegativeInteger(attributes, 'failures'),
    errors: parseNonNegativeInteger(attributes, 'errors')
  };
}

export function parseTestSuite(xml) {
  const { tests, skipped, failures, errors } = parseSuite(xml);
  return { tests, skipped, failures, errors };
}

export function assertPgVectorReport(xml) {
  const suite = parseSuite(xml);
  if (suite.name !== EXPECTED_SUITE_NAME) {
    throw new ReportAssertionError('unexpected testsuite name');
  }
  if (suite.tests < 1) throw new ReportAssertionError('pgvector integration test did not execute');
  if (suite.skipped !== 0) throw new ReportAssertionError('pgvector integration test was skipped');
  if (suite.failures !== 0 || suite.errors !== 0) {
    throw new ReportAssertionError('pgvector integration test did not pass');
  }
}

export async function verifyReportFile(path) {
  assertPgVectorReport(await readFile(path, 'utf8'));
}

export async function runCli({ argv, reportPath, stdout, stderr }) {
  if (argv.length > 0) {
    stderr.write('pgvector-proof: arguments are not supported\n');
    return 2;
  }

  try {
    await verifyReportFile(reportPath);
  } catch {
    stderr.write('pgvector-proof: report missing or invalid\n');
    return 1;
  }

  stdout.write('pgvector-proof: CdqPgVectorIT executed and passed without skips\n');
  return 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await runCli({
    argv: process.argv.slice(2),
    reportPath: REPORT_PATH,
    stdout: process.stdout,
    stderr: process.stderr
  });
}
