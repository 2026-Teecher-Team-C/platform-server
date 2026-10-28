package com.teecherteamc.platform.ruleset.interfaces;

import com.teecherteamc.platform.common.ApiException;
import com.teecherteamc.platform.common.ErrorCode;
import com.teecherteamc.platform.common.PageResponse;
import com.teecherteamc.platform.admin.interfaces.AdminPrincipal;
import com.teecherteamc.platform.common.ClientIp;
import com.teecherteamc.platform.ruleset.application.InvalidRulesetException;
import com.teecherteamc.platform.ruleset.application.ListRulesetsUseCase;
import com.teecherteamc.platform.ruleset.application.ListYaraRulesUseCase;
import com.teecherteamc.platform.ruleset.application.RulesetNotFoundException;
import com.teecherteamc.platform.ruleset.application.RulesetVersionConflictException;
import com.teecherteamc.platform.ruleset.application.UploadRulesetUseCase;
import com.teecherteamc.platform.ruleset.domain.EngineRulesetUnavailableException;
import com.teecherteamc.platform.ruleset.domain.Ruleset;
import com.teecherteamc.platform.ruleset.domain.YaraRule;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 룰셋 관리 REST 엔드포인트 (조회). 로그인(access 토큰)이 필요하며 SecurityConfig가 막는다.
 */
@RestController
public class RulesetController {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final ListRulesetsUseCase listRulesetsUseCase;
    private final ListYaraRulesUseCase listYaraRulesUseCase;
    private final UploadRulesetUseCase uploadRulesetUseCase;

    public RulesetController(ListRulesetsUseCase listRulesetsUseCase, ListYaraRulesUseCase listYaraRulesUseCase,
                             UploadRulesetUseCase uploadRulesetUseCase) {
        this.listRulesetsUseCase = listRulesetsUseCase;
        this.listYaraRulesUseCase = listYaraRulesUseCase;
        this.uploadRulesetUseCase = uploadRulesetUseCase;
    }

    /** .yar 파일을 올려 새 대기 버전을 만든다. 활성화는 따로 한다. */
    @PostMapping("/api/admin/rulesets")
    @ResponseStatus(HttpStatus.CREATED)
    public RulesetItem upload(@AuthenticationPrincipal AdminPrincipal principal,
                              @RequestParam("file") MultipartFile file, HttpServletRequest httpRequest) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Ruleset ruleset = uploadRulesetUseCase.upload(new UploadRulesetUseCase.Command(
                    file.getOriginalFilename(), content, principal.adminId(), ClientIp.resolve(httpRequest)));
            return RulesetItem.from(ruleset, 0);
        } catch (InvalidRulesetException e) {
            ErrorCode code = e.getKind() == InvalidRulesetException.Kind.BAD_INPUT
                    ? ErrorCode.INVALID_INPUT : ErrorCode.UNPROCESSABLE;
            throw new ApiException(code, e.getMessage());
        } catch (EngineRulesetUnavailableException e) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "탐지 엔진에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        } catch (RulesetVersionConflictException e) {
            throw new ApiException(ErrorCode.CONFLICT, e.getMessage());
        }
    }

    /** 룰셋 버전 목록. v0은 빼고 최신 버전부터 준다. */
    @GetMapping("/api/admin/rulesets")
    public PageResponse<RulesetItem> list(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        if (page < 0) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "page must be 0 or greater");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "size must be between 1 and " + MAX_SIZE);
        }
        ListRulesetsUseCase.Result result = listRulesetsUseCase.list(page, size);
        return PageResponse.from(result.page(),
                ruleset -> RulesetItem.from(ruleset, result.disabledCount(ruleset.getVersion())));
    }

    /** 한 버전의 룰 전부를 이름순으로 한 페이지에 준다. 없는 버전·v0은 404. */
    @GetMapping("/api/admin/rulesets/{version}/rules")
    public PageResponse<YaraRuleItem> rules(@PathVariable int version) {
        List<YaraRule> rules;
        try {
            rules = listYaraRulesUseCase.list(version);
        } catch (RulesetNotFoundException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, e.getMessage());
        }
        PageRequest pageRequest = PageRequest.of(0, Math.max(1, rules.size()));
        return PageResponse.from(new PageImpl<>(rules, pageRequest, rules.size()), YaraRuleItem::from);
    }
}
