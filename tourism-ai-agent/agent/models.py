"""
LangGraph Agent 数据模型定义
"""
from __future__ import annotations

from typing import TypedDict, Literal, NotRequired
from datetime import datetime
from pydantic import BaseModel, Field


# ── 对话消息 ─────────────────────────────────────────────────────────────────
MessageRole = Literal["system", "user", "assistant", "tool"]


class Message(TypedDict):
    role: MessageRole
    content: str
    tool_calls: NotRequired[list[dict]]
    tool_call_id: NotRequired[str]


# ── Agent 状态（LangGraph StateGraph 的核心）──────────────────────────────────
class AgentState(TypedDict):
    """完整描述 Agent 每次迭代的内存快照。"""
    messages: list[Message]
    step: str
    thinking_snapshot: str
    next_action: NotRequired[str]
    tool_result: NotRequired[dict]
    answer_chunks: NotRequired[list[str]]
    error: NotRequired[str]
    updated_at: NotRequired[str]


# ── Pydantic 请求/响应模型（FastAPI 使用）────────────────────────────────────
class ChatRequest(BaseModel):
    message: str = Field(..., description="用户本次提问")
    history: list[dict] = Field(default_factory=list, description="历史对话 [{role, content}]")
    session_id: str | None = Field(None, description="会话追踪 ID")


class ThinkingEvent(BaseModel):
    """SSE 思考过程事件"""
    type: Literal["thinking"] = "thinking"
    step: str = Field(..., description="阶段名，如 'retrieving' / 'analyzing'")
    content: str = Field(..., description="思考文本（Markdown 片段）")


class StreamEvent(BaseModel):
    """SSE 流式输出事件"""
    type: Literal["content", "done", "error", "predict"] = Field(...)
    content: str = Field("", description="内容或错误信息")
    meta: dict = Field(default_factory=dict, description="额外元数据")


class PredictNextRequest(BaseModel):
    """预测用户下一个问题请求"""
    conversation_history: list[dict] = Field(..., description="完整对话历史")


class PredictResponse(BaseModel):
    """预测响应"""
    predictions: list[str] = Field(default_factory=list, description="预测的可能问题列表")
    reason: str = Field(default="", description="预测理由")
    timestamp: str = Field(default_factory=lambda: datetime.now().isoformat())
