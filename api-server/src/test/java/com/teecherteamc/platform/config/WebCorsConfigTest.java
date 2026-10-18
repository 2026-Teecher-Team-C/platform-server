package com.teecherteamc.platform.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CORS는 Security 필터 체인 안의 CorsFilter가 처리한다. 사전 요청(OPTIONS)은 인증보다 먼저 응답되고,
 * 인증이 필요한 경로라도 401 응답에 CORS 헤더가 붙어야 콘솔이 401을 읽고 로그인 화면으로 보낼 수 있다.
 */
@WebMvcTest(
        controllers = WebCorsConfigTest.ProbeController.class,
        properties = "platform.cors.allowed-origins=https://console.example.com,http://localhost:5173")
@Import({WebCorsConfigTest.ProbeController.class, SecurityConfig.class, WebCorsConfig.class})
class WebCorsConfigTest {

    private static final String ALLOWED_ORIGIN = "https://console.example.com";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 허용된_출처의_사전_요청에는_자격_증명_허용과_CORS_헤더를_붙인다() throws Exception {
        mockMvc.perform(options("/api/cors-probe")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void 콘솔의_JSON_쓰기_요청_사전_요청을_허용한다() throws Exception {
        mockMvc.perform(options("/api/cors-probe")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void 쿠키_인증이라_Authorization_헤더는_허용하지_않는다() throws Exception {
        mockMvc.perform(options("/api/cors-probe")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 허용되지_않은_요청_헤더는_거부한다() throws Exception {
        mockMvc.perform(options("/api/cors-probe")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Custom"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 로그인이_필요한_경로의_401_응답에도_CORS_헤더를_붙인다() throws Exception {
        mockMvc.perform(get("/api/cors-probe").header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void 허용되지_않은_출처는_거부한다() throws Exception {
        mockMvc.perform(options("/api/cors-probe")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void api_밖의_경로에는_CORS를_적용하지_않는다() throws Exception {
        mockMvc.perform(options("/cors-probe")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @RestController
    static class ProbeController {

        @GetMapping({"/api/cors-probe", "/cors-probe"})
        String probe() {
            return "ok";
        }
    }
}
