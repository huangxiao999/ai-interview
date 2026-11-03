-- V10已经在独立隔离库实际应用；原恢复请求必须逐个保留，不能只保留最后一次回执。
CREATE TABLE plan_resume_request (
 owner_id UUID NOT NULL REFERENCES identity_account(id), request_id UUID NOT NULL,
 workspace_id UUID NOT NULL REFERENCES plan_workspace(id), input_digest TEXT NOT NULL,
 accepted_seq BIGINT NOT NULL, PRIMARY KEY(owner_id,request_id)
);
