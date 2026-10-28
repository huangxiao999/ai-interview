-- Read-only post-DT-01 audit; run only against this project's development database.
SELECT json_build_object(
    'database', current_database(),
    'schema_owner', (SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'app'),
    'sql_migrations', (SELECT json_agg(version ORDER BY installed_rank)
        FROM app.flyway_schema_history WHERE type = 'SQL' AND success),
    'app_tables', (SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname = 'app'),
    'account_rows', (SELECT count(*) FROM app.identity_account),
    'authority_rows', (SELECT count(*) FROM app.identity_account_authority),
    'session_rows', (SELECT count(*) FROM app.spring_session),
    'session_attribute_rows', (SELECT count(*) FROM app.spring_session_attributes)
);
