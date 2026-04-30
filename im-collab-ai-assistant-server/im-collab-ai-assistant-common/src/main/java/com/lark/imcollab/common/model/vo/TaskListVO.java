package com.lark.imcollab.common.model.vo;

import java.util.List;

public record TaskListVO(
        List<TaskSummaryVO> tasks,
        String nextCursor
) {
}
