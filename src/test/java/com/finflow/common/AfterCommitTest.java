package com.finflow.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 지표는 저장이 확정된 뒤에만 셈
class AfterCommitTest {

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // 트랜잭션을 흉내 내서 끝냄 (커밋이면 afterCommit, 롤백이면 afterCompletion만)
    private static void finish(boolean commit) {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            if (commit) {
                s.afterCommit();
            }
            s.afterCompletion(commit ? TransactionSynchronization.STATUS_COMMITTED
                    : TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("[TC-OBS-202] 트랜잭션 안에서 기록: 커밋 뒤에만 셈 / 롤백이면 세지 않음 / 트랜잭션 밖이면 바로 셈")
    void runsOnlyAfterCommit() {
        AtomicInteger count = new AtomicInteger();

        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.run(count::incrementAndGet);
        assertThat(count).hasValue(0);
        finish(true);
        assertThat(count).hasValue(1);

        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.run(count::incrementAndGet);
        finish(false);
        assertThat(count).hasValue(1);

        AfterCommit.run(count::incrementAndGet);
        assertThat(count).hasValue(2);
    }
}
