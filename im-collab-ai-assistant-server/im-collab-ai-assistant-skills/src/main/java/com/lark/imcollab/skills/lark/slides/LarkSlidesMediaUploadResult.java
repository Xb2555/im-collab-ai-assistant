package com.lark.imcollab.skills.lark.slides;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LarkSlidesMediaUploadResult {

    private String presentationId;
    private String fileToken;
    private String fileName;
    private String message;
}
