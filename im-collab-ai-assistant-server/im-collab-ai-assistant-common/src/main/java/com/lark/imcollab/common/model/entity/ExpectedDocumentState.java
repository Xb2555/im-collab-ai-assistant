package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpectedDocumentState implements Serializable {
    private DocumentExpectedStateType stateType;
    private Map<String, String> attributes;
}
