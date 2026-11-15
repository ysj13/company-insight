package com.companyinsight.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 정기 수집 작업. 테스트에서는 {@code app.scheduling.enabled=false}로 끈다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}

/** {@code @Retryable}, {@code @ConcurrencyLimit} 활성화 (Spring Framework 7). */
@Configuration(proxyBeanMethods = false)
@EnableResilientMethods
class ResilienceConfig {
}
