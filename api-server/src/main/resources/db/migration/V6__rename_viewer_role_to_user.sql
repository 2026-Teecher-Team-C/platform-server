-- V6: 관리자 역할 VIEWER를 USER로 바꾼다 (2026-11 팀 회의 결정).
--
-- VIEWER는 "읽기만 하는 콘솔 사용자"로 읽혀서 헷갈렸다. 이 역할은 콘솔 계정이 없는 일반 직원(소명 토큰 사용자)이라
-- USER로 부른다. CHECK 제약이 옛 값만 허용하므로 제약을 먼저 지우고, 값을 바꾼 뒤, 새 값으로 제약을 다시 건다.
-- V1은 수정하지 않는다.
ALTER TABLE admin_users DROP CONSTRAINT ck_admin_users_role;

UPDATE admin_users SET role = 'USER' WHERE role = 'VIEWER';

ALTER TABLE admin_users
    ADD CONSTRAINT ck_admin_users_role CHECK (role IN ('ADMIN', 'ANALYST', 'USER')),
    ALTER COLUMN role SET DEFAULT 'USER';
