package com.lark.imcollab.harness.document.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.lark.imcollab.harness.document.service.DocumentWorkflowNodes;
import com.lark.imcollab.store.checkpoint.CheckpointSaverProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class DocumentWorkflowConfig {

    @Bean(name = "documentWorkflow")
    public CompiledGraph documentWorkflow(
            DocumentWorkflowNodes nodes,
            CheckpointSaverProvider checkpointSaverProvider) throws Exception {
        StateGraph graph = new StateGraph(
                "document-doc-workflow",
                new KeyStrategyFactoryBuilder()
                        .defaultStrategy(KeyStrategy.REPLACE)
                        .build()
        );
        graph.addNode("dispatch_doc_task", nodes::dispatchDocTask);
        graph.addNode("generate_outline", nodes::generateOutline);
        graph.addNode("generate_sections", nodes::generateSections);
        graph.addNode("plan_diagrams", nodes::planDiagrams);
        graph.addNode("generate_mermaid", nodes::generateMermaid);
        graph.addNode("validate_mermaid", nodes::validateMermaid);
        graph.addNode("review_doc", nodes::reviewDoc);
        graph.addNode("compose_doc", nodes::composeDoc);
        graph.addNode("write_doc_and_sync", nodes::writeDocAndSync);
        graph.addEdge(StateGraph.START, "dispatch_doc_task");
        graph.addEdge("dispatch_doc_task", "generate_outline");
        graph.addEdge("generate_outline", "generate_sections");
        graph.addEdge("generate_sections", "plan_diagrams");
        graph.addEdge("plan_diagrams", "generate_mermaid");
        graph.addEdge("generate_mermaid", "validate_mermaid");
        graph.addEdge("validate_mermaid", "review_doc");
        graph.addConditionalEdges("review_doc", nodes::routeAfterReview, Map.of(
                "compose_doc", "compose_doc",
                "write_doc_and_sync", "write_doc_and_sync",
                StateGraph.END, StateGraph.END
        ));
        graph.addEdge("compose_doc", "write_doc_and_sync");
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
