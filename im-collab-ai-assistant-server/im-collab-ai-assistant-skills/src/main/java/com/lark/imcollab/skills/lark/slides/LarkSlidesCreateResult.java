package com.lark.imcollab.skills.lark.slides;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LarkSlidesCreateResult {

    private String presentationId;
    private String presentationUrl;
    private String title;
    private String message;
}
