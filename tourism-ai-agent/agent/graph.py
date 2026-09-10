"""
LangGraph 图构建 - 定义 Agent 的状态机流转逻辑
节点: init -> llm -> stream_answer -> predict_next
条件边: llm 完成流式输出后进入预测阶段
"""
from __future__ import annotations

from langgraph.graph import StateGraph, START, END

from .models import AgentState
from .nodes import (
    init_node,
    llm_node,
    stream_answer_node,
    predict_next_node,
)


def build_agent_graph():
    """
    构建完整的 LangGraph Agent 状态机。

    流程图:
        ┌─────────┐     ┌──────────┐     ┌────────────────┐     ┌──────────────┐
        │  START   │──▶│  init    │──▶│    llm         │──▶│ stream_answer │
        └─────────┘     └──────────┘     └────────────────┘     └───────┬──────┘
                                                                         │
                                                                         ▼
                                                                   ┌──────────────┐
                                                                   │ predict_next │
                                                                   └───────┬──────┘
                                                                           │
                                                                           ▼
                                                                        ┌──────┐
                                                                        │ END  │
                                                                        └──────┘
    """
    # 创建状态图
    workflow = StateGraph(AgentState)

    # 注册所有节点
    workflow.add_node("init", init_node)          # 初始化：加载 RAG 数据 + 构建消息
    workflow.add_node("llm", llm_node)            # LLM 推理：调用 GLM-4-Flash
    workflow.add_node("stream_answer", stream_answer_node)  # 分块准备
    workflow.add_node("predict_next", predict_next_node)    # 智能预测

    # 定义边（流转逻辑）
    workflow.add_edge(START, "init")
    workflow.add_edge("init", "llm")
    workflow.add_edge("llm", "stream_answer")
    workflow.add_edge("stream_answer", "predict_next")
    workflow.add_edge("predict_next", END)

    # 编译图（带检查点支持，用于会话记忆）
    graph = workflow.compile()

    return graph


# 全局图实例（应用启动时创建一次）
agent_graph = build_agent_graph()
