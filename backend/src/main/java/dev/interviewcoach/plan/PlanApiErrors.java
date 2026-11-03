package dev.interviewcoach.plan;

import dev.interviewcoach.material.MaterialPlanFacts;
import dev.interviewcoach.knowledge.PlanEvidenceFacts;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes=PlanApi.class)
final class PlanApiErrors {
    record ErrorBody(String code,String message){ }
    @ExceptionHandler(PlanPolicy.Rejected.class)
    ResponseEntity<ErrorBody> rejected(PlanPolicy.Rejected ex){return error(status(ex.code),ex.code,ex.getMessage());}
    @ExceptionHandler(MaterialPlanFacts.Rejected.class)
    ResponseEntity<ErrorBody> material(MaterialPlanFacts.Rejected ex){return error(status(ex.code),ex.code,"资料版本、核对或访问资格不满足，请核实原操作");}
    @ExceptionHandler(PlanEvidenceFacts.Rejected.class)
    ResponseEntity<ErrorBody> evidence(PlanEvidenceFacts.Rejected ex){return error(409,ex.getMessage(),"固定知识集合尚未就绪或配置已变化");}
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    ResponseEntity<ErrorBody> database(){return error(503,"RESULT_UNKNOWN","数据库暂不能核实，请查询原操作，勿另建请求");}
    @ExceptionHandler(org.springframework.security.authentication.CredentialsExpiredException.class)
    ResponseEntity<ErrorBody> credentials(){return error(401,"AUTHENTICATION_REQUIRED","登录凭证已变化，请重新核实身份");}
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingRequestHeaderException.class,MissingServletRequestParameterException.class,MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> malformed(){return error(400,"INVALID_INPUT","计划请求格式不正确");}
    private static int status(String code){return switch(code){case "RESOURCE_UNAVAILABLE"->404;case "INVALID_INPUT"->400;case "PLAN_SERVICE_UNAVAILABLE"->503;default->409;};}
    private static ResponseEntity<ErrorBody> error(int status,String code,String message){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorBody(code,message));}
}
