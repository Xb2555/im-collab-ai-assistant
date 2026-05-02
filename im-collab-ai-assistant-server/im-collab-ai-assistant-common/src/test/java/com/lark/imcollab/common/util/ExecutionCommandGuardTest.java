package com.lark.imcollab.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionCommandGuardTest {

    @Test
    void acceptsExplicitExecutionAfterImLabelPrefix() {
        assertThat(ExecutionCommandGuard.isExplicitExecutionRequest("【IM闭环F4】开始执行")).isTrue();
        assertThat(ExecutionCommandGuard.isExplicitExecutionRequest("@飞书IM-test 开始执行")).isTrue();
    }

    @Test
    void rejectsMetaPromptAboutExecution() {
        assertThat(ExecutionCommandGuard.isExplicitExecutionRequest("回复开始执行")).isFalse();
        assertThat(ExecutionCommandGuard.isExplicitExecutionRequest("完整计划给我看看")).isFalse();
    }
}
