-- P3-DT-01：公共知识独立于用户资料；已提交版本不可变，审核事实只追加一次。
CREATE TABLE knowledge_document (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE knowledge_version (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES knowledge_document(id),
    version_no integer NOT NULL CHECK (version_no > 0),
    submitted_by uuid NOT NULL REFERENCES identity_account(id),
    operation_id uuid NOT NULL,
    input_document_id uuid,
    title text NOT NULL,
    body text NOT NULL,
    source_url text NOT NULL,
    source_anchor text NOT NULL,
    applicability text NOT NULL,
    usage_note text NOT NULL,
    verification_note text NOT NULL,
    verified_on date NOT NULL,
    digest varchar(64) NOT NULL,
    submitted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(document_id, version_no),
    UNIQUE(submitted_by, operation_id)
);
CREATE TABLE knowledge_review (
    version_id uuid PRIMARY KEY REFERENCES knowledge_version(id),
    reviewer_id uuid NOT NULL REFERENCES identity_account(id),
    operation_id uuid NOT NULL,
    decision varchar(8) NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
    reason text NOT NULL,
    reviewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(reviewer_id, operation_id)
);
CREATE INDEX knowledge_version_document ON knowledge_version(document_id, version_no DESC);
