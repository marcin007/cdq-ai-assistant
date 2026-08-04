const allowedKinds = new Set(['CDQ_RAG', 'REST_COUNTRIES', 'WEATHER']);

export const CANONICAL_SOURCES = Object.freeze({
  CDQ_RAG: Object.freeze({
    label: 'CDQ Fraud Guard',
    url: 'https://www.cdq.com/products/cdq-fraud-guard'
  }),
  REST_COUNTRIES: Object.freeze({
    label: 'REST Countries v5',
    url: 'https://restcountries.com/'
  }),
  WEATHER: Object.freeze({
    label: 'WeatherAPI via semdin/mcp-weather',
    url: 'https://github.com/semdin/mcp-weather'
  })
});

export const MODEL = Object.freeze({
  chatModel: 'qwen3:4b-instruct-2507-q4_K_M',
  embeddingModel: 'qwen3-embedding:0.6b',
  temperature: 0.1,
  thinking: 'unavailable (Instruct-only)',
  maxOutputTokens: 256
});

export class EvaluationValidationError extends Error {}

function isAvailableAnswer(answer) {
  return !/\b(?:not|no|never|cannot|unable|unavailable|unknown|false|incorrect|wrong|myth)\b|(?:can|could|does|do|did|is|are|was|were|will|would|should|has|have|had)n['’]t\b/i.test(answer);
}

function hasCapitalRelation(answer, country, capital) {
  const countryPattern = country.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const capitalPattern = capital.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return [
    new RegExp(
      `\\b${capitalPattern}\\b\\s+is\\s+the\\s+capital(?:\\s+city|\\s+and\\s+largest\\s+city)?\\s+of\\s+\\b${countryPattern}\\b`,
      'i'
    ),
    new RegExp(
      `\\b${countryPattern}\\b['’]s\\s+capital(?:\\s+city)?\\s+is\\s+\\b${capitalPattern}\\b`,
      'i'
    ),
    new RegExp(
      `\\bthe\\s+capital(?:\\s+city)?\\s+of\\s+\\b${countryPattern}\\b\\s+is\\s+\\b${capitalPattern}\\b`,
      'i'
    ),
    new RegExp(
      `\\b${capitalPattern}\\b\\s+is\\s+\\b${countryPattern}\\b['’]s\\s+capital(?:\\s+city)?(?:\\s+and\\s+(?:its\\s+)?largest\\s+city)?`,
      'i'
    )
  ].some((pattern) => pattern.test(answer));
}

function hasEntityCelsiusTemperature(answer, entity) {
  const escapedEntity = entity.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const entityPattern = `\\b${escapedEntity}\\b`;
  const numericCelsius = '[-+]?\\d+(?:[.,]\\d+)?\\s*(?:(?:°\\s*)?C\\b|(?:degrees?\\s+)?Celsius\\b)';
  return [
    `${entityPattern}['’]s\\s+(?:current\\s+)?temperature\\s+is\\s+${numericCelsius}`,
    `(?:current\\s+)?temperature\\s+(?:in|at|for)\\s+${entityPattern}\\s+is\\s+${numericCelsius}`,
    `${entityPattern}\\s+is\\s+(?:currently\\s+)?${numericCelsius}`,
    `${entityPattern}\\s*,?\\s*where\\s+(?:the\\s+)?(?:current\\s+)?temperature\\s+is\\s+${numericCelsius}`,
    `${entityPattern}\\s+(?:currently\\s+)?(?:has|reports|records)\\s+(?:a\\s+)?temperature\\s+(?:of|at)\\s+${numericCelsius}`,
    `${numericCelsius}\\s+(?:in|at|for)\\s+${entityPattern}`,
    `${entityPattern}\\s*(?::|-)\\s*${numericCelsius}`
  ].some((pattern) => new RegExp(pattern, 'i').test(answer));
}

function hasPositiveCdqFeatureRelation(answer) {
  return answer
    .split(/[!?;\n]+|\.(?:\s+|$)|,\s*(?:but|while|whereas)\b|\s+(?:but|while|whereas)\s+/i)
    .some((clause) => /\bCDQ Fraud Guard\b/i.test(clause)
      && /\bfraud\b/i.test(clause)
      && /\b(?:bank account verification|trust scores?|fraud alerts?|fraud cases?|shared database|community|API)\b/i.test(clause)
      && /\b(?:helps?(?:\s+to)?\s+prevent|prevents?|detects?|protects?|verifies?|alerts?|assigns?|reduces?|manages?|uses?|leverages?)\b/i.test(clause));
}

const germanyCapital = (answer) => isAvailableAnswer(answer)
  && hasCapitalRelation(answer, 'Germany', 'Berlin');
const munichWeather = (answer) => isAvailableAnswer(answer)
  && hasEntityCelsiusTemperature(answer, 'Munich');
const germanyCapitalWeather = (answer) => isAvailableAnswer(answer)
  && hasCapitalRelation(answer, 'Germany', 'Berlin')
  && hasEntityCelsiusTemperature(answer, 'Berlin');
const berlinCountry = (answer) => isAvailableAnswer(answer)
  && hasCapitalRelation(answer, 'Germany', 'Berlin');
const cdqPaymentFraud = (answer) => isAvailableAnswer(answer)
  && hasPositiveCdqFeatureRelation(answer);
const japanCapitalWeather = (answer) => isAvailableAnswer(answer)
  && hasCapitalRelation(answer, 'Japan', 'Tokyo')
  && hasEntityCelsiusTemperature(answer, 'Tokyo');

function variant(id, question, kinds, semantic) {
  return { id, question, kinds, semantic };
}

export const CANONICAL_PROMPTS = Object.freeze([
  variant('germany-capital', 'What is the capital city of Germany?', ['REST_COUNTRIES'], germanyCapital),
  variant('munich-weather', 'What is the temperature currently in Munich?', ['WEATHER'], munichWeather),
  variant('germany-capital-weather', 'What is the temperature of the capital of Germany currently?', ['REST_COUNTRIES', 'WEATHER'], germanyCapitalWeather),
  variant('berlin-country', 'What do you know about Berlin?', ['REST_COUNTRIES'], berlinCountry),
  variant('cdq-payment-fraud', 'Which CDQ Fraud Guard features help prevent payment fraud?', ['CDQ_RAG'], cdqPaymentFraud),
  variant('japan-capital-weather', 'What is Japan’s capital and what is the current temperature there?', ['REST_COUNTRIES', 'WEATHER'], japanCapitalWeather)
]);

export const RELIABILITY_PROMPTS = Object.freeze([
  ...CANONICAL_PROMPTS,
  variant('germany-capital-paraphrase', 'Name Germany\'s capital city.', ['REST_COUNTRIES'], germanyCapital),
  variant('munich-weather-paraphrase', 'How many degrees Celsius is it in Munich right now?', ['WEATHER'], munichWeather),
  variant('germany-capital-weather-paraphrase', 'Find Germany\'s capital, then report its current temperature.', ['REST_COUNTRIES', 'WEATHER'], germanyCapitalWeather),
  variant('berlin-country-paraphrase', 'Which country has Berlin as its capital?', ['REST_COUNTRIES'], berlinCountry),
  variant('cdq-payment-fraud-paraphrase', 'How does CDQ Fraud Guard reduce payment fraud risk?', ['CDQ_RAG'], cdqPaymentFraud),
  variant('japan-capital-weather-paraphrase', 'Use Japan\'s capital to tell me the current temperature there.', ['REST_COUNTRIES', 'WEATHER'], japanCapitalWeather)
]);

function validateSourceRecords(sources, expectedKinds) {
  if (!Array.isArray(sources)) {
    throw new EvaluationValidationError('response sources are malformed');
  }
  const seen = new Set();
  const actualKinds = [];
  for (const source of sources) {
    if (!source || typeof source !== 'object' || Array.isArray(source)
        || typeof source.kind !== 'string'
        || !allowedKinds.has(source.kind)
        || typeof source.label !== 'string'
        || source.label.trim() === ''
        || typeof source.url !== 'string') {
      throw new EvaluationValidationError('response sources are malformed');
    }
    let sourceUrl;
    try {
      sourceUrl = new URL(source.url);
    } catch {
      throw new EvaluationValidationError('response sources are malformed');
    }
    if (!['http:', 'https:'].includes(sourceUrl.protocol) || seen.has(source.kind)) {
      throw new EvaluationValidationError('response sources are malformed');
    }
    const canonical = CANONICAL_SOURCES[source.kind];
    if (source.label !== canonical.label || source.url !== canonical.url) {
      throw new EvaluationValidationError('response sources are malformed');
    }
    seen.add(source.kind);
    actualKinds.push(source.kind);
  }
  if (actualKinds.length !== expectedKinds.length
      || actualKinds.some((kind, index) => kind !== expectedKinds[index])) {
    throw new EvaluationValidationError('source kind/order validation failed');
  }
}

export function validateAnswerPayload(payload, prompt) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)
      || typeof payload.answer !== 'string' || payload.answer.trim() === '') {
    throw new EvaluationValidationError('response answer is malformed');
  }
  validateSourceRecords(payload.sources, prompt.kinds);
  if (!prompt.semantic(payload.answer)) {
    throw new EvaluationValidationError('answer failed semantic validation');
  }
}
