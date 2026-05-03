package com.lark.imcollab.skills.lark.doc;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LarkDocFetchResult implements Serializable {
    private String docId;
    private String docUrl;
    private long revisionId;
    private String content;
    private String docFormat;
    private String detail;
    private String scope;

    private boolean success;
    private String docRef;
    private String title;
    private String message;
}
