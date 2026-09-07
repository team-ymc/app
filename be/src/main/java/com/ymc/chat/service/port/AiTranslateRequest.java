package com.ymc.chat.service.port;

import com.ymc.chat.api.dto.ChatSelectionDto;

/** BE↔AI inline-translate 호출 입력. thread_id = translationId, paper_id = Document.requestPaperId 문자열. */
public record AiTranslateRequest(String threadId, String paperId, ChatSelectionDto selection) {
}
