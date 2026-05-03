package com.lark.imcollab.skills.lark.slides;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LarkSlidesFetchResult {

    private boolean success;
    private String presentationId;
    private String title;
    private String xml;
    private String message;
}
