package com.lark.imcollab.harness.presentation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresentationExecutionState {

    private String taskId;
    private String cardId;
    private String storyline;
    private List<String> slideOutline;
    private List<String> slideContent;
    private List<String> speakerNotes;
    private String exportTarget;
}
