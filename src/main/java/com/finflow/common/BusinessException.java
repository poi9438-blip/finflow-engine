package com.finflow.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

// 사용자에게 보여 줘도 되는 업무 오류 (상태 코드와 공개 메시지를 함께 가짐)
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
