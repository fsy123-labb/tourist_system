"""
FastAPI 主服务 - 提供 SSE 流式对话接口 + 预测接口
兼容原 Java Spring Boot 前端调用：POST /api/ai/chat
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
from contextlib import asynccontextmanager
from datetime import datetime
from typing import AsyncGenerator

# Fix Windows GBK encoding issue for emojis
if sys.platform == 'win32':
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

# 添加项目根目录到路径（解决相对导入问题）
sys.path.insert(0, os.path.dirname(__file__))

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from agent.graph import agent_graph
from agent.models import ChatRequest, ThinkingEvent, StreamEvent, AgentState
from agent.nodes import THINKING_STEPS
from agent.db import get_settings

# ── 应用初始化 ───────────────────────────────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    app.state.settings = settings

    # 启动时测试数据库连接和 RAG 上下文
    try:
        from agent.db import fetch_rag_context
        ctx = fetch_rag_context()
        print(f"✅ 数据库连接正常，RAG 上下文已加载 ({len(ctx)} 字符)")
    except Exception as e:
        print(f"⚠️  数据库连接测试失败: {e}")

    print(f"🤖 Tourism AI Agent (LangGraph) 启动成功")
    print(f"   模型: {settings.ai_model}")
    print(f"   API:  {settings.ai_api_url}")
    yield

    print("👋 Tourism AI Agent 已停止")


app = FastAPI(
    title="Tourism AI Agent (LangGraph)",
    description="基于 LangGraph + FastAPI 的 AI 旅游助手，支持流式回答和思考过程展示",
    version="2.0.0",
    lifespan=lifespan,
)

# CORS 配置（允许前端跨域访问）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── SSE 事件生成器 ───────────────────────────────────────────────────────────

async def sse_event(event: dict) -> str:
    """将事件序列化为 SSE 格式"""
    return f"data: {json.dumps(event, ensure_ascii=False)}\n\n"


async def think_stream(thinking_text: str) -> AsyncGenerator[str, None]:
    """
    逐步展示思考过程（每次推送一个阶段）。
    对应需求：不要将思考过程直接添加到对话框，而是阶段性展示。
    """
    for step_name, (icon, text) in THINKING_STEPS.items():
        if step_name in ("completed",):
            continue
        yield await sse_event({
            "type": "thinking",
            "step": step_name,
            "content": text,
        })
        await asyncio.sleep(0.3)  # 思考间隔，增加"真实感"


async def answer_stream(answer_chunks: list[str]) -> AsyncGenerator[str, None]:
    """流式推送回答内容（打字机效果）"""
    for chunk in answer_chunks:
        yield await sse_event({
            "type": "content",
            "content": chunk,
        })
        await asyncio.sleep(0.05)  # 类打字机效果


# ══════════════════════════════════════════════════════════════════════════════
# 核心接口
# ══════════════════════════════════════════════════════════════════════════════

@app.post("/api/ai/chat")
async def chat(request: ChatRequest):
    """
    AI 对话接口（SSE 流式）

    请求体:
        message: str            - 用户本次提问
        history: list[dict]     - 历史对话 [{role, content}]
        session_id: str         - 可选，会话追踪

    SSE 响应流:
        data: {"type":"thinking","step":"...","content":"..."}
        data: {"type":"content","content":"..."}
        data: {"type":"done","meta":{...}}
    """
    if not request.message.strip():
        raise HTTPException(status_code=400, detail="message 不能为空")

    # 构建初始状态
    initial_messages = []

    # 追加历史对话
    history = request.history or []
    for h in history:
        role = h.get("role", "user")
        content = h.get("content", "")
        if content and role in ("user", "assistant", "system"):
            initial_messages.append({"role": role, "content": content})

    # 添加当前消息
    initial_messages.append({"role": "user", "content": request.message})

    # 初始状态
    state = AgentState(
        messages=initial_messages,
        step="pending",
        thinking_snapshot="",
        updated_at=datetime.now().isoformat(),
    )

    async def event_generator() -> AsyncGenerator[str, None]:
        try:
            # ── 阶段1: 执行 LangGraph 图（后台运行）─────────────────────
            result = await agent_graph.ainvoke(state)

            thinking_text = result.get("thinking_snapshot", "")
            answer_chunks = result.get("answer_chunks", [])
            predictions = result.get("predictions", [])
            error = result.get("error")

            # ── 阶段2: 推送思考过程事件 ─────────────────────────────────
            if thinking_text and not error:
                async for event in think_stream(thinking_text):
                    yield event
                await asyncio.sleep(0.2)

            # ── 阶段3: 推送回答内容（流式）──────────────────────────────
            if error:
                error_event = {"type": "error", "content": f"AI 服务异常: {error}"}
                yield await sse_event(error_event)
                yield await sse_event({"type": "done"})
                return

            if answer_chunks:
                async for event in answer_stream(answer_chunks):
                    yield event
                await asyncio.sleep(0.1)

            # ── 阶段4: 推送预测结果 ─────────────────────────────────────
            if predictions:
                pred_event = {
                    "type": "predict",
                    "content": json.dumps(predictions, ensure_ascii=False),
                }
                yield await sse_event(pred_event)

            # ── 阶段5: 发送完成信号 ─────────────────────────────────────
            done_event = {
                "type": "done",
                "content": "",
                "meta": {
                    "step": result.get("step"),
                    "timestamp": datetime.now().isoformat(),
                    "token_count": len(answer_chunks),
                }
            }
            yield await sse_event(done_event)

        except Exception as e:
            error_event = StreamEvent(type="error", content=str(e))
            yield await sse_event(error_event.model_dump())
            yield await sse_event({"type": "done"})

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
        }
    )


@app.post("/api/ai/predict")
async def predict_next_question(request: dict):
    """
    预测用户下一个需求接口（非流式，用于批量分析）
    """
    history = request.get("conversation_history", [])
    if len(history) < 2:
        return {"predictions": [], "reason": "对话历史不足，无法预测"}

    state = AgentState(
        messages=[{"role": "user", "content": json.dumps(history, ensure_ascii=False)}],
        step="predicting",
        thinking_snapshot="",
        updated_at=datetime.now().isoformat(),
    )
    result = await agent_graph.ainvoke(state)
    predictions = result.get("predictions", [])

    return {
        "predictions": predictions,
        "reason": "基于对话历史和景点偏好分析",
        "timestamp": datetime.now().isoformat(),
    }


@app.get("/api/ai/health")
async def health():
    """健康检查接口"""
    s = get_settings()
    return {
        "status": "healthy",
        "service": "Tourism AI Agent",
        "version": "2.0.0",
        "model": s.ai_model,
        "api_url": s.ai_api_url,
        "db_connected": True,
        "timestamp": datetime.now().isoformat(),
    }


@app.get("/api/ai/stats")
async def stats():
    """获取 Agent 统计信息"""
    from agent.db import db_session, text
    with db_session() as session:
        spot_count = session.execute(text("SELECT COUNT(*) FROM scenic_spot")).scalar()
        city_count = session.execute(text("SELECT COUNT(*) FROM scenic_city")).scalar()
        return {
            "total_spots": spot_count,
            "total_cities": city_count,
            "agent_version": "2.0.0",
            "framework": "LangGraph + FastAPI",
        }
