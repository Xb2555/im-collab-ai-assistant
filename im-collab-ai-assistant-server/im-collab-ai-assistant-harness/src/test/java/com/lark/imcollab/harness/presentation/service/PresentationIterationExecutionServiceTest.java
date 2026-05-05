package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.model.dto.PresentationIterationRequest;
import com.lark.imcollab.skills.lark.slides.LarkSlidesFetchResult;
import com.lark.imcollab.skills.lark.slides.LarkSlidesTool;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PresentationIterationExecutionServiceTest {

    @Test
    void replacesRequestedSlideTitleAndVerifiesResult() {
        LarkSlidesTool slidesTool = mock(LarkSlidesTool.class);
        when(slidesTool.fetchPresentation("slides-1"))
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("""
                                <presentation><slide id="s1"><data><shape id="b1" type="text" topLeftX="80" topLeftY="80" width="800" height="120"><content textType="title"><p>旧标题</p></content></shape></data></slide><slide id="s2"><data><shape id="b2" type="text" topLeftX="90" topLeftY="90" width="700" height="100"><content textType="title"><p>第二页</p></content></shape></data></slide></presentation>
                                """)
                        .build())
                .thenReturn(LarkSlidesFetchResult.builder()
                        .presentationId("slides-1")
                        .xml("<presentation><slide id=\"s2\"><data><shape id=\"b2\"><content><p>新采购评审结论</p></content></shape></data></slide></presentation>")
                        .build());
        PresentationIterationExecutionService service = new PresentationIterationExecutionService(slidesTool);

        var result = service.edit(PresentationIterationRequest.builder()
                .taskId("task-1")
                .artifactId("artifact-1")
                .presentationId("slides-1")
                .instruction("把第二页标题改成新采购评审结论")
                .build());

        ArgumentCaptor<List<Map<String, Object>>> partsCaptor = ArgumentCaptor.forClass(List.class);
        verify(slidesTool).replaceSlide(eq("slides-1"), eq("s2"), partsCaptor.capture());
        assertThat(result.getModifiedSlides()).containsExactly("s2");
        assertThat(partsCaptor.getValue().get(0))
                .containsEntry("action", "block_replace")
                .containsEntry("block_id", "b2");
        assertThat((String) partsCaptor.getValue().get(0).get("replacement")).contains("新采购评审结论");
    }
}
