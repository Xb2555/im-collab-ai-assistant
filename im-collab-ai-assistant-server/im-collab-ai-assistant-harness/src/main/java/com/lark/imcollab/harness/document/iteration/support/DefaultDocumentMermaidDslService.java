package com.lark.imcollab.harness.document.iteration.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class DefaultDocumentMermaidDslService implements DocumentMermaidDslService {

    private static final Set<String> SUPPORTED_MERMAID_HEADERS = Set.of(
            "flowchart",
            "graph",
            "sequencediagram",
            "statediagram",
            "statediagram-v2",
            "erdiagram"
    );

    private final ChatModel chatModel;

    public DefaultDocumentMermaidDslService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String generateMermaidDsl(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("Mermaid prompt is blank");
        }
        String diagramPlan = resolveDiagramPlan(prompt);
        String response = chatModel.call(buildDiagramPrompt(diagramPlan, prompt));
        String text = extractMermaid(response);
        if (text.isBlank() || !isMermaidValid(text, diagramPlan)) {
            return defaultMermaid(diagramPlan);
        }
        return text;
    }

    private String resolveDiagramPlan(String prompt) {
        String lower = prompt == null ? "" : prompt.toLowerCase();
        if (lower.contains("数据流转图") || lower.contains("数据流") || lower.contains("sequence")) {
            return "DATA_FLOW";
        }
        if (lower.contains("状态图")) {
            return "STATE";
        }
        if (lower.contains("时序图")) {
            return "SEQUENCE";
        }
        return "CONTEXT";
    }

    private String buildDiagramPrompt(String diagramPlan, String prompt) {
        String requiredHeader = switch (diagramPlan) {
            case "SEQUENCE" -> "sequenceDiagram";
            case "STATE" -> "stateDiagram-v2";
            case "DATA_FLOW" -> "sequenceDiagram";
            case "CONTEXT" -> "flowchart TB";
            default -> "flowchart TB";
        };
        String styleReference = switch (diagramPlan) {
            case "DATA_FLOW" -> """
                    参考风格：
                    sequenceDiagram
                        participant U as User (Frontend/IM)
                        participant A as Agent
                        participant P as Planner
                        participant D as Doc Tool
                        participant S as Sync Layer
                        U->>A: 提交任务或编辑指令
                        A->>P: 解析意图并规划步骤
                        P->>D: 生成/更新文档与图表
                        D-->>S: 产物与状态回写
                        S-->>U: 多端同步结果
                    """.strip();
            case "CONTEXT" -> """
                    参考风格：
                    flowchart TB
                        subgraph A[交互入口层]
                            Entry[IM / GUI 双入口<br/>接收指令与反馈]
                        end
                        subgraph B[Agent 决策层]
                            Agent[Agent 主驾驶<br/>理解意图、拆解任务、调度执行]
                        end
                        subgraph C[执行与产物层]
                            Doc[文档 / 白板]
                            Deck[PPT / Canvas]
                            Sync[多端同步与归档]
                        end
                        Entry --> Agent
                        Agent --> Doc
                        Agent --> Deck
                        Doc --> Sync
                        Deck --> Sync
                    """.strip();
            default -> "";
        };
        return """
                请根据以下任务生成一张 Mermaid 图，只返回 Mermaid 源码，不要解释，不要 Markdown 围栏。
                第一行必须严格以 `%s` 开头。
                图类型：%s
                需求与上下文：%s
                参考风格：%s
                额外规则：
                1. 如果图类型是 flowchart，请优先使用 TB 方向。
                2. 如果图类型是 sequenceDiagram，请显式声明 participant，并用箭头表达调用方向。
                3. 节点命名必须贴合当前章节主题，不要输出泛泛的 A/B/C 或 Node1/Node2。
                4. 只允许使用以下 Mermaid 顶层图类型：flowchart、graph、sequenceDiagram、stateDiagram-v2、erDiagram。
                5. 严禁输出 usecaseDiagram、journey、gantt、classDiagram、pie、mindmap、timeline 或其他未列出的图类型。
                6. 架构图优先表达模块边界、分层和依赖关系；数据流转图优先表达参与者、调用顺序、输入输出与状态回写。
                """.formatted(requiredHeader, diagramPlan, prompt, styleReference);
    }

    private String extractMermaid(String text) {
        String sanitized = text == null ? "" : text.trim();
        if (sanitized.startsWith("```")) {
            int start = sanitized.indexOf('\n');
            if (start >= 0) {
                sanitized = sanitized.substring(start + 1).trim();
            }
            if (sanitized.endsWith("```")) {
                sanitized = sanitized.substring(0, sanitized.length() - 3).trim();
            }
        }
        return normalizeSupportedMermaid(sanitized);
    }

    private String normalizeSupportedMermaid(String mermaid) {
        String normalized = mermaid == null ? "" : mermaid.trim();
        if (normalized.isBlank()) {
            return "";
        }
        String firstLine = normalized.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("")
                .toLowerCase();
        return SUPPORTED_MERMAID_HEADERS.stream().anyMatch(firstLine::startsWith) ? normalized : "";
    }

    private boolean isMermaidValid(String mermaid, String diagramPlan) {
        String lower = normalizeSupportedMermaid(mermaid).toLowerCase();
        if (lower.isBlank()) {
            return false;
        }
        return switch (diagramPlan) {
            case "SEQUENCE" -> lower.startsWith("sequencediagram");
            case "STATE" -> lower.startsWith("statediagram");
            case "DATA_FLOW" -> lower.startsWith("sequencediagram");
            case "CONTEXT" -> lower.startsWith("flowchart") || lower.startsWith("graph");
            default -> lower.startsWith("flowchart") || lower.startsWith("graph") || lower.startsWith("sequencediagram");
        };
    }

    private String defaultMermaid(String diagramPlan) {
        return switch (diagramPlan) {
            case "DATA_FLOW" -> """
                    sequenceDiagram
                        participant User
                        participant Agent
                        participant Planner
                        participant DocTool
                        participant Sync
                        User->>Agent: 发起任务/编辑请求
                        Agent->>Planner: 解析意图并生成计划
                        Planner->>DocTool: 执行文档或白板更新
                        DocTool-->>Sync: 回写产物与状态
                        Sync-->>User: 同步最新结果
                    """.strip();
            case "STATE" -> """
                    stateDiagram-v2
                        [*] --> PLANNING
                        PLANNING --> PLAN_READY
                        PLAN_READY --> EXECUTING
                        EXECUTING --> COMPLETED
                    """.strip();
            case "SEQUENCE" -> """
                    sequenceDiagram
                        participant User
                        participant Agent
                        participant Tool
                        User->>Agent: 提交请求
                        Agent->>Tool: 调用执行
                        Tool-->>Agent: 返回结果
                        Agent-->>User: 输出产物
                    """.strip();
            default -> """
                    flowchart TB
                        subgraph Entry[交互入口]
                            IM[IM / GUI]
                        end
                        subgraph AgentLayer[Agent 决策层]
                            Agent[Agent 主驾驶]
                            Planner[Planner / Orchestrator]
                        end
                        subgraph Output[产物层]
                            Doc[文档 / 白板]
                            Deck[PPT / Canvas]
                        end
                        IM --> Agent
                        Agent --> Planner
                        Planner --> Doc
                        Planner --> Deck
                    """.strip();
        };
    }
}
