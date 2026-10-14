package hu.auxin.ibkrfacade.web;

import hu.auxin.ibkrfacade.tws.TwsErrorCodes;
import hu.auxin.ibkrfacade.tws.TwsException;
import hu.auxin.ibkrfacade.tws.TwsNotConnectedException;
import hu.auxin.ibkrfacade.tws.TwsTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import java.util.stream.Collectors;

/**
 * Maps the exceptions to RFC 9457 problem details with an additional "code" property, eg.:
 * <pre>{"status": 422, "code": "MARKET_DATA_UNAVAILABLE", "detail": "...", "title": "Unprocessable Content"}</pre>
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handle(ApiException e) {
        return problem(e.getStatus(), e.getCode(), e.getMessage());
    }

    @ExceptionHandler(TwsNotConnectedException.class)
    ResponseEntity<ProblemDetail> handle(TwsNotConnectedException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "TWS_NOT_CONNECTED", e.getMessage());
    }

    @ExceptionHandler(TwsTimeoutException.class)
    ResponseEntity<ProblemDetail> handle(TwsTimeoutException e) {
        return problem(HttpStatus.GATEWAY_TIMEOUT, "TWS_TIMEOUT", e.getMessage());
    }

    @ExceptionHandler(TwsException.class)
    ResponseEntity<ProblemDetail> handle(TwsException e) {
        ResponseEntity<ProblemDetail> response = e.getCode() == TwsErrorCodes.NO_SECURITY_DEFINITION
                ? problem(HttpStatus.NOT_FOUND, "CONTRACT_NOT_FOUND", e.getMessage())
                : problem(HttpStatus.BAD_GATEWAY, "TWS_ERROR", e.getMessage());
        response.getBody().setProperty("tws_error_code", e.getCode());
        return response;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handle(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> snakeCase(error.getField()) + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> handleBadRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    /**
     * The request bodies use snake_case JSON properties
     */
    static String snakeCase(String field) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        if (status.is5xxServerError()) {
            log.warn("{} {}: {}", status.value(), code, detail);
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
