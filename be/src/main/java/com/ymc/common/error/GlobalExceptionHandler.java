package com.ymc.common.error;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.ymc.chat.api.dto.ChatDuplicateMessageResponse;
import com.ymc.chat.service.DuplicateChatMessageException;

/**
 * 계약(openapi.yaml)의 `Error` 스키마로 응답을 통일한다 (design D8).
 *
 * <p>계약에 없는 실패(예: 큐 발행 실패)는 여기서 코드를 지어내지 않고 그대로 흘려보내
 * Spring 기본 5xx가 되게 한다 — 에러 코드 enum은 계약이 소유한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
        return ResponseEntity.status(e.code().status())
                .body(ErrorResponse.of(e.code(), e.getMessage()));
    }

    /** @Valid 위반(검증 실패) — 필수 필드 누락 등. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + defaultMessage(fe))
                .collect(Collectors.joining(", "));
        return badRequest(ErrorCode.VALIDATION_ERROR, message.isBlank() ? "잘못된 요청입니다." : message);
    }

    /** 본문 누락·malformed JSON — 필드 검증 이전 단계의 실패. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.debug("읽을 수 없는 요청 본문", e);
        return badRequest(ErrorCode.VALIDATION_ERROR, "요청 본문을 읽을 수 없습니다.");
    }

    /** 경로 변수 타입 불일치 — paperId가 UUID가 아닌 경우. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest(ErrorCode.VALIDATION_ERROR, e.getName() + " 값의 형식이 올바르지 않습니다.");
    }

    /** 같은 clientMessageId·같은 content 재전송 — 기존 실행 식별자·상태를 담아 409 (계약 ChatDuplicateMessageError). */
    @ExceptionHandler(DuplicateChatMessageException.class)
    public ResponseEntity<ChatDuplicateMessageResponse> handleDuplicateChatMessage(
            DuplicateChatMessageException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ChatDuplicateMessageResponse.of(
                        e.getMessage(), e.getSessionId(), e.getMessageId(), e.getStatus()));
    }

    private static String defaultMessage(FieldError fieldError) {
        return fieldError.getDefaultMessage() == null ? "잘못된 값입니다." : fieldError.getDefaultMessage();
    }

    private static ResponseEntity<ErrorResponse> badRequest(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, message));
    }
}
