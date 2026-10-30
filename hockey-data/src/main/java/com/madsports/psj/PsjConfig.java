package com.madsports.psj;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registra la configuración de gestiondepsj. */
@Configuration
@EnableConfigurationProperties(PsjProperties.class)
class PsjConfig {
}
