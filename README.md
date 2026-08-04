# CDQ AI Assistant

> A local, stateless Java assistant that combines CDQ Fraud Guard knowledge with live country and weather tools through RAG and MCP.

[![CI](https://github.com/marcin007/cdq-ai-assistant/actions/workflows/ci.yml/badge.svg)](https://github.com/marcin007/cdq-ai-assistant/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-1f6feb)
![Spring AI 2.0](https://img.shields.io/badge/Spring_AI-2.0-087f5b)
![Local LLM](https://img.shields.io/badge/LLM-qwen3%3A4b--instruct--2507--q4_K_M-6f42c1)

**[Interactive architecture walkthrough](https://marcin007.github.io/cdq-ai-assistant/)** ·
**[Recruitment task](RECRUITMENT_TASK.md)** · **[Quick start](#quick-start)**

The walkthrough is a static explanation. The assistant, Ollama, pgvector, Countries MCP, and Weather MCP run locally.

## What this project demonstrates

- Local generation with \`qwen3:4b-instruct-2507-q4_K_M\` through Ollama.
- RAG over a provenance-tracked CDQ Fraud Guard snapshot with \`qwen3-embedding:0.6b\` and pgvector.
- Reviewed manual knowledge refresh with explicit approval before ingestion.
- A custom Countries MCP server over Streamable HTTP and pinned Weather MCP over stdio.
- Bounded country-to-capital-to-weather tool orchestration.
- Source attribution from successful tool execution and fail-closed factual answers without required evidence.
- A stateless browser chat and \`POST /api/chat\` API with safe timeout and dependency errors.

## Architecture

\`\`\`mermaid
flowchart LR
    Browser["Browser chat<br/>127.0.0.1:8080"] --> Assistant["Spring Boot assistant<br/>stateless /api/chat"]
    Assistant --> Ollama["Ollama<br/>local Qwen models"]
    Assistant --> Pgvector["PostgreSQL 17 + pgvector<br/>CDQ RAG"]
    Assistant --> Countries["Countries MCP<br/>Streamable HTTP"]
    Countries --> RestCountries["REST Countries v5"]
    Assistant --> Weather["Weather MCP<br/>stdio"]
    Weather --> WeatherAPI["WeatherAPI"]
\`\`\`

Both Java services and PostgreSQL bind to loopback addresses. Secrets stay in ignored \`.env\`; the application never needs committed credentials.

## Quick start

Prerequisites: Java 21+, Docker with Compose v2, Ollama, Node.js 20+, npm, \`curl\`, Git, REST Countries v5 credentials, and a WeatherAPI key.

\`\`\`sh
cp .env.example .env
ollama pull qwen3:4b-instruct-2507-q4_K_M
ollama pull qwen3-embedding:0.6b
./scripts/bootstrap-weather-mcp.sh
./scripts/preflight.sh
./scripts/run-local.sh
\`\`\`

Fill \`REST_COUNTRIES_API_KEY\` and \`WEATHER_API_KEY\` in \`.env\` before preflight. Open <http://127.0.0.1:8080>. Stop the stack from another terminal with \`./scripts/stop-local.sh\`.

The literal \`qwen3:4b\` alias is not used because it currently resolves to a thinking-only model. The runtime pins the published Instruct tag required for direct user-facing answers.

## Required scenarios

| Question | Expected source flow |
| --- | --- |
| What is the capital city of Germany? | \`REST_COUNTRIES\` |
| What is the temperature currently in Munich? | \`WEATHER\` |
| What is the temperature of the capital of Germany currently? | \`REST_COUNTRIES → WEATHER\` |
| What do you know about Berlin? | \`REST_COUNTRIES\` |
| Which CDQ Fraud Guard features help prevent payment fraud? | \`CDQ_RAG\` |
| What is Japan's capital and what is the current temperature there? | \`REST_COUNTRIES → WEATHER\` |

Current temperatures are requested live and are never hard-coded.

## Verification

Run the offline and build checks:

\`\`\`sh
sh scripts/test-bootstrap-weather-mcp.sh
sh scripts/test-local-operations.sh
node --test assistant-app/src/test/frontend/chat-page.test.mjs
node --test assistant-app/src/test/frontend/knowledge-panel.test.mjs
node --test scripts/evaluate.test.mjs
node --test scripts/reliability.test.mjs
node --test showcase/tests/showcase.test.mjs
node --test scripts/assert-pgvector-it-ran.test.mjs
node --test scripts/check-knowledge-freshness.test.mjs
node scripts/check-knowledge-freshness.mjs --max-age-days 45
./mvnw --batch-mode verify
node scripts/assert-pgvector-it-ran.mjs
\`\`\`

The last command proves that the Docker-backed \`CdqPgVectorIT\` actually ran; Maven \`BUILD SUCCESS\` alone is not sufficient when Docker is unavailable.

With a healthy local stack, run \`node scripts/smoke-chat.mjs\` for the Germany-to-Berlin-to-weather gate, \`node scripts/evaluate.mjs\` for the six required scenarios, and \`node scripts/reliability.mjs\` for twelve canonical and paraphrased cases across three repetitions. The reliability report records pass rates, latency, safe failure categories, and source-kind order in \`evaluation/reliability.md\`. Offline fixtures do not create live reports, and availability of dynamic upstream services is part of whole-system reliability.

**No fabricated live results:** this snapshot does not claim current weather or successful provider calls from an environment without Docker, the exact Ollama models, both API keys, and the pinned Weather MCP checkout.

## Reliability boundaries

| Risk | Control | Executable proof | Boundary |
| --- | --- | --- | --- |
| Unsourced factual answer | Required source kinds must complete before release. | [Evidence requirement test](assistant-app/src/test/java/com/cdq/assistant/chat/application/EvidenceRequirementPolicyTest.java) | Source execution does not prove every generated sentence. |
| One passing run hides instability | Twelve cases run across three repetitions. | [Repeated reliability evaluator](scripts/reliability.mjs) | Results include upstream availability. |
| Skipped pgvector integration | CI inspects the exact Failsafe report. | [pgvector proof script](scripts/assert-pgvector-it-ran.mjs) | Local proof needs Docker. |
| Stale RAG knowledge | Hash, provenance, age, review, and explicit activation gates. | [Knowledge freshness script](scripts/check-knowledge-freshness.mjs) | Refresh remains manual and source-specific. |

\`400\` responses cover invalid requests. \`503 Answer not verified\` withholds factual output when required evidence is missing. \`503 Dependency unavailable\` covers model, database, MCP, or upstream failures. \`504\` covers bounded model or complete-request timeouts. Provider bodies, prompts, tool inputs, and credentials are not exposed in public errors.

## Project structure

| Path | Responsibility |
| --- | --- |
| \`assistant-app/\` | Chat API/UI, model orchestration, RAG, evidence policy, and knowledge workflow |
| \`countries-mcp-server/\` | Custom Countries MCP server backed by REST Countries v5 |
| \`showcase/\` | Dependency-free static architecture walkthrough |
| \`knowledge/\` | CDQ Fraud Guard bootstrap snapshot and provenance metadata |
| \`scripts/\` | Setup, lifecycle, smoke, evaluation, reliability, and proof gates |
| \`.github/workflows/\` | CI and GitHub Pages publication |

## AI-assisted development

Codex supported repository analysis, test-first implementation, automated verification, diff review, and delivery documentation. The developer retained responsibility for architecture and final changes. No missing credentials, service results, or current weather values were invented.
