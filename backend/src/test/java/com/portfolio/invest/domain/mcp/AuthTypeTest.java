package com.portfolio.invest.domain.mcp;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuthTypeTest {
    @Test
    void 三种鉴权类型() {
        assertThat(AuthType.values())
                .containsExactly(AuthType.NONE, AuthType.BEARER, AuthType.HEADER);
    }
}
