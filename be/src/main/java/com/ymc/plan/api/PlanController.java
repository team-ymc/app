package com.ymc.plan.api;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ymc.plan.api.dto.PlanUsageResponse;
import com.ymc.plan.service.PlanQueryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/me/plan")
@RequiredArgsConstructor
public class PlanController {

    private final PlanQueryService planQueryService;

    /** 계약 getMyPlan — 현재 플랜과 기능별 사용량. */
    @GetMapping
    public PlanUsageResponse myPlan(@AuthenticationPrincipal Jwt jwt) {
        return planQueryService.myPlan(UUID.fromString(jwt.getSubject()));
    }
}
