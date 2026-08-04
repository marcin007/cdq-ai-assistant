CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE vector_store (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    content TEXT NOT NULL,
    metadata JSON NOT NULL DEFAULT '{}'::json,
    embedding VECTOR(1024) NOT NULL
);

CREATE INDEX vector_store_embedding_hnsw_cosine_idx
    ON vector_store USING hnsw (embedding vector_cosine_ops);
