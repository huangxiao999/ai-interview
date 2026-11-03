-- DT-03：公共集合不可变快照与版本停用独立于原审核事实，不修改V1～V8。
CREATE TABLE knowledge_version_stop (
    version_id uuid PRIMARY KEY REFERENCES knowledge_version(id),
    actor_id uuid NOT NULL REFERENCES identity_account(id),
    reason text NOT NULL,
    stopped_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE knowledge_collection (
    id uuid PRIMARY KEY,
    published_by uuid NOT NULL REFERENCES identity_account(id),
    config_digest varchar(64) NOT NULL,
    config_json jsonb NOT NULL,
    chunk_policy varchar(80) NOT NULL,
    retrieval_policy varchar(100) NOT NULL,
    manifest_digest varchar(64) NOT NULL,
    entry_count integer NOT NULL CHECK(entry_count BETWEEN 1 AND 500),
    published_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE knowledge_collection_entry (
    collection_id uuid NOT NULL REFERENCES knowledge_collection(id),
    chunk_id uuid NOT NULL,
    build_id uuid NOT NULL REFERENCES knowledge_index_build(id),
    version_id uuid NOT NULL REFERENCES knowledge_version(id),
    document_id uuid NOT NULL REFERENCES knowledge_document(id),
    version_digest varchar(64) NOT NULL,
    reviewed_at timestamptz NOT NULL,
    source_url text NOT NULL,
    source_anchor text NOT NULL,
    applicability text NOT NULL,
    technology text NOT NULL,
    technology_version text NOT NULL,
    scenario text NOT NULL,
    claim_key text NOT NULL,
    claim_value text NOT NULL,
    quote text NOT NULL,
    verification_note text NOT NULL,
    start_offset integer NOT NULL,
    end_offset integer NOT NULL CHECK(end_offset > start_offset),
    body text NOT NULL,
    digest varchar(64) NOT NULL,
    embedding vector(1024) NOT NULL,
    PRIMARY KEY(collection_id,chunk_id)
);
-- 全局指针只在账号→排序知识根之后加锁；与完整清单及回执共同提交。
CREATE TABLE knowledge_collection_current (
    singleton integer PRIMARY KEY CHECK(singleton=1),
    collection_id uuid REFERENCES knowledge_collection(id)
);
INSERT INTO knowledge_collection_current(singleton) VALUES(1);
CREATE TABLE knowledge_collection_request (
    actor_id uuid NOT NULL REFERENCES identity_account(id),
    operation_id uuid NOT NULL,
    kind varchar(8) NOT NULL CHECK(kind IN ('PUBLISH','STOP')),
    input_digest varchar(64) NOT NULL,
    resource_id uuid NOT NULL,
    accepted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(actor_id,operation_id)
);
CREATE INDEX knowledge_collection_conditions ON knowledge_collection_entry(collection_id,technology,technology_version,scenario,claim_key);
