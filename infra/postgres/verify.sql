-- Read-only development verification. No application/session tables or external embeddings.
SELECT json_build_object(
    'postgresql_version', current_setting('server_version'),
    'postgresql_version_num', current_setting('server_version_num')::integer,
    'database', current_database(),
    'vector_version', (SELECT extversion FROM pg_extension WHERE extname = 'vector'),
    'identical_vector_distance', '[1,0,0]'::vector <-> '[1,0,0]'::vector,
    'orthogonal_vector_distance', '[1,0,0]'::vector <-> '[0,1,0]'::vector,
    'application_table_count', (
        SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE c.relkind IN ('r', 'p')
          AND n.nspname NOT IN ('pg_catalog', 'information_schema')
          AND n.nspname NOT LIKE 'pg_toast%'
    )
);
