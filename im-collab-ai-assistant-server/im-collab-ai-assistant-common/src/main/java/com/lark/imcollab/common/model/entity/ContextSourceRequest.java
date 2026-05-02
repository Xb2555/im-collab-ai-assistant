package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.ContextSourceTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextSourceRequest implements Serializable {

    private ContextSourceTypeEnum sourceType;

    private String chatId;

    private String threadId;

    private String timeRange;

    private String startTime;

    private String endTime;

    private List<String> docRefs;

    private Integer limit;
}
