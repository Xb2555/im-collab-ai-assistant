package com.lark.imcollab.harness.document.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.harness.document.support.DocumentExecutionGuard;
import com.lark.imcollab.harness.document.support.DocumentExecutionSupport;
import com.lark.imcollab.harness.document.workflow.DocumentStateKeys;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultDocumentExecutionServiceTest {

    @Test
    void executeClearsDoneFlagsWhenTaskInstructionChangedSinceCheckpoint() {
        CompiledGraph documentWorkflow = mock(CompiledGraph.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        ObjectProvider<RedissonClient> redissonProvider = mock(ObjectProvider.class);
        DocumentExecutionSupport support = mock(DocumentExecutionSupport.class);
        DefaultDocumentExecutionService service = new DefaultDocumentExecutionService(
                documentWorkflow,
                taskRepository,
                new DocumentExecutionGuard(redissonProvider),
                support
        );

        Map<String, Object> checkpointState = new HashMap<>();
        checkpointState.put(DocumentStateKeys.RAW_INSTRUCTION, "生成客户访谈纪要");
        checkpointState.put(DocumentStateKeys.CLARIFIED_INSTRUCTION, "生成客户访谈纪要");
        checkpointState.put(DocumentStateKeys.OUTLINE, "stale-outline");
        checkpointState.put(DocumentStateKeys.SECTION_DRAFTS, "stale-sections");
        checkpointState.put(DocumentStateKeys.DONE_OUTLINE, true);
        checkpointState.put(DocumentStateKeys.DONE_SECTIONS, true);
        checkpointState.put(DocumentStateKeys.DONE_REVIEW, true);
        StateSnapshot snapshot = StateSnapshot.of(
                new OverAllState(checkpointState),
                Checkpoint.builder()
                        .id("checkpoint-1")
                        .state(checkpointState)
                        .nodeId("write_doc_and_sync")
                        .nextNodeId("__END__")
                        .build(),
                RunnableConfig.builder().threadId("task-1").build(),
                OverAllState::new
        );
        when(documentWorkflow.stateOf(any(RunnableConfig.class))).thenReturn(Optional.of(snapshot));
        when(documentWorkflow.invoke(any(OverAllState.class), any(RunnableConfig.class))).thenReturn(Optional.empty());
        when(taskRepository.findById("task-1")).thenReturn(Optional.of(Task.builder()
                .taskId("task-1")
                .rawInstruction("生成客户访谈纪要")
                .clarifiedInstruction("生成客户访谈纪要\n补充说明：请用备用方案重试，先给简版")
                .build()));

        service.execute("task-1");

        ArgumentCaptor<OverAllState> stateCaptor = ArgumentCaptor.forClass(OverAllState.class);
        org.mockito.Mockito.verify(documentWorkflow).invoke(stateCaptor.capture(), any(RunnableConfig.class));
        Map<String, Object> state = stateCaptor.getValue().data();
        assertThat(state.get(DocumentStateKeys.CLARIFIED_INSTRUCTION))
                .isEqualTo("生成客户访谈纪要\n补充说明：请用备用方案重试，先给简版");
        assertThat(state)
                .doesNotContainKeys(
                        DocumentStateKeys.OUTLINE,
                        DocumentStateKeys.SECTION_DRAFTS,
                        DocumentStateKeys.DONE_OUTLINE,
                        DocumentStateKeys.DONE_SECTIONS,
                        DocumentStateKeys.DONE_REVIEW
                );
    }
}
