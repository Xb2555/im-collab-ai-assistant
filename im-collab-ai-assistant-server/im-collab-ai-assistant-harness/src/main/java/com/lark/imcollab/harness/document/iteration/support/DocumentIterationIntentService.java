package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import org.springframework.stereotype.Component;

@Component
public class DocumentIterationIntentService {

    public DocumentIterationIntentType resolve(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "instruction must be provided");
        }
        String text = instruction.replaceAll("\\s+", "");
        if (containsAny(text, "解释", "什么意思", "阐述", "说明一下", "帮我看看")) {
            return DocumentIterationIntentType.EXPLAIN;
        }
        if (containsAny(text, "插入图片", "加图片", "插图", "插入附件", "上传附件", "插入表格", "插入白板")) {
            return DocumentIterationIntentType.INSERT_MEDIA;
        }
        if (containsAny(text, "调整布局", "调整结构", "标题层级", "改成callout", "改成列表", "挪到前面", "顺序调整")) {
            return DocumentIterationIntentType.ADJUST_LAYOUT;
        }
        if (containsAny(text, "删掉", "删除", "去掉", "移除")) {
            return DocumentIterationIntentType.DELETE;
        }
        if (containsAny(text, "新增", "添加", "补充", "插入", "追加", "加一节", "加一段")) {
            return DocumentIterationIntentType.INSERT;
        }
        if (containsAny(text, "风格", "语气", "正式", "口语化", "更简洁", "更正式", "发布会", "管理层", "润色")) {
            return DocumentIterationIntentType.UPDATE_STYLE;
        }
        return DocumentIterationIntentType.UPDATE_CONTENT;
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
