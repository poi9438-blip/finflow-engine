package com.finflow.config;

import com.finflow.common.ApiResponse;
import com.finflow.common.BusinessException;
import com.finflow.common.TraceIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

// 전역 예외 처리: 모든 Controller의 예외를 공통 오류 형식(traceId 포함)으로 변환
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 업무 오류: 지정한 상태 코드 + 공개 메시지
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.error(ex.getMessage(), TraceIds.current()));
    }

    // 입력값 검증 실패: "필드: 메시지"를 쉼표로 이어서
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message, TraceIds.current()));
    }

    // 요청 본문을 읽을 수 없음 (깨진 JSON, 타입이 다른 값)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Malformed request body", TraceIds.current()));
    }

    // 주소·쿼리 값의 타입이 맞지 않음 (예: 숫자 자리에 글자)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Invalid parameter: " + ex.getName(), TraceIds.current()));
    }

    // 동시 수정(버전 불일치) → 409
    @E (ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Application was updated by another request", TraceIds.current()));
    }

    // 메서드 보안(@PreAuthorize)에서 막힌 경우도 403
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("Access denied", TraceIds.current()));
    }

    // 그 외: Spring 표준 예외(404, 405 등)는 원래 상태 코드 유지, 나머지는 500
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode statusCode = errorResponse.getStatusCode();
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            String message = (status != null) ? status.getReasonPhrase() : "Error";
            return ResponseEntity.status(statusCode)
                    .body(ApiResponse.error(message, TraceIds.current()));
        }
        // 예상 못 한 오류: 원인은 서버 로그에만, 응답은 500 + 일반 메시지
        log.error("Unhandled exception: {}", ex.toString(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Internal server error", TraceIds.current()));
    }
}
