package com.lark.imcollab.skills.lark.auth;

import com.lark.imcollab.skills.ImCollabAiAssistantSkillsApplication;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ImCollabAiAssistantSkillsApplication.class)
@EnabledIfSystemProperty(named = "runRealLarkCliTests", matches = "true")
class LarkAdminAuthorizationRealTests {

    private static final Path DEFAULT_OUTPUT = Path.of("/tmp/lark-auth-qr.png");

    @Autowired
    private LarkAdminAuthorizationTool larkAdminAuthorizationTool;

    @Test
    void shouldGenerateRealAuthorizationQrCode() throws Exception {
        AdminAuthorizationSession session = larkAdminAuthorizationTool.startAdminAuthorization(
                System.getProperty("larkAuthProfileName")
        );
        byte[] png = session.qrCodePng();
        assertThat(png).isNotEmpty();

        Path output = resolveOutputPath();
        Files.write(output, png);
        assertThat(Files.size(output)).isPositive();
    }

    private Path resolveOutputPath() {
        String outputPath = System.getProperty("larkAuthQrOutput");
        if (outputPath == null || outputPath.isBlank()) {
            return DEFAULT_OUTPUT;
        }
        return Path.of(outputPath.trim());
    }
}
