package com.lark.imcollab.harness.presentation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PexelsSearchResponse {

    private List<PexelsPhoto> photos;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PexelsPhoto {
        private String alt;
        private PexelsPhotoSource src;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PexelsPhotoSource {
        private String original;
        private String large2x;
        private String large;
        private String medium;
        private String small;
    }
}
