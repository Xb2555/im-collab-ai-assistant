package com.lark.imcollab.common.model.entity;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
public class TableModel implements Serializable {
    private List<String> columns;
    private List<List<String>> rows;
    private List<String> formattingHints;
}
