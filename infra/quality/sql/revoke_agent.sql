-- 측정 기록(근거·평가·다운로드 이벤트)은 남기고, 측정 에이전트 토큰만 다시 못 쓰게 REVOKED로 바꾼다.
-- 하네스가 등록한 에이전트(agent_version = 'quality-harness')가 아니면 아무것도 바꾸지 않고 멈춘다.
BEGIN;
SELECT set_config('quality.agent_id', :'agent_id', true);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM agents WHERE agent_id = current_setting('quality.agent_id')::uuid
                                          AND agent_version = 'quality-harness') THEN
        RAISE EXCEPTION '측정 에이전트가 아닙니다: %', current_setting('quality.agent_id');
    END IF;
END $$;
UPDATE agents SET status = 'REVOKED', updated_at = now() WHERE agent_id = :'agent_id';
COMMIT;
