package com.zurimate.appbackup.security;

import com.zurimate.appbackup.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFilterTest {

    private static MockHttpServletResponse call(String configuredKey, String path, String header) throws Exception {
        ApiKeyFilter filter = new ApiKeyFilter(new SecurityProperties(configuredKey, ""));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (header != null) request.addHeader(ApiKeyFilter.HEADER, header);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (chain.getRequest() != null) response.setStatus(299); // marks "passed through"
        return response;
    }

    @Test
    void acceptsTheConfiguredKey() throws Exception {
        assertThat(call("k3y", "/backups", "k3y").getStatus()).isEqualTo(299);
        assertThat(call("k3y", "/backups", " k3y ").getStatus()).isEqualTo(299);
    }

    @Test
    void rejectsMissingOrWrongKeys() throws Exception {
        MockHttpServletResponse missing = call("k3y", "/backups", null);
        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(missing.getContentAsString()).contains("Missing or invalid API key");
        assertThat(call("k3y", "/backup", "wrong").getStatus()).isEqualTo(401);
        assertThat(call("k3y", "/init", "k3").getStatus()).isEqualTo(401);
    }

    @Test
    void apiIsClosedWhenNoKeyIsConfigured() throws Exception {
        assertThat(call("", "/backups", "anything").getStatus()).isEqualTo(503);
        assertThat(call(null, "/backups", null).getStatus()).isEqualTo(503);
    }

    @Test
    void healthStaysOpenForProbes() throws Exception {
        assertThat(call("k3y", "/actuator/health", null).getStatus()).isEqualTo(299);
        assertThat(call("", "/actuator/health/liveness", null).getStatus()).isEqualTo(299);
        assertThat(call("k3y", "/actuator/env", null).getStatus()).isEqualTo(401);
    }
}
