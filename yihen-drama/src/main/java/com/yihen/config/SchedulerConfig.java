package com.yihen.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulerConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(8); // 原来为1，调大避免因为第三方请求慢导致后续轮询全部排队阻塞
        s.setThreadNamePrefix("video-poller-");
        s.initialize();
        return s;
    }
}
