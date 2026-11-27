package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teecherteamc.platform.common.DashboardReadJdbc;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 대시보드 읽기 쿼리 문장 타임아웃: 넘으면 예외가 나고 서버 쪽 쿼리도 취소된다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DashboardReadJdbcTimeoutIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DashboardReadJdbc dashboardReadJdbc;

    @Test
    void 기본_타임아웃은_10초이고_기본_JdbcTemplate에는_걸지_않는다() {
        assertThat(dashboardReadJdbc.jdbc().getQueryTimeout()).isEqualTo(10);
        assertThat(jdbcTemplate.getQueryTimeout()).isNotEqualTo(10);
    }

    @Test
    void 타임아웃을_넘으면_쿼리가_서버에서_취소된다() {
        DashboardReadJdbc readJdbc = new DashboardReadJdbc(dataSource, Duration.ofSeconds(1));

        long t0 = System.nanoTime();
        assertThatThrownBy(() -> readJdbc.jdbc().queryForObject(
                "SELECT pg_sleep(30) IS NULL /* dashboard-timeout-probe */", Boolean.class))
                .isInstanceOf(DataAccessException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(10));

        Integer running = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM pg_stat_activity
                WHERE state = 'active' AND query LIKE '%dashboard-timeout-probe%' AND pid <> pg_backend_pid()
                """, Integer.class);
        assertThat(running).isZero();
    }
}
