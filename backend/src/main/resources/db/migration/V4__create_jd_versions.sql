-- JD 与简历共享删除根，但各自维护不可变版本序列与逻辑操作身份。
CREATE TABLE material_jd_version (
    id UUID PRIMARY KEY,
    resume_id UUID NOT NULL REFERENCES material_resume(id),
    version_no INTEGER NOT NULL CHECK (version_no > 0),
    operation_id UUID NOT NULL,
    body TEXT NOT NULL,
    codepoint_count INTEGER NOT NULL CHECK (codepoint_count BETWEEN 50 AND 10000),
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT material_jd_version_sequence UNIQUE (resume_id, version_no),
    CONSTRAINT material_jd_version_operation UNIQUE (resume_id, operation_id)
);

-- 扩展清理凭据类型；不修改已经应用的 V3，也不在凭据里保存正文。
ALTER TABLE material_cleanup_item DROP CONSTRAINT material_cleanup_item_type;
ALTER TABLE material_cleanup_item ADD CONSTRAINT material_cleanup_item_type
    CHECK (target_type IN ('RESUME_VERSION', 'JD_VERSION'));
