-- 清理失败必须可追踪，按最久未尝试的目标轮转，避免固定批次长期阻塞其他资料。
ALTER TABLE material_upload ADD COLUMN cleanup_attempted_at timestamptz;
ALTER TABLE material_upload ADD COLUMN cleanup_error_code varchar(32);
CREATE INDEX material_upload_cleanup_order ON material_upload(cleanup_attempted_at, id)
    WHERE state IN ('DELETE_PENDING','FAILED') AND NOT file_cleaned;
