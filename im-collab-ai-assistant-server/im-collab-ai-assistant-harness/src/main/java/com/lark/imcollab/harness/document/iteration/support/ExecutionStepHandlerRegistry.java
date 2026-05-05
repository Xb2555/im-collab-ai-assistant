package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ExecutionStepHandlerRegistry {

    private final Map<String, ExecutionStepHandler> handlers;

    public ExecutionStepHandlerRegistry(List<ExecutionStepHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(ExecutionStepHandler::stepType, Function.identity()));
    }

    public void dispatch(ExecutionStep step, String docRef, RichContentExecutionContext ctx) {
        ExecutionStepHandler handler = handlers.get(step.getStepType());
        if (handler == null) {
            throw new IllegalStateException("No handler for step type: " + step.getStepType());
        }
        handler.handle(step, docRef, ctx);
    }
}
