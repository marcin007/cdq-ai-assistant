# Recruiting task

## Task description

- Implement an AI Assistant with Java frameworks and provide a chat interface.
- Integrate the following knowledge sources:
  - Local vector database `pgvector/pgvector:pg17` populated with CDQ product information using RAG: convert the plain text from [CDQ Fraud Guard](https://www.cdq.com/products/cdq-fraud-guard) to vector embeddings.
  - Remote free REST service ([REST Countries](https://restcountries.com/)): write your own MCP server.
  - Local free MCP server ([semdin/mcp-weather](https://mcpservers.org/servers/semdin/mcp-weather)).
- Use the local `qwen3:4b` model with Ollama.
- Provide tests.
- Provide answers to the following questions:
  - What is the capital city of Germany?
  - What is the temperature currently in Munich?
  - What is the temperature of the capital of Germany currently?
  - What do you know about Berlin?
  - Your own questions to show off the solution.

## Out of scope

- No solution for long-term or short-term memory is required.

## Task requirements

- Provide the source code of the AI Assistant in a public repository of your choice.
- Run the AI Assistant and provide the answers.
- Provide a README that describes how to run the service and execute the tests.
- Using AI is explicitly allowed; explain how you used AI to fulfill the task.
- If you were not able to fulfill a task, explain why.
