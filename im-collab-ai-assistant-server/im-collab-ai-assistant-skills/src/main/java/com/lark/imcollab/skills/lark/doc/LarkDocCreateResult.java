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
public class LarkDocCreateResult implements Serializable {

    private String docId;

    private String docUrl;

    private String message;
}
