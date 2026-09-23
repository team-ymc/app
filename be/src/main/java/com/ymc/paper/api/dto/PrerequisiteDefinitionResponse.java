package com.ymc.paper.api.dto;

import com.ymc.paper.service.PrerequisiteDefinitionService.PrerequisiteDefinitionView;

/** 계약 `PrerequisiteDefinitionResponse`. */
public record PrerequisiteDefinitionResponse(String term, String definitionEn, String definitionKo) {

    public static PrerequisiteDefinitionResponse from(PrerequisiteDefinitionView view) {
        return new PrerequisiteDefinitionResponse(view.term(), view.definitionEn(), view.definitionKo());
    }
}
