package com.lark.imcollab.harness.scene.c.model;

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
public class SceneCDocOutline implements Serializable {

    private String title;

    private String templateType;

    private List<SceneCDocOutlineSection> sections;
}
