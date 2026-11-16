package com.companyinsight.financial;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 재무 수집 설정({@link FinancialProperties}) 등록. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FinancialProperties.class)
class FinancialConfig {
}
