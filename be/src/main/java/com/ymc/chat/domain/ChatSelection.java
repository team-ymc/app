package com.ymc.chat.domain;

/** 논문 본문 선택 영역의 블록 앵커. chat_message.selection에 JSON으로 저장된다. */
public record ChatSelection(Anchor start, Anchor end) {

    /** offset은 text 블록 내 UTF-16 code unit — 현재 FE는 보내지 않아 null이다. */
    public record Anchor(String blockId, Integer offset) {
    }
}
