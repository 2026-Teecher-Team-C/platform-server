package com.teecherteamc.platform.common;

import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 대시보드 읽기 쿼리(dashboard 포트, 이벤트 목록) 전용 JdbcTemplate. 문장마다 타임아웃(기본 10초, 콘솔 8초보다
 * 약간 길게)을 건다. 넘으면 드라이버가 서버에 취소를 보내 쿼리가 DB에서도 멈추고, 예외는
 * GlobalExceptionHandler의 catch-all이 지금의 ErrorResponse(500 INTERNAL_ERROR)로 바꾼다.
 *
 * <p>JdbcTemplate을 빈으로 등록하지 않는다. 그러면 Spring Boot 기본 JdbcTemplate(판정 경로 등 나머지가 쓰는 것)이
 * 사라진다. 이 컴포넌트가 안에 따로 만들어 가진다.
 *
 * <p>왜: 클라이언트가 8초에 끊어도 서버 쿼리가 계속 돌아 부하가 쌓이고, 측정 다음 단계의 TRUNCATE와 교착이 났다
 * (2026-11-27, PC 1천 대 측정).
 */
@Component
public class DashboardReadJdbc {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    @Autowired
    public DashboardReadJdbc(DataSource dataSource,
                             @Value("${platform.dashboard.read-query-timeout:10s}") Duration timeout) {
        this.jdbc = new JdbcTemplate(dataSource);
        // JDBC 쿼리 타임아웃은 초 단위다. 1초 미만은 0(무제한)이 되지 않게 1초로 올린다.
        this.jdbc.setQueryTimeout((int) Math.max(1, timeout.toSeconds()));
        this.named = new NamedParameterJdbcTemplate(this.jdbc);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public NamedParameterJdbcTemplate named() {
        return named;
    }
}
