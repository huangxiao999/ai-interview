-- 已应用V10/V11不改；每个私有图记录清理尝试与无正文错误，故障目标轮转不阻塞其他用户图。
ALTER TABLE plan_workspace ADD COLUMN cleanup_attempted_at TIMESTAMPTZ;
ALTER TABLE plan_workspace ADD COLUMN cleanup_error_code TEXT;
CREATE INDEX plan_cleanup_rotation ON plan_workspace(cleanup_attempted_at NULLS FIRST,id);
