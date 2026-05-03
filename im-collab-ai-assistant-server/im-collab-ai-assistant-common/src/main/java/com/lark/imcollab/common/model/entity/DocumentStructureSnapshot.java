package com.lark.imcollab.common.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStructureSnapshot implements Serializable {
    private String docId;
    private Long revisionId;
    private List<DocumentStructureNode> rootNodes;
    private Map<String, DocumentStructureNode> headingIndex;
    private Map<String, DocumentStructureNode> blockIndex;
    private List<String> topLevelSequence;
    private String rawOutlineXml;
    private String rawFullXml;
    private String rawFullMarkdown;
}
