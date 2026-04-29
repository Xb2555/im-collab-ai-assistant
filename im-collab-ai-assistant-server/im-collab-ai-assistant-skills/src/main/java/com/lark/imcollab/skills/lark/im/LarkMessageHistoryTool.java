package com.lark.imcollab.skills.lark.im;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class LarkMessageHistoryTool {

    private final LarkBotMessageClient messageClient;

    public LarkMessageHistoryTool(LarkBotMessageClient messageClient) {
        this.messageClient = messageClient;
    }

    @Tool(description = "Scenario A: fetch historical Lark IM messages from a chat or thread by container id as bot.")
    public LarkMessageHistoryResponse fetchHistory(
            String containerIdType,
            String containerId,
            String startTime,
            String endTime,
            String sortType,
            Integer pageSize,
            String pageToken
    ) {
        return messageClient.fetchHistory(
                containerIdType,
                containerId,
                startTime,
                endTime,
                sortType,
                pageSize,
                pageToken,
                null
        );
    }
}
