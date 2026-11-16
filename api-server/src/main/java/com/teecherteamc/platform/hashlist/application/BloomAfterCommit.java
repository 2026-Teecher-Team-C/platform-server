package com.teecherteamc.platform.hashlist.application;

import com.teecherteamc.platform.lookup.domain.BloomFilterPort;
import java.util.Collection;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 목록 쓰기가 커밋된 뒤에 블룸에 추가한다. 커밋 전에 add하면, 커밋 전 행을 못 보는 재구축이
 * 그 add를 덮어써서 누락될 수 있다. 트랜잭션이 없으면 즉시 추가한다.
 */
final class BloomAfterCommit {

    private BloomAfterCommit() {
    }

    static void addAll(BloomFilterPort bloomFilter, Collection<String> sha256s) {
        List<String> copy = List.copyOf(sha256s);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    bloomFilter.addAll(copy);
                }
            });
        } else {
            bloomFilter.addAll(copy);
        }
    }

    static void add(BloomFilterPort bloomFilter, String sha256) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    bloomFilter.add(sha256);
                }
            });
        } else {
            bloomFilter.add(sha256);
        }
    }
}
