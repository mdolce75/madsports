package com.madsports.sidgad;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registra la configuración de SIDGAD. */
@Configuration
@EnableConfigurationProperties(SidgadProperties.class)
class SidgadConfig {
}
