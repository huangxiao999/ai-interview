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

@RestControllerAdvice(assignableTypes = {ResumeApi.class, UploadApi.class})
final class ResumeApiErrors {

    @ExceptionHandler(InvalidMaterial.class)
    ResponseEntity<ErrorBody> invalid(InvalidMaterial ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", ex.getMessage());
    }

    @ExceptionHandler(UploadNotReady.class)
    ResponseEntity<ErrorBody> uploadNotReady() {
        return error(HttpStatus.CONFLICT, "UPLOAD_NOT_READY", "文件尚未解析成功，请核实状态或改用粘贴正文");
    }

    @ExceptionHandler(java.io.IOException.class)
    ResponseEntity<ErrorBody> fileFailure() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "RESULT_UNKNOWN", "暂时无法核实文件操作，请查询原操作");
    }
    @ExceptionHandler(UploadFileUnavailable.class)
    ResponseEntity<ErrorBody> confirmationFileFailure() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "FILE_UNAVAILABLE", "原文件暂时不可读，尚未确认新版本；请核实状态或放弃上传后粘贴正文");
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

    @ExceptionHandler(MaterialAccountChanged.class)
    ResponseEntity<ErrorBody> accountChanged() {
        return error(HttpStatus.CONFLICT, "ACCOUNT_CHANGED", "登录身份已变化，请核实后操作");
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

// Multipart 在选择 Controller 前就可能失败，必须覆盖该阶段，避免错误转发被认证链改写为 403。
@RestControllerAdvice
class UploadLimitErrors {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ResumeApiErrors.ErrorBody> tooLarge() {
        return ResponseEntity.status(413).cacheControl(CacheControl.noStore())
                .body(new ResumeApiErrors.ErrorBody("FILE_TOO_LARGE", "文件不能超过 10,000,000 字节"));
    }
    @ExceptionHandler({org.springframework.web.multipart.MultipartException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<ResumeApiErrors.ErrorBody> malformedMultipart() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(new ResumeApiErrors.ErrorBody("INVALID_INPUT", "上传请求格式不正确"));
    }
}
