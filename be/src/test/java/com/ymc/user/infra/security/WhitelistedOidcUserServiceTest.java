package com.ymc.user.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

import com.ymc.common.config.AuthProperties;
import com.ymc.user.service.LoginWhitelist;

class WhitelistedOidcUserServiceTest {

    private static OidcUser userWithEmail(String email) {
        OidcIdToken idToken = new OidcIdToken(
                "token-value", Instant.now(), Instant.now().plusSeconds(60),
                Map.of(IdTokenClaimNames.SUB, "sub-1", "email", email));
        // authorities는 null 대신 비어있지 않은 목록으로 준다
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);
    }

    private static WhitelistedOidcUserService serviceWith(Set<String> whitelist, OidcUser loaded) {
        LoginWhitelist gate = new LoginWhitelist(new AuthProperties(
                "test-secret-key-that-is-32-bytes-long!!",
                Duration.ofMinutes(30), Duration.ofDays(14), "http://localhost:5173", false,
                whitelist));
        return new WhitelistedOidcUserService(request -> loaded, gate);
    }

    @Test
    void 허용_계정이면_delegate_결과를_그대로_돌려준다() {
        OidcUser loaded = userWithEmail("a@x.com");
        WhitelistedOidcUserService service = serviceWith(Set.of("a@x.com"), loaded);

        assertThat(service.loadUser(null)).isSameAs(loaded);
    }

    @Test
    void 명단이_비면_그대로_통과시킨다() {
        OidcUser loaded = userWithEmail("아무나@x.com");
        WhitelistedOidcUserService service = serviceWith(Set.of(), loaded);

        assertThat(service.loadUser(null)).isSameAs(loaded);
    }

    @Test
    void 비허용_계정이면_not_allowed로_거부한다() {
        WhitelistedOidcUserService service =
                serviceWith(Set.of("a@x.com"), userWithEmail("남@x.com"));

        assertThatThrownBy(() -> service.loadUser(null))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(thrown -> assertThat(
                        ((OAuth2AuthenticationException) thrown).getError().getErrorCode())
                        .isEqualTo(WhitelistedOidcUserService.NOT_ALLOWED_CODE));
    }
}
