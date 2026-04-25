package com.lark.imcollab.skills.lark.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imcollab.skills.lark-cli")
public class LarkCliProperties {

    private String executable = "lark-cli";
    private String workingDirectory = "";
    private int qrCodeSize = 280;

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public int getQrCodeSize() {
        return qrCodeSize;
    }

    public void setQrCodeSize(int qrCodeSize) {
        this.qrCodeSize = qrCodeSize;
    }
}
