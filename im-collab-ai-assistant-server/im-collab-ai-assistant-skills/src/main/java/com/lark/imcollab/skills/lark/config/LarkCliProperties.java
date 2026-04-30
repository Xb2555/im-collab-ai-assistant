package com.lark.imcollab.skills.lark.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Setter
@Getter
@ConfigurationProperties(prefix = "imcollab.skills.lark-cli")
public class LarkCliProperties {

    private String executable = "lark-cli";
    private List<String> args = List.of();
    private String workingDirectory = "";
    private String docIdentity = "user";

}
