-- P3-DT-02：仅公共知识构建。候选和完整接收分开；无发布集合或检索指针。
CREATE TABLE knowledge_index_build (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES knowledge_document(id),
    version_id uuid NOT NULL REFERENCES knowledge_version(id),
    owner_id uuid NOT NULL REFERENCES identity_account(id),
    version_digest varchar(64) NOT NULL,
    config_digest varchar(64) NOT NULL,
    config_json jsonb NOT NULL,
    policy varchar(80) NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('QUEUED','RUNNING','CANDIDATE_READY','WAITING_MANUAL','SUCCEEDED','FAILED_FINAL','INVALIDATED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    error_category varchar(48),
    UNIQUE(version_id, config_digest, policy)
);
CREATE TABLE knowledge_index_batch (
    id uuid PRIMARY KEY,
    build_id uuid NOT NULL REFERENCES knowledge_index_build(id),
    ordinal integer NOT NULL,
    input_json jsonb NOT NULL,
    input_digest varchar(64) NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('QUEUED','RUNNING','CANDIDATE_READY','WAITING_MANUAL','ACCEPTED','FAILED_FINAL','INVALIDATED')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 3),
    generation bigint NOT NULL DEFAULT 0,
    queued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    current_attempt uuid,
    UNIQUE(build_id, ordinal)
);
CREATE TABLE knowledge_index_attempt (
    id uuid PRIMARY KEY,
    batch_id uuid NOT NULL REFERENCES knowledge_index_batch(id),
    attempt_no integer NOT NULL CHECK(attempt_no BETWEEN 1 AND 3),
    generation bigint NOT NULL,
    executor_id uuid NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('DISPATCH_INTENT','RESPONSE_STORED','ACCEPTED','FAILED','EXTERNAL_UNKNOWN','FENCED')),
    started_at timestamptz NOT NULL,
    deadline timestamptz NOT NULL,
    provider_request_id varchar(200),
    usage_tokens bigint,
    usage_unknown boolean NOT NULL DEFAULT true,
    error_category varchar(48),
    finished_at timestamptz,
    UNIQUE(batch_id, attempt_no),
    CHECK(deadline > started_at)
);
-- 结构已核实的原批次候选，可在接收失败或重启后重新接收，不能再次付费猜结果。
CREATE TABLE knowledge_index_candidate (
    attempt_id uuid PRIMARY KEY REFERENCES knowledge_index_attempt(id),
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- 此处为构建中间结果；P3-DT-03才建立pgvector检索表示及发布集合。
CREATE TABLE knowledge_index_vector (
    batch_id uuid NOT NULL REFERENCES knowledge_index_batch(id),
    chunk_id uuid NOT NULL,
    ordinal integer NOT NULL,
    start_offset integer NOT NULL,
    end_offset integer NOT NULL CHECK(end_offset > start_offset),
    body text NOT NULL,
    digest varchar(64) NOT NULL,
    embedding double precision[] NOT NULL CHECK(cardinality(embedding)=1024),
    PRIMARY KEY(batch_id, chunk_id),
    UNIQUE(batch_id, ordinal)
);
CREATE TABLE knowledge_index_request (
    actor_id uuid NOT NULL REFERENCES identity_account(id),
    operation_id uuid NOT NULL,
    build_id uuid NOT NULL REFERENCES knowledge_index_build(id),
    kind varchar(8) NOT NULL CHECK(kind IN ('CREATE','RESUME')),
    input_digest varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(actor_id, operation_id)
);
CREATE INDEX knowledge_index_pending ON knowledge_index_batch(state, queued_at);
