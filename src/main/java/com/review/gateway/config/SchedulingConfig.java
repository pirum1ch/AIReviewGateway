package com.review.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

/**
 * Enables {@code @Scheduled} and provides a virtual-thread {@link TaskScheduler} (requirements §2.1:
 * Java 21 virtual threads suit this workload's profile of many long-blocked-on-I/O background ticks).
 * Single Gateway instance (architecture §12) means no distributed-lock library (e.g. ShedLock) is
 * needed — there is only ever one scheduler running these jobs.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();
        scheduler.setVirtualThreads(true);
        scheduler.setThreadNamePrefix("gateway-scheduler-");
        return scheduler;
    }
}
