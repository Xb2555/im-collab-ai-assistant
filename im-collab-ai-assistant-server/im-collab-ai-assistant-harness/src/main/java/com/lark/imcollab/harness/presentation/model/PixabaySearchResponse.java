package com.lark.imcollab.harness.presentation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PixabaySearchResponse {

    private List<PixabayHit> hits;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PixabayHit {
        private String largeImageURL;
        private String webformatURL;
        private String previewURL;
        private String tags;
        private Integer imageWidth;
        private Integer imageHeight;
    }
}
