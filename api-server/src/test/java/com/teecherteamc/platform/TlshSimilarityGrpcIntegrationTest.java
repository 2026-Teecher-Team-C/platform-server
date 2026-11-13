package com.teecherteamc.platform;

import static org.assertj.core.api.Assertions.assertThat;
import com.google.protobuf.ByteString;
import com.teecherteamc.platform.proto.verdict.v1.*;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@AutoConfigureTestGrpcTransport
@ImportGrpcClients(types = {VerdictServiceGrpc.VerdictServiceStub.class, VerdictServiceGrpc.VerdictServiceBlockingStub.class})
@Import(TestcontainersConfiguration.class)
class TlshSimilarityGrpcIntegrationTest {
    @Autowired VerdictServiceGrpc.VerdictServiceStub stub;
    @Autowired VerdictServiceGrpc.VerdictServiceBlockingStub blocking;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.teecherteamc.platform.lookup.application.BloomFilterRebuildService bloom;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.teecherteamc.platform.verdict.application.SimilarityDecisions similarity;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    FakeQuarantineUploadPort quarantine;
    @Autowired com.teecherteamc.platform.verdict.application.ImportFileFingerprint importer;
    @Autowired com.teecherteamc.platform.verdict.domain.FileVerdictRepository verdicts;
    @Autowired com.teecherteamc.platform.quarantine.application.RestoreQuarantineFileUseCase restore;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.teecherteamc.platform.verdict.infrastructure.OfficialTlshCalculator calculator;

    VerdictServiceGrpc.VerdictServiceStub rawStub;
    VerdictServiceGrpc.VerdictServiceBlockingStub rawBlocking;
    UUID authenticatedAgent;

    @org.junit.jupiter.api.BeforeEach
    void seedAgent() {
        rawStub = stub;
        rawBlocking = blocking;
        var identity = VerdictTestClient.seed(jdbc);
        authenticatedAgent = identity.id();
        stub = VerdictTestClient.withToken(stub, identity.token());
        blocking = VerdictTestClient.withToken(blocking, identity.token());
    }

    @Test
    void padding_variant_blocks_after_clean_engine_and_preserves_original_analysis() throws Exception {
        byte[] original = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        byte[] padded = Arrays.copyOf(original, original.length + 64);
        Arrays.fill(padded, original.length, padded.length, (byte)' ');
        String baseline = TlshFingerprintGrpcIntegrationTest.sha(original);
        String sha = TlshFingerprintGrpcIntegrationTest.sha(padded);
        assertThat(sha).isNotEqualTo(baseline);
        upload(original, UUID.randomUUID());
        jdbc.update("INSERT INTO hash_blacklist(hash_value, reason) VALUES (?, 'harmless baseline fixture')", baseline);
        UUID event = UUID.randomUUID();
        var response = upload(padded, event);
        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);
        assertThat(response.getSourceValue()).isEqualTo(6);
        var evidence = jdbc.queryForMap("SELECT * FROM similarity_assessments WHERE assessment_id = (SELECT assessment_id FROM event_decision_evidence WHERE event_id = ?)", event);
        assertThat(evidence.get("distance")).isEqualTo(17);
        assertThat(evidence.get("matched_sample_sha256")).isEqualTo(baseline);
        assertThat(evidence.get("classification")).isEqualTo("BLOCK");
        assertThat(jdbc.queryForObject("SELECT verdict FROM analyses WHERE analysis_id = ?", String.class, evidence.get("analysis_id"))).isEqualTo("CLEAN");
        assertThat(jdbc.queryForObject("SELECT verdict_source FROM file_verdicts WHERE sha256 = ?", String.class, sha)).isEqualTo("SIMILARITY");
        blocking.reportEvent(ReportEventRequest.newBuilder().setEventId(event.toString()).setSha256(sha)
                .setUrl("https://fixture.invalid/padded").setRequestHost("fixture.invalid")
                .setDecision(FinalDecision.FINAL_DECISION_BLOCKED).setDecisionSourceValue(6)
                .setHeldAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(java.time.Instant.now().getEpochSecond())).build());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quarantine_files WHERE event_id=?", Integer.class, event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT similarity_assessment_id FROM download_events WHERE event_id=?", UUID.class, event)).isEqualTo(evidence.get("assessment_id"));
        assertThat(jdbc.queryForMap("SELECT severity, severity_rank, reason_type FROM download_event_threats WHERE event_id=?", event))
                .containsEntry("severity", "MEDIUM").containsEntry("severity_rank", 2).containsEntry("reason_type", "SIMILARITY");
    }

    @org.junit.jupiter.api.AfterEach
    void removeBaseline() {
        jdbc.update("DELETE FROM hash_blacklist WHERE reason = 'harmless baseline fixture'");
    }

    @Test
    void corpus_changes_invalidate_both_lookup_and_direct_upload_without_losing_cached_provenance() throws Exception {
        byte[] original = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        byte[] padded = Arrays.copyOf(original, original.length + 64);
        Arrays.fill(padded, original.length, padded.length, (byte)' ');
        String baseline = TlshFingerprintGrpcIntegrationTest.sha(original);
        String sha = TlshFingerprintGrpcIntegrationTest.sha(padded);
        upload(original, UUID.randomUUID());
        upload(padded, UUID.randomUUID());
        jdbc.update("INSERT INTO hash_blacklist(hash_value, reason) VALUES (?, 'harmless baseline fixture')", baseline);
        assertThat(upload(padded, UUID.randomUUID()).getSourceValue()).isEqualTo(6);
        assertThat(upload(padded, UUID.randomUUID()).getSourceValue()).isEqualTo(6);
        bloom.rebuild();
        assertThat(blocking.checkHash(CheckHashRequest.newBuilder().setSha256(sha).build()).getSourceValue()).isEqualTo(6);
        jdbc.update("UPDATE hash_blacklist SET is_active = false WHERE hash_value = ?", baseline);
        assertThat(blocking.checkHash(CheckHashRequest.newBuilder().setSha256(sha).build()).getDecision()).isEqualTo(Decision.DECISION_UNKNOWN);
        assertThat(upload(padded, UUID.randomUUID()).getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        jdbc.update("UPDATE hash_blacklist SET is_active = true WHERE hash_value = ?", baseline);
        assertThat(upload(padded, UUID.randomUUID()).getSourceValue()).isEqualTo(6);
    }

    @Test
    void corpus_only_change_reuses_clean_engine_analysis() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        String sha = TlshFingerprintGrpcIntegrationTest.sha(bytes);
        upload(bytes, UUID.randomUUID());
        Integer before = jdbc.queryForObject("SELECT count(*) FROM analyses WHERE sha256=?", Integer.class, sha);
        Integer analysisCount = jdbc.queryForObject("SELECT analysis_count FROM file_verdicts WHERE sha256=?", Integer.class, sha);
        baseline(FingerprintImportRestIntegrationTest.TLSH);
        assertThat(upload(bytes, UUID.randomUUID()).getSourceValue()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analyses WHERE sha256=?", Integer.class, sha)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT analysis_count FROM file_verdicts WHERE sha256=?", Integer.class, sha)).isEqualTo(analysisCount);
    }

    @Test
    void direct_upload_exact_whitelist_wins_even_over_existing_similarity_block() throws Exception {
        byte[] original = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        byte[] padded = Arrays.copyOf(original, original.length + 64);
        Arrays.fill(padded, original.length, padded.length, (byte)' ');
        String sha = TlshFingerprintGrpcIntegrationTest.sha(padded);
        upload(original, UUID.randomUUID());
        jdbc.update("INSERT INTO hash_blacklist(hash_value, reason) VALUES (?, 'harmless baseline fixture')", TlshFingerprintGrpcIntegrationTest.sha(original));
        assertThat(upload(padded, UUID.randomUUID()).getDecision()).isEqualTo(Decision.DECISION_BLOCK);
        UUID admin = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_users(admin_id, username, password_hash) VALUES (?, ?, 'unused')", admin, "tlsh-" + admin);
        jdbc.update("INSERT INTO hash_whitelist(hash_value, reason, added_by) VALUES (?, 'exact exception', ?)", sha, admin);
        try {
            var response = upload(padded, UUID.randomUUID());
            assertThat(response.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
            assertThat(response.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_WHITELIST);
        } finally { jdbc.update("DELETE FROM hash_whitelist WHERE hash_value=?", sha); }
    }

    @Test
    void no_candidates_clean_cache_is_reused_and_event_maps_to_original_evidence() throws Exception {
        byte[] original = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        // 파일 지문이 있지만 기준 목록은 비어 있는 경우도 평가 완료 상태다.
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        upload(original, first);
        var response = upload(original, second);
        assertThat(response.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_CACHE);
        assertThat(jdbc.queryForObject("SELECT assessment_id FROM event_decision_evidence WHERE event_id=?", UUID.class, second))
                .isEqualTo(jdbc.queryForObject("SELECT assessment_id FROM event_decision_evidence WHERE event_id=?", UUID.class, first));
    }

    @Test
    void newly_available_input_fingerprint_invalidates_its_previous_unavailable_cache() throws Exception {
        byte[] original = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        baseline(FingerprintImportRestIntegrationTest.TLSH);
        jdbc.update("DELETE FROM file_fingerprints WHERE sha256=?", TlshFingerprintGrpcIntegrationTest.sha(original));
        org.mockito.Mockito.doReturn(new com.teecherteamc.platform.verdict.domain.FingerprintCalculation(null, "UNAVAILABLE"))
                .when(calculator).calculate(org.mockito.ArgumentMatchers.any(java.nio.file.Path.class));
        assertThat(upload(original, UUID.randomUUID()).getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        org.mockito.Mockito.doCallRealMethod().when(calculator).calculate(org.mockito.ArgumentMatchers.any(java.nio.file.Path.class));
        assertThat(upload(original, UUID.randomUUID()).getDecision()).isEqualTo(Decision.DECISION_BLOCK);
    }

    @Test
    void rule_changes_invalidate_similarity_blocks_too() throws Exception {
        byte[] original = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        baseline(FingerprintImportRestIntegrationTest.TLSH);
        assertThat(upload(original, UUID.randomUUID()).getSourceValue()).isEqualTo(6);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s -> verdicts.markEngineVerdictsStale());
        assertThat(jdbc.queryForObject("SELECT is_stale FROM file_verdicts WHERE sha256=?", Boolean.class,
                TlshFingerprintGrpcIntegrationTest.sha(original))).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"30,BLOCK", "31,WARN", "70,WARN", "71,NONE"})
    void measured_policy_boundaries_preserve_engine_and_cached_event_evidence(int distance, String classification) throws Exception {
        byte[] original = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        String sample = baseline(digestAt(distance));
        UUID first = UUID.randomUUID();
        var response = upload(original, first);
        assertThat(response.getDecision()).isEqualTo(distance <= 30 ? Decision.DECISION_BLOCK : Decision.DECISION_ALLOW);
        var row = jdbc.queryForMap("SELECT a.* FROM similarity_assessments a JOIN event_decision_evidence e USING(assessment_id) WHERE e.event_id=?", first);
        assertThat(row.get("distance")).isEqualTo(distance);
        assertThat(row.get("classification")).isEqualTo(classification);
        assertThat(row.get("matched_sample_sha256")).isEqualTo(sample);
        assertThat(row.get("policy_version")).isEqualTo("tlsh-30-70-v1");
        UUID cachedEvent = UUID.randomUUID();
        upload(original, cachedEvent);
        assertThat(jdbc.queryForObject("SELECT assessment_id FROM event_decision_evidence WHERE event_id=?", UUID.class, cachedEvent)).isEqualTo(row.get("assessment_id"));
        report(cachedEvent, TlshFingerprintGrpcIntegrationTest.sha(original), distance <= 30);
        assertThat(jdbc.queryForObject("SELECT similarity_assessment_id FROM download_events WHERE event_id=?", UUID.class, cachedEvent)).isEqualTo(row.get("assessment_id"));
        jdbc.update("DELETE FROM hash_blacklist WHERE hash_value=?", sample);
        upload(original, UUID.randomUUID());
        // 이후 재분석해도 먼저 보고한 이벤트의 근거와 샘플 스냅샷은 바뀌지 않는다.
        assertThat(jdbc.queryForObject("SELECT similarity_assessment_id FROM download_events WHERE event_id=?", UUID.class, cachedEvent)).isEqualTo(row.get("assessment_id"));
        assertThat(jdbc.queryForObject("SELECT matched_sample_tlsh FROM similarity_assessments WHERE assessment_id=?", String.class, row.get("assessment_id"))).isEqualTo(digestAt(distance));
    }

    void report(UUID event, String sha, boolean block) {
        blocking.reportEvent(ReportEventRequest.newBuilder().setEventId(event.toString()).setSha256(sha)
                .setUrl("https://fixture.invalid/file").setRequestHost("fixture.invalid")
                .setDecision(block ? FinalDecision.FINAL_DECISION_BLOCKED : FinalDecision.FINAL_DECISION_RELEASED)
                .setDecisionSourceValue(block ? 6 : 3)
                .setHeldAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(java.time.Instant.now().getEpochSecond())).build());
    }

    static String digestAt(int distance) {
        String original = FingerprintImportRestIntegrationTest.TLSH;
        var reference = com.trendmicro.tlsh.Tlsh.fromTlshStr(original);
        Random random = new Random(713);
        // 정책 경계용 메타데이터만 합성한다. 실제 바이트 변종 검증은 padding distance17 테스트가 맡는다.
        for (int i=0; i<100000; i++) {
            char[] candidate = original.toCharArray();
            for (int j=0, n=1+random.nextInt(30); j<n; j++) candidate[8+random.nextInt(64)] = "0123456789ABCDEF".charAt(random.nextInt(16));
            String value = new String(candidate);
            if (reference.totalDiff(com.trendmicro.tlsh.Tlsh.fromTlshStr(value), true)==distance) return value;
        }
        throw new AssertionError("fixture not found: " + distance);
    }

    @Test
    void engine_failure_over_stale_cached_allow_records_error_instead_of_leaving_old_allow() throws Exception {
        byte[] bytes = ("stale cached-" + FakeDetectionEngineService.CRASH_MARKER).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = TlshFingerprintGrpcIntegrationTest.sha(bytes);
        jdbc.update("INSERT INTO file_verdicts(sha256, verdict, verdict_source, is_stale) VALUES (?, 'CLEAN', 'ENGINE', true)", sha);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> upload(bytes, UUID.randomUUID()))
                .hasCauseInstanceOf(io.grpc.StatusRuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT verdict FROM file_verdicts WHERE sha256=?", String.class, sha)).isEqualTo("ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analyses WHERE sha256=? AND verdict='ERROR'", Integer.class, sha)).isEqualTo(1);
    }

    @Test
    void nearest_candidate_ties_are_stable_and_no_variants_are_auto_enrolled() throws Exception {
        byte[] original = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        String far = baseline(digestAt(71));
        String near1 = baseline(digestAt(31));
        String near2 = baseline(digestAt(31));
        UUID event = UUID.randomUUID();
        assertThat(upload(original, event).getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        var row = jdbc.queryForMap("SELECT a.* FROM similarity_assessments a JOIN event_decision_evidence e USING(assessment_id) WHERE event_id=?", event);
        assertThat(row.get("distance")).isEqualTo(31);
        assertThat(row.get("matched_sample_sha256")).isEqualTo(near1.compareTo(near2)<0 ? near1 : near2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hash_blacklist WHERE hash_value=?", Integer.class, TlshFingerprintGrpcIntegrationTest.sha(original))).isZero();
        bloom.rebuild();
        assertThat(blocking.checkHash(CheckHashRequest.newBuilder().setSha256(TlshFingerprintGrpcIntegrationTest.sha(original)).build()).getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        UUID lookupOnly = UUID.randomUUID();
        report(lookupOnly, TlshFingerprintGrpcIntegrationTest.sha(original), false);
        assertThat(jdbc.queryForObject("SELECT similarity_assessment_id FROM download_events WHERE event_id=?", UUID.class, lookupOnly)).isNull();
    }

    @Test
    void baseline_import_update_and_effective_whitelist_expiry_invalidate_both_caches() throws Exception {
        byte[] original = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        String sha = TlshFingerprintGrpcIntegrationTest.sha(original);
        String sample = baseline(digestAt(71));
        UUID actor = jdbc.queryForObject("SELECT imported_by FROM file_fingerprints WHERE sha256=?", UUID.class, sample);
        assertThat(upload(original, UUID.randomUUID()).getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        bloom.rebuild();
        importer.importFingerprint(sample, FingerprintImportRestIntegrationTest.TLSH.substring(2), "legacy normalized fixture", actor, null);
        assertThat(blocking.checkHash(CheckHashRequest.newBuilder().setSha256(sha).build()).getDecision()).isEqualTo(Decision.DECISION_UNKNOWN);
        assertThat(upload(original, UUID.randomUUID()).getSourceValue()).isEqualTo(6);
        jdbc.update("INSERT INTO hash_whitelist(hash_value, reason, added_by, expires_at) VALUES (?, 'baseline exception', ?, clock_timestamp()+interval '1 second')", sample, actor);
        try {
            assertThat(blocking.checkHash(CheckHashRequest.newBuilder().setSha256(sha).build()).getDecision()).isEqualTo(Decision.DECISION_UNKNOWN);
            assertThat(upload(original, UUID.randomUUID()).getDecision()).isEqualTo(Decision.DECISION_ALLOW);
            Thread.sleep(1100); // 쓰기 없이 시간 경과만으로 뷰와 토큰이 바뀌는지 확인한다.
            assertThat(blocking.checkHash(CheckHashRequest.newBuilder().setSha256(sha).build()).getDecision()).isEqualTo(Decision.DECISION_UNKNOWN);
            assertThat(upload(original, UUID.randomUUID()).getSourceValue()).isEqualTo(6);
        } finally { jdbc.update("DELETE FROM hash_whitelist WHERE hash_value=?", sample); }
    }

    @Test
    void yara_blocks_and_structured_failures_do_not_run_similarity() throws Exception {
        baseline(FingerprintImportRestIntegrationTest.TLSH);
        for (String marker : List.of(FakeDetectionEngineService.MALICIOUS_MARKER, FakeDetectionEngineService.INCONSISTENT_MARKER)) {
            byte[] bytes = ("tlsh-policy-" + marker).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var response = upload(bytes, UUID.randomUUID());
            assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);
            assertThat(response.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_ENGINE);
            org.mockito.Mockito.verify(similarity, org.mockito.Mockito.never()).compare(TlshFingerprintGrpcIntegrationTest.sha(bytes));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM file_fingerprints WHERE sha256=?", Integer.class, TlshFingerprintGrpcIntegrationTest.sha(bytes))).isEqualTo(1);
        }
    }

    @Test
    void s3_failure_never_downgrades_similarity_block() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        baseline(FingerprintImportRestIntegrationTest.TLSH);
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated storage outage")).when(quarantine).upload(org.mockito.ArgumentMatchers.any());
        var response = upload(bytes, UUID.randomUUID());
        assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);
        assertThat(response.getSourceValue()).isEqualTo(6);
    }

    @Test
    void corpus_writer_waits_for_comparison_commit_then_invalidates_the_committed_cache() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        String sha = TlshFingerprintGrpcIntegrationTest.sha(bytes);
        String sample = baseline(FingerprintImportRestIntegrationTest.TLSH);
        CountDownLatch compared = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch writerStarted = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            Object assessment = call.callRealMethod();
            compared.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("comparison release timeout");
            return assessment;
        }).when(org.springframework.test.util.AopTestUtils.<com.teecherteamc.platform.verdict.application.SimilarityDecisions>getUltimateTargetObject(similarity)).compare(sha);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            UUID event = UUID.randomUUID();
            Future<SubmitFileResponse> upload = pool.submit(() -> upload(bytes, event));
            assertThat(compared.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> writer = pool.submit(() -> {
                writerStarted.countDown();
                return jdbc.update("UPDATE hash_blacklist SET is_active=false WHERE hash_value=?", sample);
            });
            assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(upload.get(10, TimeUnit.SECONDS).getSourceValue()).isEqualTo(6);
            assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            bloom.rebuild();
            assertThat(blocking.checkHash(CheckHashRequest.newBuilder().setSha256(sha).build()).getDecision()).isEqualTo(Decision.DECISION_UNKNOWN);
            report(event, sha, true);
            assertThat(jdbc.queryForObject("SELECT a.classification FROM download_events e JOIN similarity_assessments a ON e.similarity_assessment_id=a.assessment_id WHERE event_id=?", String.class, event)).isEqualTo("BLOCK");
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void baseline_and_whitelist_changes_during_engine_analysis_are_rechecked() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        FakeDetectionEngineService.beforeNextAnalyzeVerdict(() -> baseline(FingerprintImportRestIntegrationTest.TLSH));
        jdbc.update("UPDATE file_verdicts SET is_stale=true WHERE sha256=?", TlshFingerprintGrpcIntegrationTest.sha(bytes));
        assertThat(upload(bytes, UUID.randomUUID()).getSourceValue()).isEqualTo(6);
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_users(admin_id, username, password_hash) VALUES (?, ?, 'unused')", actor, "tlsh-race-"+actor);
        String sha = TlshFingerprintGrpcIntegrationTest.sha(bytes);
        jdbc.update("UPDATE file_verdicts SET is_stale=true WHERE sha256=?", sha);
        FakeDetectionEngineService.beforeNextAnalyzeVerdict(() -> jdbc.update("INSERT INTO hash_whitelist(hash_value, reason, added_by) VALUES (?, 'mid-analysis', ?)", sha, actor));
        try {
            assertThat(upload(bytes, UUID.randomUUID()).getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_WHITELIST);
        } finally { jdbc.update("DELETE FROM hash_whitelist WHERE hash_value=?", sha); FakeDetectionEngineService.beforeNextAnalyzeVerdict(null); }
    }

    @Test
    void simultaneous_verified_uploads_keep_each_event_evidence() throws Exception {
        byte[] bytes = (new String(getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)+UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        baseline(FingerprintImportRestIntegrationTest.TLSH);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> upload(bytes, first));
            var two = pool.submit(() -> upload(bytes, second));
            assertThat(one.get(15, TimeUnit.SECONDS).getDecision()).isEqualTo(two.get(15, TimeUnit.SECONDS).getDecision());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_decision_evidence WHERE event_id IN (?, ?)", Integer.class, first, second)).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = com.teecherteamc.platform.proto.engine.v1.EngineVerdict.class,
            names = {"ENGINE_VERDICT_MALICIOUS", "ENGINE_VERDICT_SUSPICIOUS"})
    void whitelist_inserted_during_yara_block_does_not_release_current_upload_or_recache_old_block(
            com.teecherteamc.platform.proto.engine.v1.EngineVerdict verdict) throws Exception {
        byte[] bytes = ("whitelist-during-yara-" + verdict).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FakeDetectionEngineService.verdictOverride = verdict;
        String sha = TlshFingerprintGrpcIntegrationTest.sha(bytes);
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_users(admin_id, username, password_hash) VALUES (?, ?, 'unused')", actor, "tlsh-yara-race-"+actor);
        FakeDetectionEngineService.beforeNextAnalyzeVerdict(() -> jdbc.update("INSERT INTO hash_whitelist(hash_value, reason, added_by) VALUES (?, 'mid-yara', ?)", sha, actor));
        try {
            var response = upload(bytes, UUID.randomUUID());
            assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);
            assertThat(response.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_ENGINE);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_quarantine_uploads WHERE sha256=?", Integer.class, sha)).isEqualTo(1);
            // 다음 독립 요청의 정확 화이트리스트 동작은 그대로 유지한다.
            assertThat(upload(bytes, UUID.randomUUID()).getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_WHITELIST);
        } finally {
            jdbc.update("DELETE FROM hash_whitelist WHERE hash_value=?", sha);
            FakeDetectionEngineService.verdictOverride = null;
        }
        bloom.rebuild();
        assertThat(blocking.checkHash(CheckHashRequest.newBuilder().setSha256(sha).build()).getDecision()).isEqualTo(Decision.DECISION_UNKNOWN);
    }

    @Test
    void whitelist_added_after_failed_engine_response_does_not_turn_fail_close_into_allow() throws Exception {
        byte[] bytes = ("failed-engine-whitelist-" + FakeDetectionEngineService.INCONSISTENT_MARKER).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = TlshFingerprintGrpcIntegrationTest.sha(bytes);
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_users(admin_id, username, password_hash) VALUES (?, ?, 'unused')", actor, "tlsh-error-race-"+actor);
        FakeDetectionEngineService.beforeNextAnalyzeVerdict(() -> jdbc.update("INSERT INTO hash_whitelist(hash_value, reason, added_by) VALUES (?, 'mid-error', ?)", sha, actor));
        try {
            var response = upload(bytes, UUID.randomUUID());
            assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);
            assertThat(response.getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_ENGINE);
        } finally { jdbc.update("DELETE FROM hash_whitelist WHERE hash_value=?", sha); }
    }

    @Test
    void initial_clean_rollout_and_newly_imported_baseline_cannot_bypass_comparison() throws Exception {
        byte[] bytes = (new String(getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)+"rollout").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = TlshFingerprintGrpcIntegrationTest.sha(bytes);
        jdbc.update("INSERT INTO file_verdicts(sha256, verdict, verdict_source) VALUES (?, 'CLEAN', 'ENGINE')", sha);
        String sample = FingerprintImportRestIntegrationTest.randomSha();
        jdbc.update("INSERT INTO hash_blacklist(hash_value, reason) VALUES (?, 'harmless baseline fixture')", sample);
        bloom.rebuild();
        assertThat(blocking.checkHash(CheckHashRequest.newBuilder().setSha256(sha).build()).getDecision()).isEqualTo(Decision.DECISION_UNKNOWN);
        assertThat(upload(bytes, UUID.randomUUID()).getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_ENGINE);
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_users(admin_id, username, password_hash, role) VALUES (?, ?, 'unused', 'ADMIN')", actor, "tlsh-first-import-"+actor);
        String fingerprint = jdbc.queryForObject("SELECT tlsh FROM file_fingerprints WHERE sha256=?", String.class, sha);
        importer.importFingerprint(sample, fingerprint, "first available metadata", actor, null);
        assertThat(blocking.checkHash(CheckHashRequest.newBuilder().setSha256(sha).build()).getDecision()).isEqualTo(Decision.DECISION_UNKNOWN);
        assertThat(upload(bytes, UUID.randomUUID()).getSourceValue()).isEqualTo(6);
    }

    @Test
    void full_corpus_scan_does_not_stop_at_fetch_batch_boundary() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        String far = digestAt(71);
        for (int i=1; i<=300; i++) {
            String sha = "%064x".formatted(i);
            jdbc.update("INSERT INTO hash_blacklist(hash_value, reason) VALUES (?, 'harmless baseline fixture')", sha);
            jdbc.update("INSERT INTO file_fingerprints(sha256, tlsh, calculation_status, algorithm_version, provenance) VALUES (?, ?, 'CALCULATED', 'test-fixture', 'VERIFIED_UPLOAD')", sha, far);
        }
        String sample = baseline(FingerprintImportRestIntegrationTest.TLSH);
        UUID event = UUID.randomUUID();
        assertThat(upload(bytes, event).getSourceValue()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT matched_sample_sha256 FROM similarity_assessments a JOIN event_decision_evidence e USING(assessment_id) WHERE event_id=?", String.class, event)).isEqualTo(sample);
    }

    @Test
    void retried_submit_for_same_pending_event_uses_its_actual_last_decision_not_first_or_latest_sha_analysis() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        String sample = baseline(FingerprintImportRestIntegrationTest.TLSH);
        UUID event = UUID.randomUUID();
        assertThat(upload(bytes, event).getDecision()).isEqualTo(Decision.DECISION_BLOCK);
        jdbc.update("DELETE FROM hash_blacklist WHERE hash_value=?", sample);
        assertThat(upload(bytes, event).getDecision()).isEqualTo(Decision.DECISION_ALLOW);
        report(event, TlshFingerprintGrpcIntegrationTest.sha(bytes), false);
        assertThat(jdbc.queryForObject("SELECT a.classification FROM download_events e JOIN similarity_assessments a ON e.similarity_assessment_id=a.assessment_id WHERE event_id=?", String.class, event)).isEqualTo("NONE");
    }

    @Test
    void exact_whitelist_retry_does_not_attach_old_block_evidence_to_released_event() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        String sample = baseline(FingerprintImportRestIntegrationTest.TLSH);
        String sha = TlshFingerprintGrpcIntegrationTest.sha(bytes);
        UUID event = UUID.randomUUID();
        assertThat(upload(bytes, event).getSourceValue()).isEqualTo(6);
        UUID actor = jdbc.queryForObject("SELECT imported_by FROM file_fingerprints WHERE sha256=?", UUID.class, sample);
        jdbc.update("INSERT INTO hash_whitelist(hash_value, reason, added_by) VALUES (?, 'retry exception', ?)", sha, actor);
        try {
            assertThat(upload(bytes, event).getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_WHITELIST);
            assertThat(jdbc.queryForObject("SELECT assessment_id FROM event_decision_evidence WHERE event_id=?", UUID.class, event)).isNull();
            blocking.reportEvent(ReportEventRequest.newBuilder().setEventId(event.toString()).setSha256(sha)
                    .setUrl("https://fixture.invalid/whitelisted").setRequestHost("fixture.invalid")
                    .setDecision(FinalDecision.FINAL_DECISION_RELEASED).setDecisionSource(DecisionSource.DECISION_SOURCE_WHITELIST)
                    .setHeldAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(java.time.Instant.now().getEpochSecond())).build());
            assertThat(jdbc.queryForObject("SELECT similarity_assessment_id FROM download_events WHERE event_id=?", UUID.class, event)).isNull();
        } finally { jdbc.update("DELETE FROM hash_whitelist WHERE hash_value=?", sha); }
    }

    @Test
    void restoring_a_real_similarity_quarantine_whitelists_only_its_exact_sha_and_preserves_history() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        String sample = baseline(FingerprintImportRestIntegrationTest.TLSH);
        String sha = TlshFingerprintGrpcIntegrationTest.sha(bytes);
        UUID event = UUID.randomUUID();
        assertThat(upload(bytes, event).getSourceValue()).isEqualTo(6);
        report(event, sha, true);
        UUID evidence = jdbc.queryForObject("SELECT similarity_assessment_id FROM download_events WHERE event_id=?", UUID.class, event);
        UUID quarantineId = jdbc.queryForObject("SELECT quarantine_id FROM quarantine_files WHERE event_id=?", UUID.class, event);
        UUID actor = jdbc.queryForObject("SELECT imported_by FROM file_fingerprints WHERE sha256=?", UUID.class, sample);
        try {
            assertThat(restore.restore(quarantineId, "harmless similarity false positive fixture", actor, null).orElseThrow().whitelisted()).isTrue();
            assertThat(blocking.checkHash(CheckHashRequest.newBuilder().setSha256(sha).build()).getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_WHITELIST);
            assertThat(upload(bytes, UUID.randomUUID()).getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_WHITELIST);
            // 보고 완료된 UUID 재요청도 과거 근거를 비우지 않는다.
            assertThat(upload(bytes, event).getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_WHITELIST);
            assertThat(jdbc.queryForObject("SELECT assessment_id FROM event_decision_evidence WHERE event_id=?", UUID.class, event)).isEqualTo(evidence);
            assertThat(jdbc.queryForObject("SELECT is_active FROM hash_blacklist WHERE hash_value=?", Boolean.class, sample)).isTrue();
            assertThat(jdbc.queryForObject("SELECT similarity_assessment_id FROM download_events WHERE event_id=?", UUID.class, event)).isEqualTo(evidence);
            assertThat(jdbc.queryForObject("SELECT classification FROM similarity_assessments WHERE assessment_id=?", String.class, evidence)).isEqualTo("BLOCK");
        } finally { jdbc.update("DELETE FROM hash_whitelist WHERE hash_value=?", sha); }
    }

    @Test
    void another_authenticated_agent_cannot_submit_or_report_the_same_event() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        baseline(FingerprintImportRestIntegrationTest.TLSH);
        UUID event = UUID.randomUUID();
        upload(bytes, event);
        var second = VerdictTestClient.seed(jdbc);
        var original = stub;
        stub = VerdictTestClient.withToken(rawStub, second.token());
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> upload(bytes, event))
                    .hasCauseInstanceOf(io.grpc.StatusRuntimeException.class)
                    .satisfies(e -> assertThat(io.grpc.Status.fromThrowable(e.getCause()).getCode()).isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED));
        } finally { stub = original; }
        var report = ReportEventRequest.newBuilder().setEventId(event.toString()).setSha256(TlshFingerprintGrpcIntegrationTest.sha(bytes))
                .setUrl("https://fixture.invalid/file").setRequestHost("fixture.invalid")
                .setDecision(FinalDecision.FINAL_DECISION_BLOCKED).setDecisionSourceValue(6)
                .setHeldAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(java.time.Instant.now().getEpochSecond())).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> VerdictTestClient.withToken(rawBlocking, second.token()).reportEvent(report))
                .isInstanceOfSatisfying(io.grpc.StatusRuntimeException.class, e -> assertThat(e.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED));
        blocking.reportEvent(report);
        assertThat(jdbc.queryForObject("SELECT agent_id FROM download_events WHERE event_id=?", UUID.class, event)).isEqualTo(authenticatedAgent);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> VerdictTestClient.withToken(rawBlocking, second.token()).reportEvent(report))
                .isInstanceOfSatisfying(io.grpc.StatusRuntimeException.class, e -> assertThat(e.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED));
    }

    @Test
    void same_owner_cannot_rebind_an_event_to_another_sha() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        UUID event = UUID.randomUUID();
        upload(bytes, event);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> upload("different-content".getBytes(java.nio.charset.StandardCharsets.UTF_8), event))
                .hasCauseInstanceOf(io.grpc.StatusRuntimeException.class)
                .satisfies(e -> assertThat(io.grpc.Status.fromThrowable(e.getCause()).getCode()).isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED));
        var report = ReportEventRequest.newBuilder().setEventId(event.toString()).setSha256("f".repeat(64))
                .setUrl("https://fixture.invalid/file").setRequestHost("fixture.invalid")
                .setDecision(FinalDecision.FINAL_DECISION_RELEASED).setDecisionSourceValue(3)
                .setHeldAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(java.time.Instant.now().getEpochSecond())).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> blocking.reportEvent(report))
                .isInstanceOfSatisfying(io.grpc.StatusRuntimeException.class, e -> assertThat(e.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED));
        report(event, TlshFingerprintGrpcIntegrationTest.sha(bytes), false);
    }

    @Test
    void three_attempt_engine_only_retry_clears_pending_similarity_before_yara_cache_report() throws Exception {
        byte[] bytes = getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        baseline(FingerprintImportRestIntegrationTest.TLSH);
        String sha = TlshFingerprintGrpcIntegrationTest.sha(bytes);
        UUID event = UUID.randomUUID();
        assertThat(upload(bytes, event).getSourceValue()).isEqualTo(6);
        UUID oldAssessment = jdbc.queryForObject("SELECT assessment_id FROM event_decision_evidence WHERE event_id=?", UUID.class, event);
        // 룰셋 변경 뒤 같은 bytes가 YARA 악성으로 판정되는 상황을 실제 fake-engine RPC로 재현한다.
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s -> verdicts.markEngineVerdictsStale());
        FakeDetectionEngineService.verdictOverride = com.teecherteamc.platform.proto.engine.v1.EngineVerdict.ENGINE_VERDICT_MALICIOUS;
        try {
            assertThat(upload(bytes, event).getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_ENGINE);
            assertThat(jdbc.queryForObject("SELECT assessment_id FROM event_decision_evidence WHERE event_id=?", UUID.class, event)).isNull();
            assertThat(upload(bytes, event).getSource()).isEqualTo(DecisionSource.DECISION_SOURCE_CACHE);
        } finally {
            FakeDetectionEngineService.verdictOverride = null;
            // 공유 reference fixture의 강제 YARA 결과가 뒤 테스트의 캐시로 새지 않게 한다.
            jdbc.update("UPDATE file_verdicts SET is_stale=true WHERE sha256=?", sha);
        }
        blocking.reportEvent(ReportEventRequest.newBuilder().setEventId(event.toString()).setSha256(sha)
                .setUrl("https://fixture.invalid/retry").setRequestHost("fixture.invalid")
                .setDecision(FinalDecision.FINAL_DECISION_BLOCKED).setDecisionSource(DecisionSource.DECISION_SOURCE_CACHE)
                .setHeldAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(java.time.Instant.now().getEpochSecond())).build());
        assertThat(jdbc.queryForObject("SELECT similarity_assessment_id FROM download_events WHERE event_id=?", UUID.class, event)).isNull();
        assertThat(jdbc.queryForObject("SELECT assessment_id FROM event_decision_evidence WHERE event_id=?", UUID.class, event)).isNull();
        assertThat(jdbc.queryForObject("SELECT agent_id FROM event_decision_evidence WHERE event_id=?", UUID.class, event)).isEqualTo(authenticatedAgent);
        assertThat(jdbc.queryForObject("SELECT classification FROM similarity_assessments WHERE assessment_id=?", String.class, oldAssessment)).isEqualTo("BLOCK");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void late_successful_upload_links_already_reported_event_without_cross_agent_or_duplicate_quarantine(boolean yara) throws Exception {
        byte[] bytes = yara ? ("late-upload-" + UUID.randomUUID() + FakeDetectionEngineService.MALICIOUS_MARKER)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                : getClass().getResourceAsStream("/tlsh/reference.txt").readAllBytes();
        if (!yara) baseline(FingerprintImportRestIntegrationTest.TLSH);
        String sha = TlshFingerprintGrpcIntegrationTest.sha(bytes);
        UUID event = UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("S3 release timeout");
            return call.callRealMethod();
        }).when(quarantine).upload(org.mockito.ArgumentMatchers.any());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SubmitFileResponse> original = pool.submit(() -> upload(bytes, event));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(upload(bytes, event).getDecision()).isEqualTo(Decision.DECISION_BLOCK);
            // S3 중 corpus 쓰기가 완료되어야 한다. 이 변경은 이미 확정된 이벤트의 근거를 바꾸지 않는다.
            jdbc.update("UPDATE hash_blacklist SET reason=reason WHERE reason='harmless baseline fixture'");
            var request = ReportEventRequest.newBuilder().setEventId(event.toString()).setSha256(sha)
                    .setFilename("event-time-name.bin").setFileSize(bytes.length)
                    .setUrl("https://fixture.invalid/late").setRequestHost("fixture.invalid")
                    .setDecision(FinalDecision.FINAL_DECISION_BLOCKED)
                    .setDecisionSourceValue(yara ? 3 : 6)
                    .setHeldAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(java.time.Instant.now().getEpochSecond())).build();
            var other = VerdictTestClient.seed(jdbc);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> VerdictTestClient.withToken(rawBlocking, other.token()).reportEvent(request))
                    .isInstanceOfSatisfying(io.grpc.StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED));
            blocking.reportEvent(request);
            UUID assessment = jdbc.queryForObject("SELECT similarity_assessment_id FROM download_events WHERE event_id=?", UUID.class, event);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM quarantine_files WHERE event_id=?", Integer.class, event)).isZero();
            release.countDown();
            assertThat(original.get(10, TimeUnit.SECONDS).getDecision()).isEqualTo(Decision.DECISION_BLOCK);
            var row = jdbc.queryForMap("SELECT * FROM quarantine_files WHERE event_id=?", event);
            assertThat(row.get("agent_id")).isEqualTo(authenticatedAgent);
            assertThat(row.get("sha256")).isEqualTo(sha);
            assertThat(row.get("original_filename")).isEqualTo("event-time-name.bin");
            assertThat(((Number) row.get("file_size")).longValue()).isEqualTo(bytes.length);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_quarantine_uploads WHERE event_id=?", Integer.class, event)).isZero();
            assertThat(jdbc.queryForObject("SELECT similarity_assessment_id FROM download_events WHERE event_id=?", UUID.class, event)).isEqualTo(assessment);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> blocking.reportEvent(request))
                    .isInstanceOfSatisfying(io.grpc.StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.ALREADY_EXISTS));
            assertThat(upload(bytes, event).getDecision()).isEqualTo(Decision.DECISION_BLOCK);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM quarantine_files WHERE event_id=?", Integer.class, event)).isEqualTo(1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Autowired com.teecherteamc.platform.verdict.application.EventQuarantineUploads eventUploads;
    @Autowired com.teecherteamc.platform.event.application.ReportEventUseCase reports;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void completion_and_report_serialize_at_commit_boundary_and_completion_is_idempotent(boolean reportFirst) throws Exception {
        byte[] bytes = ("boundary-" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = TlshFingerprintGrpcIntegrationTest.sha(bytes);
        UUID event = UUID.randomUUID();
        upload(bytes, event);
        var command = new com.teecherteamc.platform.event.application.ReportEventCommand(event, authenticatedAgent,
                sha, "fixture.invalid", "https://fixture.invalid/boundary", "boundary-name.bin", null, null,
                (long) bytes.length, "BLOCKED", "ENGINE", false, bytes.length, java.time.Instant.now(), null, null);
        var upload = new com.teecherteamc.platform.verdict.domain.QuarantineUploadResult("boundary-bucket", event.toString());
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        CountDownLatch firstReady = new CountDownLatch(1), commit = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
        Runnable complete = () -> tx.executeWithoutResult(s -> eventUploads.complete(event, sha, authenticatedAgent, bytes.length, upload));
        Runnable report = () -> reports.reportEvent(command);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> tx.executeWithoutResult(s -> {
                (reportFirst ? report : complete).run();
                firstReady.countDown();
                try { if (!commit.await(10, TimeUnit.SECONDS)) throw new AssertionError("commit timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            assertThat(firstReady.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> { secondStarted.countDown(); (reportFirst ? complete : report).run(); });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // 실제 event advisory lock 대기를 확인한다. 단순 sleep 기반 타이밍 추정이 아니다.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND NOT granted AND classid=74190323 AND objid=(hashtext(?)::bigint & 4294967295))", Boolean.class, event.toString()));
                if (waiting) break;
                Thread.yield();
            }
            assertThat(waiting).isTrue();
            assertThat(second.isDone()).isFalse();
            commit.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            complete.run();
            tx.executeWithoutResult(s -> eventUploads.complete(event, sha, authenticatedAgent, bytes.length,
                    new com.teecherteamc.platform.verdict.domain.QuarantineUploadResult("boundary-bucket", UUID.randomUUID().toString())));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM quarantine_files WHERE event_id=?", Integer.class, event)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_quarantine_uploads WHERE event_id=?", Integer.class, event)).isZero();
            assertThat(jdbc.queryForObject("SELECT original_filename FROM quarantine_files WHERE event_id=?", String.class, event)).isEqualTo("boundary-name.bin");
        } finally { commit.countDown(); pool.shutdownNow(); }
    }

    @Test
    void event_bound_pending_cannot_be_consumed_by_another_agents_matching_sha_report() throws Exception {
        byte[] bytes = ("pending-owner-" + UUID.randomUUID() + FakeDetectionEngineService.MALICIOUS_MARKER).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = TlshFingerprintGrpcIntegrationTest.sha(bytes);
        UUID event = UUID.randomUUID();
        upload(bytes, event);
        var other = VerdictTestClient.seed(jdbc);
        UUID otherEvent = UUID.randomUUID();
        VerdictTestClient.withToken(rawBlocking, other.token()).reportEvent(ReportEventRequest.newBuilder()
                .setEventId(otherEvent.toString()).setSha256(sha).setFilename("other.bin")
                .setRequestHost("fixture.invalid").setUrl("https://fixture.invalid/other")
                .setDecision(FinalDecision.FINAL_DECISION_BLOCKED).setDecisionSource(DecisionSource.DECISION_SOURCE_CACHE)
                .setHeldAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(java.time.Instant.now().getEpochSecond())).build());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quarantine_files WHERE event_id=?", Integer.class, otherEvent)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_quarantine_uploads WHERE event_id=?", Integer.class, event)).isEqualTo(1);
        report(event, sha, true);
        assertThat(jdbc.queryForObject("SELECT agent_id FROM quarantine_files WHERE event_id=?", UUID.class, event)).isEqualTo(authenticatedAgent);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_quarantine_uploads WHERE event_id=?", Integer.class, event)).isZero();
    }

    String baseline(String tlsh) {
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_users(admin_id, username, password_hash, role) VALUES (?, ?, 'unused', 'ADMIN')", actor, "tlsh-import-" + actor);
        String sha = FingerprintImportRestIntegrationTest.randomSha();
        jdbc.update("INSERT INTO hash_blacklist(hash_value, reason) VALUES (?, 'harmless baseline fixture')", sha);
        importer.importFingerprint(sha, tlsh, "controlled harmless metadata fixture", actor, null);
        return sha;
    }

    SubmitFileResponse upload(byte[] bytes, UUID event) throws Exception {
        var result = new CompletableFuture<SubmitFileResponse>();
        var stream = stub.submitFile(new StreamObserver<SubmitFileResponse>() {
            public void onNext(SubmitFileResponse r) { result.complete(r); }
            public void onError(Throwable t) { result.completeExceptionally(t); }
            public void onCompleted() {}
        });
        stream.onNext(SubmitFileRequest.newBuilder().setMetadata(SubmitFileMetadata.newBuilder()
                .setSha256(TlshFingerprintGrpcIntegrationTest.sha(bytes)).setFileSize(bytes.length).setEventId(event.toString())).build());
        stream.onNext(SubmitFileRequest.newBuilder().setChunk(ByteString.copyFrom(bytes)).build());
        stream.onCompleted();
        return result.get(20, TimeUnit.SECONDS);
    }
}
