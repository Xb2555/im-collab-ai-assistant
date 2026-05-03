package com.lark.imcollab.skills.lark.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "imcollab.skills.lark-doc")
public class LarkDocProperties {

    private String folderToken = "";

    private String webBaseUrl = "";

    private int requestTimeoutSeconds = 30;

    private int maxBlocksPerRequest = 40;

    private int maxTextCharsPerBlock = 1600;

}
