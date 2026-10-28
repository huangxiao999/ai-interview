CREATE TABLE identity_account (
    id UUID PRIMARY KEY,
    login_name TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    credential_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT identity_account_login_name_nonblank CHECK (login_name ~ '[^[:space:]]'),
    CONSTRAINT identity_account_password_hash_nonempty CHECK (length(password_hash) > 0),
    CONSTRAINT identity_account_credential_version_positive CHECK (credential_version > 0)
);

CREATE TABLE identity_account_authority (
    account_id UUID NOT NULL REFERENCES identity_account(id),
    authority TEXT NOT NULL,
    CONSTRAINT identity_account_authority_pk PRIMARY KEY (account_id, authority),
    CONSTRAINT identity_account_authority_nonblank CHECK (authority ~ '[^[:space:]]')
);
