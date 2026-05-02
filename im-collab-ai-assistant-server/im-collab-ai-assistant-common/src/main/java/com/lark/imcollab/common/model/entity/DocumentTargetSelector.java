package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.DocumentLocatorStrategy;
import com.lark.imcollab.common.model.enums.DocumentRelativePosition;
import com.lark.imcollab.common.model.enums.DocumentTargetType;
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
public class DocumentTargetSelector implements Serializable {
    private String docId;
    private String docUrl;
    private DocumentTargetType targetType;
    private DocumentLocatorStrategy locatorStrategy;
    private DocumentRelativePosition relativePosition;
    private String locatorValue;
    private List<String> matchedBlockIds;
    private String matchedExcerpt;
}
