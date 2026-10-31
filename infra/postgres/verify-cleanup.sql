-- 运维只读核查：不输出账号、正文、文件名、路径或文件摘要。
SELECT d.id, d.state, d.requested_at, d.cleanup_deadline_at, d.cleaned_at,
       (d.state = 'PENDING' AND d.cleanup_deadline_at <= clock_timestamp()) AS overdue,
       count(*) FILTER (WHERE i.state = 'PENDING') AS pending_targets
FROM app.material_delete_request d
LEFT JOIN app.material_cleanup_item i ON i.delete_request_id = d.id
GROUP BY d.id ORDER BY d.requested_at;

SELECT id, state, file_cleaned, cleanup_attempted_at, cleanup_error_code
FROM app.material_upload
WHERE state IN ('DELETE_PENDING','FAILED') AND NOT file_cleaned
ORDER BY cleanup_attempted_at NULLS FIRST, id;
