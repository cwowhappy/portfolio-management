package com.portfolio.invest.domain.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuthTypeTest {
    @DisplayName("三种鉴权类型")
    @Test
    void givenAuthTypeEnum_whenCheckValues_thenExactlyThreeTypes() {
        assertThat(AuthType.values())
                .containsExactly(AuthType.NONE, AuthType.BEARER, AuthType.HEADER);
    }
}
