-- P2-DT-01：首个私有资料对象。根记录同时承担归属和不可逆删除屏障。
CREATE TABLE material_resume (
    id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL REFERENCES identity_account(id),
    create_operation_id UUID NOT NULL,
    state TEXT NOT NULL DEFAULT 'ACTIVE',
    generation BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT material_resume_state CHECK (state IN ('ACTIVE', 'DELETED')),
    CONSTRAINT material_resume_deleted_at CHECK ((state = 'ACTIVE' AND deleted_at IS NULL)
        OR (state = 'DELETED' AND deleted_at IS NOT NULL)),
    CONSTRAINT material_resume_generation CHECK (generation > 0),
    CONSTRAINT material_resume_create_operation UNIQUE (owner_account_id, create_operation_id)
);
CREATE INDEX material_resume_owner_active ON material_resume(owner_account_id, created_at DESC)
    WHERE state = 'ACTIVE';

-- 已确认正文只追加；删除时物理删除版本行，根作为不含正文的防复活凭据保留。
CREATE TABLE material_resume_version (
    id UUID PRIMARY KEY,
    resume_id UUID NOT NULL REFERENCES material_resume(id),
    version_no INTEGER NOT NULL,
    operation_id UUID NOT NULL,
    body TEXT NOT NULL,
    codepoint_count INTEGER NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT material_resume_version_number CHECK (version_no > 0),
    CONSTRAINT material_resume_version_count CHECK (codepoint_count BETWEEN 100 AND 20000),
    CONSTRAINT material_resume_version_sequence UNIQUE (resume_id, version_no),
    CONSTRAINT material_resume_version_operation UNIQUE (resume_id, operation_id)
);

-- 删除请求与每个已清理正文版本的最小审计事实不包含私有正文。
CREATE TABLE material_delete_request (
    id UUID PRIMARY KEY,
    resume_id UUID NOT NULL UNIQUE REFERENCES material_resume(id),
    requested_at TIMESTAMPTZ NOT NULL,
    cleanup_deadline_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL,
    cleaned_at TIMESTAMPTZ,
    CONSTRAINT material_delete_request_state CHECK (state IN ('PENDING', 'CLEANED')),
    CONSTRAINT material_delete_request_time CHECK (cleanup_deadline_at = requested_at + INTERVAL '24 hours'),
    CONSTRAINT material_delete_request_cleaned CHECK ((state = 'PENDING' AND cleaned_at IS NULL)
        OR (state = 'CLEANED' AND cleaned_at IS NOT NULL))
);

CREATE TABLE material_cleanup_item (
    id UUID PRIMARY KEY,
    delete_request_id UUID NOT NULL REFERENCES material_delete_request(id),
    target_type TEXT NOT NULL,
    target_id UUID NOT NULL,
    state TEXT NOT NULL,
    CONSTRAINT material_cleanup_item_type CHECK (target_type = 'RESUME_VERSION'),
    CONSTRAINT material_cleanup_item_state CHECK (state = 'CLEANED'),
    CONSTRAINT material_cleanup_item_unique UNIQUE (delete_request_id, target_type, target_id)
);
