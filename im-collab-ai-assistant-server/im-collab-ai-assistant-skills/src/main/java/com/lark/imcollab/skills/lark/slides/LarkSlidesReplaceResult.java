package com.lark.imcollab.skills.lark.slides;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LarkSlidesReplaceResult {

    private String presentationId;
    private String slideId;
    private int partsCount;
    private String revisionId;
    private String message;
}
