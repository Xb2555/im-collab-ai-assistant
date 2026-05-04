package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentPatchOperation implements Serializable {
    private DocumentPatchOperationType operationType;
    private String runtimeGroupKey;
    private String blockId;
    private String targetBlockId;
    private String startBlockId;
    private String endBlockId;
    private String oldText;
    private String newContent;
    private String docFormat;
    private String justification;
}
