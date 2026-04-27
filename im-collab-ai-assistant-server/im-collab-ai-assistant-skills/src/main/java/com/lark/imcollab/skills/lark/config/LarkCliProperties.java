package com.lark.imcollab.skills.lark.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "imcollab.skills.lark-cli")
public class LarkCliProperties {

    private String executable = "lark-cli";
    private String workingDirectory = "";

}
