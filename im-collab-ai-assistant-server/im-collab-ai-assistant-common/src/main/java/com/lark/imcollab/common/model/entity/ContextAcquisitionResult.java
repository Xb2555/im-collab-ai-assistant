package com.lark.imcollab.common.model.entity;

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
public class ContextAcquisitionResult implements Serializable {

    private boolean success;

    private boolean sufficient;

    private String contextSummary;

    private List<String> selectedMessages;

    private List<String> selectedMessageIds;

    private List<String> docFragments;

    private List<String> sourceRefs;

    private String message;

    private String clarificationQuestion;

    public static ContextAcquisitionResult failure(String message) {
        return ContextAcquisitionResult.builder()
                .success(false)
                .sufficient(false)
                .message(message == null ? "" : message)
                .build();
    }
}
