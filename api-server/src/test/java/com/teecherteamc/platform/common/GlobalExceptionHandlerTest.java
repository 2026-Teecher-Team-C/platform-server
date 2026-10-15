package com.teecherteamc.platform.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어떤 예외가 나도 응답이 공통 ErrorResponse 형식으로 내려가는지 확인한다.
 * 앱 전체를 띄우지 않고, 테스트용 컨트롤러와 GlobalExceptionHandler만 연결해서 본다.
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void ApiException은_ErrorCode대로_변환된다() throws Exception {
        mvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("대상을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.path").value("/test/not-found"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void ApiException에_메시지를_넘기면_그_문장이_내려간다() throws Exception {
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("이미 등록된 해시입니다."));
    }

    @Test
    void 입력_검증_실패는_400과_필드별_사유를_준다() throws Exception {
        mvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("name은 필수입니다."));
    }

    @Test
    void 깨진_JSON은_400_INVALID_INPUT이다() throws Exception {
        mvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 허용되지_않는_메서드는_405다() throws Exception {
        mvc.perform(post("/test/not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 알_수_없는_예외는_500이고_내부_정보를_노출하지_않는다() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("secret-host"))));
    }

    @Test
    void 서버_오류_ApiException은_직접_넣은_메시지를_응답에_싣지_않는다() throws Exception {
        mvc.perform(get("/test/internal-with-message"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                // 던진 쪽이 쓴 문장이 응답 어디에도 없어야 한다
                .andExpect(content().string(not(containsString("DB 접속 정보 노출"))));
    }

    /** 테스트 전용 컨트롤러. 일부러 예외를 던진다. */
    @RestController
    @RequestMapping("/test")
    static class TestController {

        record Body(@NotBlank(message = "name은 필수입니다.") String name) {
        }

        @GetMapping("/not-found")
        void notFound() {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new ApiException(ErrorCode.CONFLICT, "이미 등록된 해시입니다.");
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody Body body) {
        }

        /** ApiException이 아닌 예상 못 한 예외. 메시지에 내부 정보가 섞여 있다. */
        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("db password=hunter2 host=secret-host");
        }

        /** 5xx ErrorCode에 직접 메시지를 넣어 던지는 경우. 이 문장은 응답에 나가면 안 된다. */
        @GetMapping("/internal-with-message")
        void internalWithMessage() {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "DB 접속 정보 노출");
        }
    }
}