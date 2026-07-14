package com.ymc.common.error;

/**
 * 계약에 정의된 에러 코드로 응답하겠다는 뜻의 예외. HTTP 상태는 {@link ErrorCode}가 안다.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
