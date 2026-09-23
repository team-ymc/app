package com.ymc.paper.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI가 고정 형식으로 만드는 definition Markdown에서 영문·국문 한 줄씩 꺼낸다.
 * 전체 형식을 통째로 맞춰 형식이 바뀌면 조용히 틀린 값을 내지 않고 empty가 된다.
 */
public final class PrerequisiteDefinitionMarkdown {

    private static final Pattern FORMAT = Pattern.compile(
            "^### [^\\n]+\\n\\n\\*\\*Definition \\(정의\\)\\*\\*\\n\\n([^\\n]+?) {2}\\n([^\\n]+)$");

    private PrerequisiteDefinitionMarkdown() {
    }

    public static Optional<PrerequisiteDefinition> parse(String markdown) {
        if (markdown == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(markdown.strip().replace("\r\n", "\n"));
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new PrerequisiteDefinition(m.group(1).strip(), m.group(2).strip()));
    }
}
