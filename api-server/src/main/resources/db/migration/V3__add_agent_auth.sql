-- 관리자가 발급하는 1회용 등록 토큰. agents와 분리하는 이유: 발급 시점에는 아직
-- device/agent 행이 존재하지 않는다(RegisterAgent가 성공해야 그제서야 생긴다).
CREATE TABLE agent_enrollment_tokens (
    enrollment_token_id   UUID         NOT NULL,
    enrollment_token_hash VARCHAR(255) NOT NULL,  -- 평문 토큰 저장 금지
    expires_at            TIMESTAMPTZ  NOT NULL,
    issued_by             UUID,                    -- nullable: 관리자 로그인이 아직 없다(알려진 임시 간극)
    redeemed_at           TIMESTAMPTZ,
    redeemed_by_agent_id  UUID,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 낙관적 락. redeem()이 읽기→수정→쓰기라 동시에 같은 토큰으로 두 번 RegisterAgent가 들어오면
    -- DB 제약만으로는 "1회용"을 못 지킨다 — 둘 다 "아직 안 쓰임"을 보고 통과해버린다. version으로
    -- 둘 중 늦게 커밋하는 쪽의 UPDATE가 0행에 맞아 떨어지게 해서 OptimisticLockingFailure로 걸러낸다.
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_agent_enrollment_tokens PRIMARY KEY (enrollment_token_id),
    CONSTRAINT uq_agent_enrollment_tokens_hash UNIQUE (enrollment_token_hash),
    CONSTRAINT fk_agent_enrollment_tokens_issued_by FOREIGN KEY (issued_by)
        REFERENCES admin_users (admin_id),
    CONSTRAINT fk_agent_enrollment_tokens_redeemed_by FOREIGN KEY (redeemed_by_agent_id)
        REFERENCES agents (agent_id),
    -- 상태를 별도 컬럼으로 안 두고 (redeemed_at, redeemed_by_agent_id) 쌍으로 표현한다 —
    -- 둘을 항상 같이 채우거나 같이 비워서 중간 상태가 생기지 않게 한다.
    CONSTRAINT ck_agent_enrollment_tokens_redemption
        CHECK ((redeemed_at IS NULL) = (redeemed_by_agent_id IS NULL))
);

-- RegisterAgent가 발급하고 Heartbeat 인터셉터가 검증할 운영용 agent_token.
-- NULL을 허용한다 — "현재 유효한 토큰이 없는" 상태가 있을 수 있고, NULL은 인터셉터가
-- 자동으로 거부한다(fail-close).
ALTER TABLE agents
    ADD COLUMN agent_token_hash VARCHAR(255),
    ADD COLUMN agent_token_expires_at TIMESTAMPTZ;

ALTER TABLE agents
    ADD CONSTRAINT ck_agents_token_pair
        CHECK ((agent_token_hash IS NULL) = (agent_token_expires_at IS NULL));

-- Heartbeat 인터셉터가 매 호출마다 하는 조회 — 토큰이 있는 행만 대상이면 되므로 부분 인덱스.
CREATE INDEX idx_agents_token_hash ON agents (agent_token_hash)
    WHERE agent_token_hash IS NOT NULL;

COMMENT ON TABLE agent_enrollment_tokens IS '관리자 발급 1회용 등록 토큰 (redeem 전까지 device/agent 미생성)';
