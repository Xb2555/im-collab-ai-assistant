package com.lark.imcollab.skills.lark.doc;

import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class LarkCliStartupDiagnostics implements ApplicationRunner {

    private final LarkCliClient larkCliClient;
    private final LarkCliProperties properties;

    public LarkCliStartupDiagnostics(LarkCliClient larkCliClient, LarkCliProperties properties) {
        this.larkCliClient = larkCliClient;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            CliCommandResult version = larkCliClient.execute(List.of("--version"));
            CliCommandResult createHelp = larkCliClient.execute(List.of("docs", "+create", "--help"));
            CliCommandResult fetchHelp = larkCliClient.execute(List.of("docs", "+fetch", "--help"));
            log.info(
                    "Lark CLI diagnostics: executable={}, version={}, createApiVersionFlag={}, fetchApiVersionFlag={}",
                    properties.getExecutable(),
                    oneLine(version.output()),
                    containsApiVersion(createHelp.output()),
                    containsApiVersion(fetchHelp.output())
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Lark CLI diagnostics failed: executable={}, error={}",
                    properties.getExecutable(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    private boolean containsApiVersion(String output) {
        return output != null && output.contains("--api-version");
    }

    private String oneLine(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace('\n', ' ').trim();
    }
}
