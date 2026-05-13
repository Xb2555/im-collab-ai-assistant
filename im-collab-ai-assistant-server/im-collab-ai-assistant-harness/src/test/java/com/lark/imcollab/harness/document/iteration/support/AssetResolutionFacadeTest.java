package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.MediaAssetSpec;
import com.lark.imcollab.common.model.enums.MediaAssetSourceType;
import com.lark.imcollab.common.model.enums.MediaAssetType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetResolutionFacadeTest {

    @Test
    void imageAttachmentSourceIsReturnedDirectly() {
        DocumentImageSearchService imageSearchService = mock(DocumentImageSearchService.class);
        DocumentMermaidDslService mermaidDslService = mock(DocumentMermaidDslService.class);
        AssetResolutionFacade facade = new AssetResolutionFacade(imageSearchService, mermaidDslService);

        var resolved = facade.resolve(MediaAssetSpec.builder()
                .assetType(MediaAssetType.IMAGE)
                .sourceType(MediaAssetSourceType.ATTACHMENT)
                .sourceRef("https://cos.example.com/a.png")
                .build());

        assertThat(resolved.getAssetRef()).isEqualTo("https://cos.example.com/a.png");
    }

    @Test
    void imageSearchUsesDocumentImageSearchService() {
        DocumentImageSearchService imageSearchService = mock(DocumentImageSearchService.class);
        DocumentMermaidDslService mermaidDslService = mock(DocumentMermaidDslService.class);
        AssetResolutionFacade facade = new AssetResolutionFacade(imageSearchService, mermaidDslService);
        when(imageSearchService.searchFirstImageUrl("search query")).thenReturn("https://images.pexels.com/photo.jpg");

        var resolved = facade.resolve(MediaAssetSpec.builder()
                .assetType(MediaAssetType.IMAGE)
                .sourceType(MediaAssetSourceType.SEARCH)
                .generationPrompt("search query")
                .build());

        assertThat(resolved.getAssetRef()).isEqualTo("https://images.pexels.com/photo.jpg");
    }

    @Test
    void whiteboardPromptIsConvertedToDsl() {
        DocumentImageSearchService imageSearchService = mock(DocumentImageSearchService.class);
        DocumentMermaidDslService mermaidDslService = mock(DocumentMermaidDslService.class);
        AssetResolutionFacade facade = new AssetResolutionFacade(imageSearchService, mermaidDslService);
        when(mermaidDslService.generateMermaidDsl("diagram prompt")).thenReturn("flowchart TD;A-->B");

        var resolved = facade.resolve(MediaAssetSpec.builder()
                .assetType(MediaAssetType.WHITEBOARD)
                .generationPrompt("diagram prompt")
                .build());

        assertThat(resolved.getAssetRef()).isEqualTo("flowchart TD;A-->B");
    }

    @Test
    void missingImageSourceFailsFast() {
        DocumentImageSearchService imageSearchService = mock(DocumentImageSearchService.class);
        DocumentMermaidDslService mermaidDslService = mock(DocumentMermaidDslService.class);
        AssetResolutionFacade facade = new AssetResolutionFacade(imageSearchService, mermaidDslService);

        assertThatThrownBy(() -> facade.resolve(MediaAssetSpec.builder().assetType(MediaAssetType.IMAGE).build()))
                .isInstanceOf(IllegalStateException.class);
    }
}
