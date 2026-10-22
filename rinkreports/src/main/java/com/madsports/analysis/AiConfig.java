package com.madsports.analysis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registra la configuración del análisis con IA. */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
class AiConfig {
}
