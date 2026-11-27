-- 측정 DB 정리: 다음 단계 시드 전에 이 DB의 다른 세션을 모두 끝내고 유휴 상태를 확인한다.
-- 왜: PC 1천 대 폴링이 8초에 끊은 쿼리가 서버에서 계속 돌다가 3천 대 단계의 TRUNCATE와 교착이 났다(2026-11-27).
--
-- psql -v expect_db=<측정 DB 이름> -f quiesce.sql
--   * current_database()가 expect_db와 다르면 아무것도 끝내지 않고 실패한다(seed.sql과 같은 안전장치)
--   * 자기 자신과 다른 DB 세션, 백그라운드 프로세스(autovacuum 등)는 건드리지 않는다
--   * 끝낸 세션이 사라지고, 자기 외 active 세션이 0개가 될 때까지 기다린다(상한 quiesce_wait_s, 기본 60초)
\set ON_ERROR_STOP 1
\if :{?expect_db}
\else
    \echo '중단: expect_db 변수가 없다'
    SELECT 1/0 AS quiesce_abort;
\endif
\if :{?quiesce_wait_s}
\else
    \set quiesce_wait_s 60
\endif
SELECT set_config('quiesce.expect_db', :'expect_db', false),
       set_config('quiesce.wait_s', :'quiesce_wait_s', false) \gset

DO $$
DECLARE
    v_expect text := current_setting('quiesce.expect_db');
    v_wait_s int := current_setting('quiesce.wait_s')::int;
    v_killed int;
    v_left int;
    v_deadline timestamptz := clock_timestamp() + make_interval(secs => v_wait_s);
BEGIN
    IF current_database() <> v_expect THEN
        RAISE EXCEPTION '중단: 현재 DB(%)가 expect_db(%)와 다르다', current_database(), v_expect;
    END IF;

    SELECT count(*) FILTER (WHERE pg_terminate_backend(pid)) INTO v_killed
    FROM pg_stat_activity
    WHERE datname = current_database() AND pid <> pg_backend_pid() AND backend_type = 'client backend';
    RAISE NOTICE 'quiesce: 세션 %개 종료 요청', v_killed;

    -- 연결 풀(api-server)은 곧바로 다시 붙지만 쿼리를 돌리지 않으면 idle이다. active만 0이면 된다.
    -- 트랜잭션(DO 블록) 안에서는 pg_stat_activity 스냅샷이 고정되므로 매번 지워 최신 상태를 본다.
    LOOP
        PERFORM pg_stat_clear_snapshot();
        SELECT count(*) INTO v_left
        FROM pg_stat_activity
        WHERE datname = current_database() AND pid <> pg_backend_pid()
          AND backend_type = 'client backend' AND state <> 'idle';
        EXIT WHEN v_left = 0;
        IF clock_timestamp() > v_deadline THEN
            RAISE EXCEPTION 'quiesce: %초 안에 유휴가 되지 않았다(active 세션 %개)', v_wait_s, v_left;
        END IF;
        PERFORM pg_sleep(0.5);
    END LOOP;
    RAISE NOTICE 'quiesce: 유휴 확인(자기 외 active 세션 0개)';
END $$;
