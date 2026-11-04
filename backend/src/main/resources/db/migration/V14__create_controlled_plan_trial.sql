-- 单次已授权计划试验；复用索引(D=0)，不开放普通运行，也不改变旧计划的资格。
CREATE TABLE plan_controlled_window (
 authorization_id TEXT PRIMARY KEY CHECK(authorization_id='p4-20261104-single-window-v1'),
 owner_id UUID NOT NULL REFERENCES identity_account(id),
 workspace_id UUID NOT NULL UNIQUE REFERENCES plan_workspace(id),
 manifest_digest TEXT NOT NULL,
 input_digest TEXT,
 starts_at TIMESTAMPTZ NOT NULL, ends_at TIMESTAMPTZ NOT NULL CHECK(ends_at>starts_at),
 state TEXT NOT NULL CHECK(state IN ('ACTIVE','WAITING_BILL','STOPPED')),
 reason TEXT,
 query_used INTEGER NOT NULL DEFAULT 0 CHECK(query_used BETWEEN 0 AND 1),
 generation_used INTEGER NOT NULL DEFAULT 0 CHECK(generation_used BETWEEN 0 AND 1),
 reserved_cny NUMERIC(16,8) NOT NULL DEFAULT 0 CHECK(reserved_cny BETWEEN 0 AND 5),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE plan_controlled_request (
 authorization_id TEXT NOT NULL REFERENCES plan_controlled_window(authorization_id),
 kind TEXT NOT NULL CHECK(kind IN ('QUERY','GENERATE')),
 wire_digest TEXT,
 input_tokens BIGINT CHECK(input_tokens>0), output_limit INTEGER NOT NULL,
 tokenizer_evidence TEXT,
 state TEXT NOT NULL CHECK(state IN ('PREPARED','APPROVED','INTENT','RECEIPTED','FAILED')),
 attempt_id UUID UNIQUE REFERENCES plan_external_attempt(id), send_claimed BOOLEAN NOT NULL DEFAULT false,
 request_id TEXT, reported_input BIGINT, reported_output BIGINT,
 reserved_cny NUMERIC(16,8), billed_cny NUMERIC(16,8), billing_reference TEXT,
 PRIMARY KEY(authorization_id,kind)
);
ALTER TABLE interview_plan DROP CONSTRAINT interview_plan_eligibility_check;
ALTER TABLE interview_plan ADD CONSTRAINT interview_plan_eligibility_check
 CHECK(eligibility IN ('ENGINEERING_ONLY','CONTROLLED_TRIAL'));
