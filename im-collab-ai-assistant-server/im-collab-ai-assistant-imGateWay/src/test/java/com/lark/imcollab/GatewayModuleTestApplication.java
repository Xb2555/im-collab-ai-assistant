package com.lark.imcollab;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.lark.imcollab")
@ConfigurationPropertiesScan(basePackages = "com.lark.imcollab")
class GatewayModuleTestApplication {
}
