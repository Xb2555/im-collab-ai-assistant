package com.lark.imcollab.skills.lark.doc;

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
public class LarkDocUpdateResult implements Serializable {

    private boolean success;

    private String docId;

    private String mode;

    private String message;

    private List<String> boardTokens;
}
