package com.teecherteamc.platform.lookup.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 정확 일치 조회 대상(file_verdicts ∪ 블랙리스트 ∪ 화이트리스트)의 sha256을 모두 담는 블룸 필터.
 * "없음"이면 그 해시는 확실히 세 곳 어디에도 없다는 뜻이고(블룸 필터는 거짓 양성만 가능, 거짓 음성은 없음),
 * CheckHashUseCase가 이 신호로 file_verdicts 조회를 생략한다 — CLEAN/MALICIOUS 어느 쪽 캐시
 * 히트든 블룸을 통과해야 하므로, 둘 다 빠짐없이 추적해야 한다.
 */
public interface BloomFilterPort {

    /**
     * 블룸 키가 없으면(Redis 초기화 등) 판단 근거가 없으므로 true("있을 수 있음")를 돌려주고 재구축을 요청한다.
     * 거짓 음성을 내지 않는다.
     */
    boolean mightContain(String sha256);

    /** 현재 live 블룸의 Redis 키 이름(비트 수 포함). 테스트·지표 로그용. */
    String keyName();

    /**
     * 해시 한 건을 즉시 추가한다. 목록 등록·file_verdicts 신규 저장 직후 호출해서 재구축을 기다리지 않게
     * 한다. Redis 장애는 로그만 남기고 삼키되 재구축을 요청한다 — 누락은 그 재구축이 메우고, 호출한 쓰기를 실패시키지 않는다.
     */
    void add(String sha256);

    /** 여러 건을 Lua 스크립트로 묶어 추가한다(청크마다 왕복 한 번). 일괄 가져오기용. 실패 시 재구축을 요청하는 것은 add와 같다. */
    void addAll(Collection<String> sha256s);

    /**
     * 깨끗한 재구축을 시작한다: 재구축 중 표시(pending)를 세운다. 이미 다른 재구축이 돌고 있으면 empty.
     * 표시를 세운 <b>뒤에</b> 원천 스냅숏을 읽어 {@link BloomRebuild#complete}에 넘겨야 빠짐이 없다 — 표시 뒤의 추가는
     * pending에도 쓰여 교체 때 합쳐진다.
     */
    Optional<BloomRebuild> beginRebuild();

    /**
     * Redis 서버 프로세스 식별자({@code INFO server}의 run_id). 바뀌었으면 Redis가 재시작된 것이다. 조회에 실패하면 null.
     */
    String serverRunId();

    /** live 블룸의 켜진 비트 수와 추정 오탐률. live 키가 없으면 empty. */
    Optional<BloomFillStats> fillStats();

    /** 시작된 재구축 한 건. try-with-resources로 쓴다 — complete 없이 닫히면 pending과 임시 키를 지운다. */
    interface BloomRebuild extends AutoCloseable {

        /**
         * 스냅숏 해시로 새 비트맵을 만들고, pending과 OR한 뒤 live와 원자적으로 교체한다. 옛 live와는 합치지 않으므로
         * 원천에서 빠진 해시의 비트는 지워진다. pending이 그사이 사라졌으면 교체하지 않고 {@code swapped=false}를 준다.
         */
        BloomRebuildResult complete(List<String> hashes);

        @Override
        void close();
    }
}
