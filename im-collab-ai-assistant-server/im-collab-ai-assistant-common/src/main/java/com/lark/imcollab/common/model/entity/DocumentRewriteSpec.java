package com.lark.imcollab.common.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRewriteSpec implements Serializable {
    private String targetContent;
    private boolean styleOnly;
    private String newContent;
}
