-- P4-DT-01：M-03真实计划首用。全部私有payload与资料根关联，删除时允许清空，墓碑保留去重身份。
CREATE TABLE material_plan_review (
 id UUID PRIMARY KEY, resume_id UUID NOT NULL REFERENCES material_resume(id),
 resume_version_id UUID NOT NULL, jd_version_id UUID NOT NULL,
 state TEXT NOT NULL DEFAULT 'SUBMITTED' CHECK(state IN ('SUBMITTED','CLEAR','REVIEW_REQUIRED','INVALID','OUT_OF_SCOPE','CONFIRMED')),
 finding_digest TEXT, findings JSONB, confirmation JSONB,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE plan_workspace (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES identity_account(id),
 resume_id UUID NOT NULL REFERENCES material_resume(id), create_operation_id UUID NOT NULL,
 operation_id UUID, initial_digest TEXT NOT NULL, input JSONB,
 input_revision INTEGER NOT NULL DEFAULT 1, state TEXT NOT NULL DEFAULT 'DRAFT', reason TEXT,
 generation BIGINT NOT NULL DEFAULT 1, initial_used INTEGER NOT NULL DEFAULT 0 CHECK(initial_used BETWEEN 0 AND 1),
 auto_used INTEGER NOT NULL DEFAULT 0 CHECK(auto_used BETWEEN 0 AND 1),
 manual_used INTEGER NOT NULL DEFAULT 0 CHECK(manual_used BETWEEN 0 AND 2),
 query_used INTEGER NOT NULL DEFAULT 0 CHECK(query_used BETWEEN 0 AND 3),
 manual_mode BOOLEAN NOT NULL DEFAULT false, queued_at TIMESTAMPTZ, deadline_at TIMESTAMPTZ,
 query_vector JSONB, evidence JSONB, prompt JSONB,
 resume_seq BIGINT NOT NULL DEFAULT 0, last_resume_id UUID, last_resume_digest TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 CHECK(initial_used+auto_used+manual_used<=4),
 CHECK(state IN ('DRAFT','QUEUED','PREPARING_EVIDENCE','RUNNING','CANDIDATE_READY','WAITING_INPUT',
 'BLOCKED_KNOWLEDGE','WAITING_MANUAL','PAUSED_LOCAL','SUCCEEDED','FAILED_FINAL','INVALIDATED')),
 UNIQUE(owner_id,create_operation_id), UNIQUE(owner_id,operation_id)
);
CREATE INDEX plan_workspace_resume ON plan_workspace(resume_id);
CREATE TABLE plan_input_revision (
 workspace_id UUID NOT NULL REFERENCES plan_workspace(id), revision INTEGER NOT NULL,
 input_digest TEXT NOT NULL, confirmation JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(workspace_id,revision)
);
CREATE TABLE plan_external_attempt (
 id UUID PRIMARY KEY, workspace_id UUID NOT NULL REFERENCES plan_workspace(id),
 kind TEXT NOT NULL CHECK(kind IN ('QUERY','GENERATE')), ordinal INTEGER NOT NULL,
 category TEXT NOT NULL CHECK(category IN ('INITIAL','AUTO','MANUAL')), generation BIGINT NOT NULL,
 input_revision INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN ('DISPATCH_INTENT','RESPONSE_STORED','FAILED','EXTERNAL_UNKNOWN','FENCED')),
 deadline_at TIMESTAMPTZ NOT NULL, request_id TEXT, input_tokens BIGINT CHECK(input_tokens>=0),
 output_tokens BIGINT CHECK(output_tokens>=0), cost_unknown BOOLEAN NOT NULL DEFAULT true,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(workspace_id,kind,ordinal)
);
CREATE TABLE plan_generation_candidate (
 id UUID PRIMARY KEY, workspace_id UUID NOT NULL REFERENCES plan_workspace(id),
 attempt_id UUID NOT NULL UNIQUE REFERENCES plan_external_attempt(id), generation BIGINT NOT NULL,
 input_revision INTEGER NOT NULL, payload JSONB, digest TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('STORED','REJECTED','ACCEPTED','PURGED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE interview_plan (
 id UUID PRIMARY KEY, revision_id UUID NOT NULL UNIQUE, workspace_id UUID NOT NULL UNIQUE REFERENCES plan_workspace(id),
 payload JSONB, eligibility TEXT NOT NULL CHECK(eligibility='ENGINEERING_ONLY'),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
-- 新清理类型必须与首个私有图同版本交付，不能依靠文件已清来推断计划JSON/向量已清。
ALTER TABLE material_cleanup_item DROP CONSTRAINT material_cleanup_item_target_type_check;
ALTER TABLE material_cleanup_item ADD CONSTRAINT material_cleanup_item_target_type_check
 CHECK(target_type IN ('RESUME_VERSION','JD_VERSION','PRIVATE_FILE','PLAN_PRIVATE_GRAPH'));
