package com.lark.imcollab.harness.scene.c.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.lark.imcollab.harness.scene.c.service.SceneCWorkflowNodes;
import com.lark.imcollab.store.checkpoint.CheckpointSaverProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class SceneCWorkflowConfig {

    @Bean(name = "sceneCDocWorkflow")
    public CompiledGraph sceneCDocWorkflow(
            SceneCWorkflowNodes nodes,
            CheckpointSaverProvider checkpointSaverProvider) throws Exception {
        StateGraph graph = new StateGraph("scene-c-doc-workflow");
        graph.addNode("dispatch_doc_task", nodes::dispatchDocTask);
        graph.addNode("generate_outline", nodes::generateOutline);
        graph.addNode("generate_sections", nodes::generateSections);
        graph.addNode("review_doc", nodes::reviewDoc);
        graph.addNode("write_doc_and_sync", nodes::writeDocAndSync);
        graph.addEdge(StateGraph.START, "dispatch_doc_task");
        graph.addEdge("dispatch_doc_task", "generate_outline");
        graph.addEdge("generate_outline", "generate_sections");
        graph.addEdge("generate_sections", "review_doc");
        graph.addConditionalEdges("review_doc", nodes::routeAfterReview, Map.of(
                "write_doc_and_sync", "write_doc_and_sync",
                StateGraph.END, StateGraph.END
        ));
        graph.addConditionalEdges("write_doc_and_sync", nodes::routeAfterWrite, Map.of(
                StateGraph.END, StateGraph.END
        ));
        return graph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder()
                        .register(checkpointSaverProvider.getCheckpointSaver())
                        .build())
                .build());
    }
}
