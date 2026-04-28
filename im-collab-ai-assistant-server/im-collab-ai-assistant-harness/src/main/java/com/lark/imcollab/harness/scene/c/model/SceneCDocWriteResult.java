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
public class SceneCDocWriteResult implements Serializable {

    private String docId;

    private String docUrl;

    private List<String> artifactRefs;
}
