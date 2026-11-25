package com.schemaplexai.service.evaluation.impl;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** 评估协调器和等待模型的条目分别占用独立有界线程池。 */
@Component
class EvalExecutionScheduler {
    private final Executor coordinator;
    private final Executor items;
    private final TransactionTemplate onRejection;

    EvalExecutionScheduler(@Qualifier("evalCoordinatorPool") Executor coordinator,
                           @Qualifier("evalItemPool") Executor items,
                           PlatformTransactionManager transactionManager) {
        this.coordinator = coordinator;
        this.items = items;
        this.onRejection = new TransactionTemplate(transactionManager);
        this.onRejection.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void submit(Runnable task, Runnable rejected) {
        try {
            coordinator.execute(task);
        } catch (RejectedExecutionException e) {
            onRejection.executeWithoutResult(status -> rejected.run());
        }
    }

    CompletableFuture<Void> runItem(Runnable task) {
        return CompletableFuture.runAsync(task, items);
    }
}
