package com.teecherteamc.platform.policy.application;

import com.teecherteamc.platform.policy.domain.ActiveBypassHost;
import com.teecherteamc.platform.policy.domain.ListActiveBypassHostsPort;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 에이전트 GetPolicy에 내려줄 정책 조회. 지금은 활성 바이패스 호스트만 읽고, file_type_policies는
 * 후속 PR에서 이 유스케이스에 붙인다. 읽기 전용이라 감사 로그와 트랜잭션이 필요 없다.
 * 에이전트 인증은 AgentAuthServerInterceptor가 이미 했으므로 여기서 다시 확인하지 않는다.
 */
@Service
public class GetPolicyUseCase {

    private final ListActiveBypassHostsPort listActiveBypassHostsPort;

    public GetPolicyUseCase(ListActiveBypassHostsPort listActiveBypassHostsPort) {
        this.listActiveBypassHostsPort = listActiveBypassHostsPort;
    }

    public List<ActiveBypassHost> bypassHosts() {
        return listActiveBypassHostsPort.listAll();
    }
}
