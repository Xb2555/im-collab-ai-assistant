package com.lark.imcollab.harness.presentation.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.lark.imcollab.harness.presentation.service.PresentationWorkflowNodes;
import com.lark.imcollab.store.checkpoint.CheckpointSaverProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PresentationWorkflowConfig {

    @Bean(name = "presentationWorkflow")
    public CompiledGraph presentationWorkflow(
            PresentationWorkflowNodes nodes,
            CheckpointSaverProvider checkpointSaverProvider) throws Exception {
        StateGraph graph = new StateGraph(
                "presentation-slides-workflow",
                new KeyStrategyFactoryBuilder()
                        .defaultStrategy(KeyStrategy.REPLACE)
                        .build()
        );
        graph.addNode("dispatch_presentation_task", nodes::dispatchPresentationTask);
        graph.addNode("build_storyline", nodes::buildStoryline);
        graph.addNode("generate_slide_outline", nodes::generateSlideOutline);
        graph.addNode("generate_slide_xml", nodes::generateSlideXml);
        graph.addNode("validate_slide_xml", nodes::validateSlideXml);
        graph.addNode("review_presentation", nodes::reviewPresentation);
        graph.addNode("write_slides_and_sync", nodes::writeSlidesAndSync);
        graph.addEdge(StateGraph.START, "dispatch_presentation_task");
        graph.addEdge("dispatch_presentation_task", "build_storyline");
        graph.addEdge("build_storyline", "generate_slide_outline");
        graph.addEdge("generate_slide_outline", "generate_slide_xml");
        graph.addEdge("generate_slide_xml", "validate_slide_xml");
        graph.addEdge("validate_slide_xml", "review_presentation");
        graph.addEdge("review_presentation", "write_slides_and_sync");
        graph.addEdge("write_slides_and_sync", StateGraph.END);
        return graph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder()
                        .register(checkpointSaverProvider.getCheckpointSaver())
                        .build())
                .build());
    }
}
