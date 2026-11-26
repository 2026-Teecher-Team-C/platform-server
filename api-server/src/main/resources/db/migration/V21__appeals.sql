-- 직원 소명. 차단된 다운로드 이벤트 1건당 소명 1건.
-- event_id에 download_events FK를 걸지 않는다. ReportEvent는 차단 응답 뒤에 오고 실패할 수도 있어서
-- 소명 시점에 이벤트 행이 없을 수 있다. 토큰에 sha256과 agent_id가 들어 있으므로 이벤트 행 없이도 소명은 성립한다.
CREATE TABLE appeals (
    appeal_id     UUID         NOT NULL,
    event_id      UUID         NOT NULL,
    sha256        VARCHAR(64)  NOT NULL,
    agent_id      UUID         NOT NULL,
    reason        TEXT         NOT NULL,      -- 직원이 쓴 사유, 1~1000자
    status        VARCHAR(16)  NOT NULL,      -- PENDING / APPROVED / REJECTED
    decided_by    UUID,                       -- 다음 작업에서 채움
    decision_note TEXT,                       -- 다음 작업에서 채움 (거절 사유 등)
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at    TIMESTAMPTZ,
    CONSTRAINT pk_appeals PRIMARY KEY (appeal_id),
    CONSTRAINT uq_appeals_event UNIQUE (event_id),   -- 이벤트 1건당 소명 1건
    CONSTRAINT fk_appeals_agent FOREIGN KEY (agent_id) REFERENCES agents (agent_id),
    CONSTRAINT fk_appeals_decided_by FOREIGN KEY (decided_by) REFERENCES admin_users (admin_id),
    CONSTRAINT ck_appeals_status CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT ck_appeals_decided CHECK ((status = 'PENDING') = (decided_at IS NULL))
);
CREATE INDEX idx_appeals_status ON appeals (status, created_at DESC);
