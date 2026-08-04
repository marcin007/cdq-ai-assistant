import { createHash, timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const canonicalSourceUrl = 'https://www.cdq.com/products/cdq-fraud-guard';
const millisecondsPerDay = 86_400_000;
const hexadecimalHash = /^[a-f0-9]{64}$/;
const isoInstant = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/;

export class KnowledgeCheckError extends Error {}

function reject(reason) {
  throw new KnowledgeCheckError(reason);
}

function parseMetadata(metadataText) {
  let metadata;
  try {
    metadata = JSON.parse(metadataText);
  } catch {
    reject('metadata is not valid JSON');
  }

  if (metadata === null || Array.isArray(metadata) || typeof metadata !== 'object') {
    reject('metadata must be an object');
  }
  if (
    typeof metadata.sourceUrl !== 'string' ||
    typeof metadata.capturedAt !== 'string' ||
    typeof metadata.snapshotHash !== 'string'
  ) {
    reject('metadata fields are invalid');
  }
  if (metadata.sourceUrl !== canonicalSourceUrl) {
    reject('metadata source URL is not canonical');
  }
  if (!hexadecimalHash.test(metadata.snapshotHash)) {
    reject('metadata snapshot hash is invalid');
  }
  if (!isoInstant.test(metadata.capturedAt)) {
    reject('metadata capture timestamp is invalid');
  }

  const capturedAt = new Date(metadata.capturedAt);
  if (Number.isNaN(capturedAt.getTime())) {
    reject('metadata capture timestamp is invalid');
  }
  const normalizedTimestamp = metadata.capturedAt.includes('.')
    ? metadata.capturedAt
    : metadata.capturedAt.replace('Z', '.000Z');
  if (capturedAt.toISOString() !== normalizedTimestamp) {
    reject('metadata capture timestamp is invalid');
  }

  return { capturedAt, snapshotHash: metadata.snapshotHash };
}

function validateMaxAgeDays(maxAgeDays) {
  if (!Number.isInteger(maxAgeDays) || maxAgeDays < 1 || maxAgeDays > 365) {
    reject('max age days is invalid');
  }
}

export function verifySnapshot({ textBytes, metadataText, now, maxAgeDays }) {
  validateMaxAgeDays(maxAgeDays);
  const { capturedAt, snapshotHash } = parseMetadata(metadataText);
  if (!(now instanceof Date) || Number.isNaN(now.getTime())) {
    reject('current time is invalid');
  }

  const ageMs = now.getTime() - capturedAt.getTime();
  if (ageMs < 0) {
    reject('capture timestamp is in the future');
  }
  if (ageMs > maxAgeDays * millisecondsPerDay) {
    reject('knowledge is older than the age budget');
  }

  const actualHash = createHash('sha256').update(textBytes).digest('hex');
  const expectedHashBytes = Buffer.from(snapshotHash, 'hex');
  const actualHashBytes = Buffer.from(actualHash, 'hex');
  if (!timingSafeEqual(expectedHashBytes, actualHashBytes)) {
    reject('snapshot hash does not match text');
  }

  return {
    snapshotHash,
    capturedAt: capturedAt.toISOString().replace('.000Z', 'Z'),
    ageDays: Math.floor(ageMs / millisecondsPerDay)
  };
}

export async function verifySnapshotFiles({ textPath, metadataPath, now, maxAgeDays }) {
  let textBytes;
  let metadataText;
  try {
    [textBytes, metadataText] = await Promise.all([
      readFile(textPath),
      readFile(metadataPath, 'utf8')
    ]);
  } catch {
    reject('knowledge files could not be read');
  }

  return verifySnapshot({ textBytes, metadataText, now, maxAgeDays });
}

function parseCliArguments(args) {
  if (args.length === 0) {
    return 45;
  }
  if (args.length !== 2 || args[0] !== '--max-age-days' || !/^[1-9]\d*$/.test(args[1])) {
    reject('invalid CLI arguments');
  }
  const maxAgeDays = Number(args[1]);
  validateMaxAgeDays(maxAgeDays);
  return maxAgeDays;
}

async function main() {
  let maxAgeDays;
  try {
    maxAgeDays = parseCliArguments(process.argv.slice(2));
  } catch (error) {
    reportFailure(error, 2);
    return;
  }

  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  try {
    const result = await verifySnapshotFiles({
      textPath: path.join(root, 'knowledge', 'cdq-fraud-guard.txt'),
      metadataPath: path.join(root, 'knowledge', 'cdq-fraud-guard.source.json'),
      now: new Date(),
      maxAgeDays
    });
    process.stdout.write(
      `knowledge-check: verified\n` +
      `snapshot hash: ${result.snapshotHash}\n` +
      `captured at: ${result.capturedAt}\n` +
      `age days: ${result.ageDays}\n` +
      `max age days: ${maxAgeDays}\n`
    );
  } catch (error) {
    reportFailure(error, 1);
  }
}

function reportFailure(error, exitCode) {
  const reason = error instanceof KnowledgeCheckError ? error.message : 'knowledge check failed';
  process.stderr.write(
    `knowledge-check: ${reason}; recapture, review, and version the CDQ snapshot\n`
  );
  process.exitCode = exitCode;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
