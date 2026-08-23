package com.reservex.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ExecutorConfigTest {

    @Test
    void mailPoolRejectsInsteadOfBlockingTheSchedulerThread() {
        var executor = new ExecutorConfig(new ReserveXProperties()).mailExecutor();
        try {
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class,
                    executor.getThreadPoolExecutor().getRejectedExecutionHandler());
        } finally {
            executor.shutdown();
        }
    }
}
