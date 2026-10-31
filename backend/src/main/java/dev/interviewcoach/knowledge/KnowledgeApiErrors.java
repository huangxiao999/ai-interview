package dev.interviewcoach.knowledge;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = KnowledgeApi.class)
final class KnowledgeApiErrors {
    @ExceptionHandler(InvalidKnowledge.class)
    ResponseEntity<ErrorBody> invalid(InvalidKnowledge ex) { return error(400, "INVALID_INPUT", ex.getMessage()); }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> malformed() { return error(400, "INVALID_INPUT", "知识请求格式不正确"); }
    @ExceptionHandler(KnowledgeConflict.class)
    ResponseEntity<ErrorBody> conflict() { return error(409, "OPERATION_CONFLICT", "操作身份或版本摘要与原请求不符"); }
    @ExceptionHandler(KnowledgeStateConflict.class)
    ResponseEntity<ErrorBody> state() { return error(409, "KNOWLEDGE_STATE_CONFLICT", "该版本已有审核决定，请读取原事实"); }
    @ExceptionHandler(KnowledgeUnavailable.class)
    ResponseEntity<ErrorBody> missing() { return error(404, "RESOURCE_UNAVAILABLE", "知识版本或原操作不可访问"); }
    @ExceptionHandler(KnowledgeAccountChanged.class)
    ResponseEntity<ErrorBody> changed() { return error(409, "ACCOUNT_CHANGED", "身份已变化，请核实后操作"); }
    @ExceptionHandler(CredentialsExpiredException.class)
    ResponseEntity<ErrorBody> credentials() { return error(401, "AUTHENTICATION_REQUIRED", "登录已失效"); }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ErrorBody> database() { return error(503, "RESULT_UNKNOWN", "暂时无法核实知识操作，请查询原操作"); }
    private static ResponseEntity<ErrorBody> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorBody(code, message));
    }
    record ErrorBody(String code, String message) { }
}
