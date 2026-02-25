/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Test configuration to enable async execution in tests.
 * Uses SyncTaskExecutor so async methods run synchronously in tests.
 */
@Configuration
@EnableAsync
public class TestAsyncConfig {
    
    @Bean
    @Primary
    public Executor taskExecutor() {
        // Use SyncTaskExecutor so @Async methods run synchronously in tests
        // This makes tests deterministic and easier to verify
        return new SyncTaskExecutor();
    }
}
