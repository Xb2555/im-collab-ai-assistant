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
}

/**
 * 核心任务预览视图 (场景 B 审查态核心数据)
 */
export interface PlanPreviewVO {
  taskId?: string;
  planningPhase?: string;
  title?: string;
  summary?: string;
  cards?: PlanCardVO[];
  clarificationQuestions?: string[];
  clarificationAnswers?: string[];
  actions?: TaskActionVO;
}

/**
 * 2. 任务指令请求 (确认执行/重新规划/取消)
 */
export interface PlanCommandRequest {
  action?: string; // 'CONFIRM_EXECUTE' | 'REPLAN' | 'CANCEL'
  feedback?: string;
  version?: number; // ✨ 乐观锁：任务版本号，用于防冲突
}

/**
 * 3. 任务恢复请求 (回答 LLM 的追问)
 */
export interface ResumeRequest {
  feedback?: string;
  replanFromRoot?: boolean;
}