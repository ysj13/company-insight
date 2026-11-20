package com.companyinsight.analysis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 분석 작업 큐 설정({@link AnalysisProperties}) 등록. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalysisProperties.class)
class AnalysisConfig {
}
