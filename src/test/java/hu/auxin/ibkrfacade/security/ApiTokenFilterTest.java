package hu.auxin.ibkrfacade.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiTokenFilterTest {

    private static final String READ = "read-token-0123456789";
    private static final String WRITE = "write-token-0123456789";

    private final ApiTokenFilter filter = new ApiTokenFilter(true, READ, WRITE, true);

    @Test
    void readTokenCanReadButCannotTrade() throws Exception {
        assertThat(call(filter, "GET", "/positions", READ).getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/orders/12", READ).getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/price", READ).getStatus()).isEqualTo(200);

        MockHttpServletResponse order = call(filter, "POST", "/order", READ);
        assertThat(order.getStatus()).isEqualTo(403);
        assertThat(order.getContentAsString()).contains("INSUFFICIENT_SCOPE");
        assertThat(call(filter, "DELETE", "/orders/12", READ).getStatus()).isEqualTo(403);
        // unknown, encoded or unusual paths are treated as write operations
        assertThat(call(filter, "POST", "/%6Frder", READ).getStatus()).isEqualTo(403);
        assertThat(call(filter, "POST", "//order", READ).getStatus()).isEqualTo(403);
        assertThat(call(filter, "POST", "/order;x=1", READ).getStatus()).isEqualTo(403);
        assertThat(call(filter, "PUT", "/positions", READ).getStatus()).isEqualTo(403);
        assertThat(call(filter, "PATCH", "/orders/12", READ).getStatus()).isEqualTo(403);
    }

    @Test
    void writeTokenCanDoEverything() throws Exception {
        assertThat(call(filter, "POST", "/order", WRITE).getStatus()).isEqualTo(200);
        assertThat(call(filter, "DELETE", "/orders/12", WRITE).getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/account/summary", WRITE).getStatus()).isEqualTo(200);
    }

    @Test
    void missingOrInvalidTokenIsUnauthorized() throws Exception {
        MockHttpServletResponse missing = call(filter, "GET", "/positions", null);
        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(missing.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(missing.getContentAsString()).contains("UNAUTHORIZED");
        assertThat(call(filter, "GET", "/positions", "wrong-token-0123456789").getStatus()).isEqualTo(401);
        assertThat(call(filter, "POST", "/order", "wrong-token-0123456789").getStatus()).isEqualTo(401);

        MockHttpServletRequest basic = request("GET", "/positions");
        basic.addHeader("Authorization", "Basic " + WRITE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(basic, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void healthAndDocsArePublic() throws Exception {
        assertThat(call(filter, "GET", "/actuator/health", null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/actuator/health/liveness", null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/v3/api-docs", null).getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/actuator/metrics", null).getStatus()).isEqualTo(401);

        ApiTokenFilter privateDocs = new ApiTokenFilter(true, READ, WRITE, false);
        assertThat(call(privateDocs, "GET", "/v3/api-docs", null).getStatus()).isEqualTo(401);
    }

    @Test
    void writeOnlyConfiguration() throws Exception {
        ApiTokenFilter writeOnly = new ApiTokenFilter(true, "", WRITE, true);
        assertThat(call(writeOnly, "GET", "/positions", WRITE).getStatus()).isEqualTo(200);
        assertThat(call(writeOnly, "GET", "/positions", "").getStatus()).isEqualTo(401);
    }

    @Test
    void disabledFilterLetsEverythingThrough() throws Exception {
        ApiTokenFilter disabled = new ApiTokenFilter(false, "", "", true);
        assertThat(call(disabled, "POST", "/order", null).getStatus()).isEqualTo(200);
    }

    @Test
    void misconfigurationFailsAtStartup() {
        assertThatThrownBy(() -> new ApiTokenFilter(true, READ, "", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiTokenFilter(true, WRITE, WRITE, true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiTokenFilter(true, "", "short", true)).isInstanceOf(IllegalStateException.class);
    }

    private static MockHttpServletResponse call(ApiTokenFilter filter, String method, String path, String token)
            throws Exception {
        MockHttpServletRequest request = request(method, path);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/ibkr" + path);
        request.setContextPath("/ibkr");
        return request;
    }
}
