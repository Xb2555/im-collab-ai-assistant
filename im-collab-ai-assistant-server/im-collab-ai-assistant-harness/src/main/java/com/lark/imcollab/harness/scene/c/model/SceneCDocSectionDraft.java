package com.lark.imcollab.harness.scene.c.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SceneCDocSectionDraft implements Serializable {

    private String heading;

    private String body;
}
