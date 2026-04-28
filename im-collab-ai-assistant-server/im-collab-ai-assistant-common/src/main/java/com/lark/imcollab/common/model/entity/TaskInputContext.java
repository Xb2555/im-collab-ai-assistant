package com.lark.imcollab.common.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "浠诲姟杈撳叆涓婁笅鏂?")
public class TaskInputContext implements Serializable {

    @Schema(description = "杈撳叆鏉ユ簮")
    private String inputSource;

    @Schema(description = "浼氳瘽ID")
    private String chatId;

    @Schema(description = "绾跨▼ID")
    private String threadId;

    @Schema(description = "娑堟伅ID")
    private String messageId;

    @Schema(description = "鍙戦€佽€卌penId")
    private String senderOpenId;

    @Schema(description = "鑱婂ぉ绫诲瀷")
    private String chatType;
}
