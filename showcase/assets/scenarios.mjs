export const SOURCES = Object.freeze([
  Object.freeze({
    id: "cdq",
    kind: "CDQ_RAG",
    label: "CDQ Fraud Guard RAG",
    transport: "Local pgvector search",
    upstream: "Reviewed CDQ product snapshot",
    tool: "search_cdq_fraud_guard",
  }),
  Object.freeze({
    id: "countries",
    kind: "REST_COUNTRIES",
    label: "Countries MCP",
    transport: "MCP Streamable HTTP",
    upstream: "REST Countries v5",
    tool: "countries_get_by_name / countries_get_by_capital",
  }),
  Object.freeze({
    id: "weather",
    kind: "WEATHER",
    label: "Weather MCP",
    transport: "MCP stdio",
    upstream: "WeatherAPI through semdin/mcp-weather",
    tool: "get_weather",
  }),
]);

export const SCENARIOS = Object.freeze([
  Object.freeze({
    id: "germany-capital",
    question: "What is the capital city of Germany?",
    expectedSources: Object.freeze(["REST_COUNTRIES"]),
    steps: Object.freeze([
      "The model selects countries_get_by_name.",
      "Countries MCP requests Germany from REST Countries v5.",
      "The model answers from the returned capital data.",
    ]),
  }),
  Object.freeze({
    id: "munich-weather",
    question: "What is the temperature currently in Munich?",
    expectedSources: Object.freeze(["WEATHER"]),
    steps: Object.freeze([
      "The model selects get_weather for Munich.",
      "Weather MCP requests current conditions from WeatherAPI.",
      "The model reports the returned Celsius value at request time.",
    ]),
  }),
  Object.freeze({
    id: "germany-capital-weather",
    question: "What is the temperature of the capital of Germany currently?",
    expectedSources: Object.freeze(["REST_COUNTRIES", "WEATHER"]),
    steps: Object.freeze([
      "Countries MCP resolves Germany to Berlin.",
      "The result returns to the bounded tool loop.",
      "Weather MCP requests current conditions for Berlin.",
      "The model combines both successful tool results.",
    ]),
  }),
  Object.freeze({
    id: "berlin",
    question: "What do you know about Berlin?",
    expectedSources: Object.freeze(["REST_COUNTRIES"]),
    steps: Object.freeze([
      "The model selects countries_get_by_capital.",
      "Countries MCP resolves Berlin through REST Countries v5.",
      "The model summarizes only the projected country fields.",
    ]),
  }),
  Object.freeze({
    id: "fraud-guard",
    question: "Which CDQ Fraud Guard features help prevent payment fraud?",
    expectedSources: Object.freeze(["CDQ_RAG"]),
    steps: Object.freeze([
      "The query is embedded with qwen3-embedding:0.6b.",
      "pgvector returns up to four relevant snapshot chunks.",
      "The model answers from the retrieved CDQ context.",
    ]),
  }),
  Object.freeze({
    id: "japan-capital-weather",
    question: "What is Japan’s capital and what is the current temperature there?",
    expectedSources: Object.freeze(["REST_COUNTRIES", "WEATHER"]),
    steps: Object.freeze([
      "Countries MCP resolves Japan to Tokyo.",
      "The result returns to the bounded tool loop.",
      "Weather MCP requests current conditions for Tokyo.",
      "The model combines both successful tool results.",
    ]),
  }),
]);

export const DECISIONS = Object.freeze([
  Object.freeze({
    title: "Stateless requests",
    summary: "Each API request contains only the system policy and current question.",
  }),
  Object.freeze({
    title: "Bounded orchestration",
    summary: "A maximum of four model calls permits chaining while preventing loops.",
  }),
  Object.freeze({
    title: "Executed-source attribution",
    summary: "A source is recorded only after its tool callback succeeds.",
  }),
  Object.freeze({
    title: "Reviewable knowledge",
    summary: "The CDQ snapshot carries a source URL, capture time, and SHA-256.",
  }),
]);
