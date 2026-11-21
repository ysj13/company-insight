package com.companyinsight.analysis.table;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 크기를 정하는 값({@link ImpactProperties}, {@link AiImpactProperties}) 등록. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ ImpactProperties.class, AiImpactProperties.class })
class TableReadConfig {
}
