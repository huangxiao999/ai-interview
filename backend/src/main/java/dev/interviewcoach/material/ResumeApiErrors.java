package dev.interviewcoach.material;

import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ResumeApi.class)
final class ResumeApiErrors {

    @ExceptionHandler(InvalidMaterial.class)
    ResponseEntity<ErrorBody> invalid(InvalidMaterial ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", ex.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> malformedRequest() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "资料请求格式不正确");
    }

    @ExceptionHandler(MaterialConflict.class)
    ResponseEntity<ErrorBody> conflict() {
        return error(HttpStatus.CONFLICT, "OPERATION_CONFLICT", "操作标识已用于另一份正文");
    }

    @ExceptionHandler(MaterialUnavailable.class)
    ResponseEntity<ErrorBody> missing() {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_UNAVAILABLE", "资料不存在或不可访问");
    }

    @ExceptionHandler(CredentialsExpiredException.class)
    ResponseEntity<ErrorBody> revokedSession() {
        return error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "登录已失效，请重新登录");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ErrorBody> databaseUnavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "RESULT_UNKNOWN", "暂时无法核实资料操作结果");
    }

    private ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ErrorBody(code, message));
    }

    record ErrorBody(String code, String message) {
    }
}
