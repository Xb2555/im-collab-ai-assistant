package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.MediaAssetSpec;
import com.lark.imcollab.common.model.entity.ResolvedAsset;
import com.lark.imcollab.common.model.entity.TableModel;
import com.lark.imcollab.common.model.enums.MediaAssetSourceType;
import com.lark.imcollab.common.model.enums.MediaAssetType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class AssetResolutionFacade {

    private final ChatModel chatModel;
    private final DocumentImageSearchService documentImageSearchService;
    private final DocumentMermaidDslService documentMermaidDslService;

    @Autowired
    public AssetResolutionFacade(
            ChatModel chatModel,
            DocumentImageSearchService documentImageSearchService,
            DocumentMermaidDslService documentMermaidDslService
    ) {
        this.chatModel = chatModel;
        this.documentImageSearchService = documentImageSearchService;
        this.documentMermaidDslService = documentMermaidDslService;
    }

    AssetResolutionFacade(
            DocumentImageSearchService documentImageSearchService,
            DocumentMermaidDslService documentMermaidDslService
    ) {
        this.chatModel = null;
        this.documentImageSearchService = documentImageSearchService;
        this.documentMermaidDslService = documentMermaidDslService;
    }

    public ResolvedAsset resolve(MediaAssetSpec spec) {
        if (spec == null || spec.getAssetType() == null) {
            return null;
        }
        return switch (spec.getAssetType()) {
            case IMAGE -> resolveImage(spec);
            case TABLE -> resolveTable(spec);
            case WHITEBOARD -> resolveWhiteboard(spec);
            default -> ResolvedAsset.builder()
                    .assetType(spec.getAssetType())
                    .build();
        };
    }

    private ResolvedAsset resolveImage(MediaAssetSpec spec) {
        String sourceRef = firstNonBlank(spec.getSourceRef(), spec.getGenerationPrompt());
        if (spec.getSourceType() == MediaAssetSourceType.ATTACHMENT || spec.getSourceType() == MediaAssetSourceType.EXTERNAL_URL) {
            if (sourceRef == null || sourceRef.isBlank()) {
                throw new IllegalStateException("IMAGE asset requires sourceRef");
            }
            return ResolvedAsset.builder()
                    .assetType(MediaAssetType.IMAGE)
                    .assetRef(sourceRef)
                    .mimeType(spec.getMimeType())
                    .caption(spec.getCaption())
                    .altText(spec.getAltText())
                    .requiresUpload(false)
                    .requiresCreation(false)
                    .build();
        }
        if (spec.getSourceType() == MediaAssetSourceType.SEARCH || spec.getSourceType() == null) {
            String query = firstNonBlank(spec.getGenerationPrompt(), spec.getSourceRef());
            if (query == null || query.isBlank()) {
                throw new IllegalStateException("IMAGE asset requires sourceRef or generationPrompt");
            }
            return ResolvedAsset.builder()
                    .assetType(MediaAssetType.IMAGE)
                    .assetRef(documentImageSearchService.searchFirstImageUrl(query))
                    .mimeType(spec.getMimeType())
                    .caption(spec.getCaption())
                    .altText(spec.getAltText())
                    .requiresUpload(false)
                    .requiresCreation(false)
                    .build();
        }
        return ResolvedAsset.builder()
                .assetType(MediaAssetType.IMAGE)
                .assetRef(sourceRef)
                .mimeType(spec.getMimeType())
                .caption(spec.getCaption())
                .altText(spec.getAltText())
                .requiresUpload(false)
                .requiresCreation(false)
                .build();
    }

    private ResolvedAsset resolveTable(MediaAssetSpec spec) {
        TableModel tableModel = spec.getTableSchema();
        if (tableModel == null && spec.getGenerationPrompt() != null) {
            tableModel = generateTableModel(spec.getGenerationPrompt());
        }
        if (tableModel == null || tableModel.getColumns() == null || tableModel.getColumns().isEmpty()) {
            throw new IllegalStateException("TABLE asset requires non-empty tableModel");
        }
        return ResolvedAsset.builder()
                .assetType(MediaAssetType.TABLE)
                .tableModel(tableModel)
                .requiresUpload(false)
                .requiresCreation(true)
                .build();
    }

    private ResolvedAsset resolveWhiteboard(MediaAssetSpec spec) {
        String dsl = firstNonBlank(spec.getWhiteboardDsl(), spec.getSourceRef());
        if ((dsl == null || dsl.isBlank()) && spec.getGenerationPrompt() != null && !spec.getGenerationPrompt().isBlank()) {
            dsl = documentMermaidDslService.generateMermaidDsl(spec.getGenerationPrompt());
        }
        if (dsl == null || dsl.isBlank()) {
            throw new IllegalStateException("WHITEBOARD asset requires whiteboard DSL");
        }
        return ResolvedAsset.builder()
                .assetType(MediaAssetType.WHITEBOARD)
                .assetRef(dsl)
                .requiresUpload(false)
                .requiresCreation(false)
                .build();
    }

    private TableModel generateTableModel(String prompt) {
        if (chatModel == null) {
            return null;
        }
        String response = chatModel.call(
                "你是表格结构生成器。根据用户描述，输出 JSON 格式的表格结构：\n"
                + "{\"columns\":[\"列名1\",\"列名2\"],\"rows\":[[\"值1\",\"值2\"]]}\n"
                + "只输出合法 JSON，不要解释。\n用户描述：" + prompt
        );
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(response.trim());
            java.util.List<String> columns = mapper.convertValue(node.get("columns"),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
            java.util.List<java.util.List<String>> rows = mapper.convertValue(node.get("rows"),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.List<String>>>() {});
            return TableModel.builder().columns(columns).rows(rows).build();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
