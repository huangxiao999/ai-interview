-- 上传意图先于文件写入提交；同一登记贯穿暂存、解析、确认和清理。
CREATE TABLE material_upload (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL REFERENCES identity_account(id),
    operation_id uuid NOT NULL,
    resume_id uuid REFERENCES material_resume(id),
    target_resume_id uuid REFERENCES material_resume(id),
    original_name varchar(128) NOT NULL,
    file_type varchar(4) NOT NULL CHECK (file_type IN ('PDF', 'DOCX')),
    byte_size bigint NOT NULL CHECK (byte_size BETWEEN 1 AND 10000000),
    sha256 varchar(64) NOT NULL,
    state varchar(20) NOT NULL CHECK (state IN
        ('RECEIVING','QUEUED','PARSING','READY','FAILED','CONFIRMED','DELETE_PENDING','CLEANED')),
    generation bigint NOT NULL DEFAULT 0,
    parsed_body text,
    error_code varchar(40),
    file_cleaned boolean NOT NULL DEFAULT false,
    deadline_at timestamptz,
    version_id uuid UNIQUE,
    confirm_operation_id uuid,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(owner_account_id, operation_id),
    CHECK (state <> 'CONFIRMED' OR version_id IS NOT NULL)
);
CREATE INDEX material_upload_work ON material_upload(state, updated_at);
CREATE INDEX material_upload_root ON material_upload(resume_id);
-- 文件清理期间删除回执必须保持 PENDING；正文仍在屏障事务中清除。
ALTER TABLE material_cleanup_item DROP CONSTRAINT material_cleanup_item_type;
ALTER TABLE material_cleanup_item ADD CHECK (target_type IN ('RESUME_VERSION','JD_VERSION','PRIVATE_FILE'));
ALTER TABLE material_cleanup_item DROP CONSTRAINT material_cleanup_item_state;
ALTER TABLE material_cleanup_item ADD CHECK (state IN ('PENDING','CLEANED'));
