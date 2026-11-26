package com.teecherteamc.platform.lookup.application;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 블룸 재구축 요청 플래그. 요청이 몇 번 쌓여도 {@link #take()} 한 번이면 비워진다(첫 사유만 남는다).
 * 스레드 안전하다.
 */
@Component
public class BloomRebuildRequests {

    public static final String STARTUP = "startup";
    public static final String DAILY = "daily";
    public static final String MISSING = "missing";
    public static final String ADD_FAILED = "add_failed";
    /** 앞으로 생길 삭제 경로용 사유다. 지금은 호출하는 곳이 없다. */
    public static final String SOURCE_DELETED = "source_deleted";

    private final AtomicReference<String> pending = new AtomicReference<>();

    public void request(String reason) {
        pending.compareAndSet(null, reason);
    }

    /** 걸려 있던 첫 사유를 돌려주고 플래그를 비운다. */
    public Optional<String> take() {
        return Optional.ofNullable(pending.getAndSet(null));
    }
}
