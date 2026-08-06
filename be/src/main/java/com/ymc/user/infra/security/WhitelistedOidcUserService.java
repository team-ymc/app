package com.ymc.user.infra.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import com.ymc.common.config.AuthProperties;
import com.ymc.user.service.LoginWhitelist;

/**
 * 명단에 없는 계정을 userinfo 단계에서 거부한다. 여기서 던지면 User 행도 토큰도 생기지 않는다.
 *
 * <p>스코프에 openid가 있어 OIDC 경로를 탄다 — oidcUserService로 등록해야 호출된다.
 */
@Component
public class WhitelistedOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    static final String NOT_ALLOWED_CODE = "not_allowed";

    private static final Logger log = LoggerFactory.getLogger(WhitelistedOidcUserService.class);

    private static final OAuth2Error NOT_ALLOWED =
            new OAuth2Error(NOT_ALLOWED_CODE, "허용되지 않은 계정입니다.", null);

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final LoginWhitelist whitelist;

    /** 생성자가 둘이라 명시하지 않으면 스프링이 못 고른다. */
    @Autowired
    public WhitelistedOidcUserService(LoginWhitelist whitelist, AuthProperties props) {
        this(new OidcUserService(), whitelist);
        // 배선을 빠뜨려도 로그인은 성공하므로 상태를 로그로 남긴다. 이메일은 남기지 않는다.
        int size = props.loginWhitelist().size();
        if (size > 0) {
            log.info("로그인 화이트리스트 활성: 허용 {}건", size);
        } else {
            log.info("로그인 화이트리스트 비활성 — 모든 계정 로그인 허용");
        }
    }

    WhitelistedOidcUserService(
            OAuth2UserService<OidcUserRequest, OidcUser> delegate, LoginWhitelist whitelist) {
        this.delegate = delegate;
        this.whitelist = whitelist;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser user = delegate.loadUser(request);
        if (!whitelist.isAllowed(user.getEmail())) {
            throw new OAuth2AuthenticationException(NOT_ALLOWED);
        }
        return user;
    }
}
