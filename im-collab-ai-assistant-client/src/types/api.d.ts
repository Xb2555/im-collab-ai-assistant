// src/types/api.d.ts

/**
 * 通用响应结构 
 */
export interface ApiResponse<T = unknown> {
  code: number;
  data: T;
  message: string;
}

// --- Auth 模块相关契约 ---

/**
 * 登录用户信息 
 */
export interface User {
  name: string;
  avatarUrl: string;
}

/**
 * code 换 token 请求体
 */
export interface AuthCallbackRequest {
  code: string;
  state: string;
}

/**
 * code 换 token 响应体 
 */
export interface AuthCallbackResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

//  以下为新增：Planner 任务调度模块相关契约
/**
 * 任务工作空间上下文 (对应前端精选的消息或时间范围)
 */
export interface WorkspaceContext {
  selectionType?: string; // 'MESSAGE' | 'DOCUMENT' | 'FILE'
  timeRange?: string;
  selectedMessages?: string[];
  selectedMessageIds?: string[];
  chatId?: string;
}

/**
 * 1. 创建任务规划请求体 (发起 Agent)
 */
export interface PlanRequest {
  rawInstruction?: string;      // 纯文本指令 (暂缺语音字段，使用文本兜底)
  taskId?: string;              // 首次创建时为空
  userFeedback?: string;
  workspaceContext?: WorkspaceContext;
}

/**
 * 任务卡片基础结构 (用于 Stepper 步骤条渲染)
 */
export interface PlanCardVO {
  cardId?: string;
  title?: string;
  description?: string;
  type?: string;
  status?: string;
  progress?: number;
  dependsOn?: string[];
}

/**
 * 任务可执行的操作权限 (用于驱动 GUI 按钮的亮起/置灰)
 */
export interface TaskActionVO {
  canConfirm?: boolean;
  canReplan?: boolean;
  canCancel?: boolean;
  canResume?: boolean;
  canInterrupt?: boolean;
  canRetry?: boolean;
}

/**
 * 核心任务预览视图 (场景 B 审查态核心数据)
 */
export interface PlanPreviewVO {
  taskId?: string;
  version?: number;
  planningPhase?: string;
  title?: string;
  summary?: string;
  cards?: PlanCardVO[];
  clarificationQuestions?: string[];
  clarificationAnswers?: string[];
  actions?: TaskActionVO;

  // ✨ 新增：后端最新契约补充的闲聊与任务状态字段
  accepted?: boolean;          // 是否被 Planner 接受立项
  runtimeAvailable?: boolean;  // 是否有底层的 Runtime 任务流
  transientReply?: boolean;    // 是否为瞬时闲聊回复
  assistantReply?: string;     // 大模型直接返回的闲聊文本
}

export interface RuntimeTaskVO {
  taskId: string;
  version: number;
  title: string;
  goal: string;
  status: string;
  currentStage: string;
  progress: number;
  needUserAction: boolean;
  riskFlags: string[];
  createdAt: string;
  updatedAt: string;
}

export interface RuntimeStepVO {
  stepId: string;
  name: string;
  type: string;
  status: string;
  inputSummary: string | null;
  outputSummary: string | null;
  progress: number;
  retryCount: number;
  assignedWorker: string | null;
  startedAt: string | null;
  endedAt: string | null;
}

export interface RuntimeArtifactVO {
  artifactId: string;
  type: string;
  title: string;
  url: string;
  preview: string | null;
  status: string;
  createdAt: string;
}

export interface RuntimeEventVO {
  eventId: string;
  version: number;
  type: string;
  stepId: string | null;
  message: string;
  createdAt: string;
}

export interface TaskRuntimeVO {
  task: RuntimeTaskVO;
  steps: RuntimeStepVO[];
  artifacts: RuntimeArtifactVO[];
  events: RuntimeEventVO[];
  actions: TaskActionVO;
}

/**
 * 2. 任务指令请求 (确认执行/重新规划/取消)
 */
export interface PlanCommandRequest {
  action?: string; // 'CONFIRM_EXECUTE' | 'REPLAN' | 'CANCEL'
  feedback?: string;
  version?: number; // ✨ 乐观锁：任务版本号，用于防冲突

  // 👇 新增：针对完成态任务修改产物的策略控制
  artifactPolicy?: 'AUTO' | 'EDIT_EXISTING' | 'CREATE_NEW' | 'KEEP_EXISTING_CREATE_NEW';
  targetArtifactId?: string; // 如果有多个产物，需明确指定修改哪一个
}

/**
 * ✨ 新增：文档迭代请求体 (对应 5.1. 执行文档迭代.md)
 */
export interface DocumentIterationRequest {
  taskId: string;
  docUrl: string;
  instruction: string; // 用户的自然语言修改指令
  workspaceContext?: WorkspaceContext; // 上下文依赖
}

/**
 * ✨ 新增：文档迭代响应体
 */
export interface DocumentIterationResponse {
  taskId: string;
  planningPhase: string;
  recognizedIntent: string;
  requireInput: boolean;
  preview: string;
  docUrl: string;
  modifiedBlocks: any[];
  summary: string;
  editPlan?: {
    intentType: string;
    semanticAction: string;
    targetTitle: string;
    generatedContent: string;
    toolCommandType: string;
    requiresApproval: boolean;
    riskLevel: string;
  };
}

/**
 * ✨ 新增：文档迭代审批请求体 (对应 5.2)
 */
export interface DocumentIterationApprovalRequest {
  action: 'APPROVE' | 'REJECT' | 'MODIFY';
  feedback?: string;
}

/**
 * 3. 任务恢复请求 (回答 LLM 的追问)
 */
export interface ResumeRequest {
  feedback?: string;
  replanFromRoot?: boolean;
}
