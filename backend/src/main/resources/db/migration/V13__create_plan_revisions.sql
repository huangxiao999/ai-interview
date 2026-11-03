-- M-03不可变计划修订与保存/确认回执；正文仍属于原PLAN_PRIVATE_GRAPH删除目标。
CREATE TABLE plan_revision (
 id UUID PRIMARY KEY, plan_id UUID NOT NULL REFERENCES interview_plan(id),
 revision_no INTEGER NOT NULL CHECK(revision_no>0), parent_revision_id UUID,
 payload JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(plan_id,id), UNIQUE(plan_id,revision_no),
 FOREIGN KEY(plan_id,parent_revision_id) REFERENCES plan_revision(plan_id,id)
);
INSERT INTO plan_revision(id,plan_id,revision_no,payload,created_at)
 SELECT revision_id,id,1,payload,created_at FROM interview_plan;
ALTER TABLE interview_plan ADD COLUMN current_revision_id UUID;
UPDATE interview_plan SET current_revision_id=revision_id;
ALTER TABLE interview_plan ADD CONSTRAINT plan_current_revision_fk
 FOREIGN KEY(id,current_revision_id) REFERENCES plan_revision(plan_id,id);
CREATE TABLE plan_revision_confirmation (
 id UUID PRIMARY KEY, plan_id UUID NOT NULL, revision_id UUID NOT NULL,
 confirmed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(plan_id,revision_id), FOREIGN KEY(plan_id,revision_id) REFERENCES plan_revision(plan_id,id)
);
CREATE TABLE plan_revision_request (
 owner_id UUID NOT NULL REFERENCES identity_account(id), operation_id UUID NOT NULL,
 plan_id UUID NOT NULL, revision_id UUID NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('SAVE','CONFIRM')),
 input_digest TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(owner_id,operation_id), FOREIGN KEY(plan_id,revision_id) REFERENCES plan_revision(plan_id,id)
);
