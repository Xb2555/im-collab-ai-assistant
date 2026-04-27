package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskResultEvaluation implements Serializable {

    private String taskId;

    private String agentTaskId;

    @Builder.Default
    private int resultScore = 0;

    private ResultVerdictEnum verdict;

    private List<String> issues;

    private List<String> suggestions;

    @Builder.Default
    private Instant evaluatedAt = Instant.now();
}
