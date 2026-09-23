package com.ymc.paper.service.port;

import java.util.Optional;
import java.util.UUID;

/** 사용자당 설명 생성 1개를 지키는 소유권. */
public interface PrerequisiteGenerationLock {

    /** @return 획득하면 해제용 토큰. 다른 생성이 진행 중이면 empty. */
    Optional<String> tryAcquire(UUID userId);

    /** 토큰이 같을 때만 지운다. 실패는 WARN만 남긴다. */
    void release(UUID userId, String token);

    class LockUnavailableException extends RuntimeException {
        public LockUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
