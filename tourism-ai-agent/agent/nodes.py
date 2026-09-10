"""
Agent 节点实现 - LangGraph 图结构中的各个处理步骤
对应原始 Java AiServiceImpl 的逻辑，但拆分为独立的可组合节点
"""
from __future__ import annotations

import json
import os
import time
from datetime import datetime

from openai import AsyncOpenAI
from langchain_core.messages import (
    HumanMessage, SystemMessage, AIMessage, ToolMessage,
)

from .models import AgentState, Message
from .db import fetch_rag_context, fetch_relevant_spots, get_settings

# ── 大模型客户端（异步，复用连接）────────────────────────────────────────────
_settings = get_settings()
_client = AsyncOpenAI(
    api_key=_settings.ai_api_key,
    base_url=_settings.ai_api_url.rstrip("/").replace("/chat/completions", ""),
)

# 思考过程阶段描述映射
THINKING_STEPS = {
    "init": ("🔄 初始化", "正在加载旅游助手知识体系..."),
    "retrieving": ("🔍 数据检索", "正在从 678 个景点数据库中检索相关信息..."),
    "analyzing": ("🧠 分析推理", "分析景点数据，结合用户意图进行推理..."),
    "generating": ("✍️ 生成回答", "正在组织 Markdown 格式的回答内容..."),
    "predicting": ("💡 预测需求", "分析您的兴趣，预测您可能想了解的其他景点..."),
    "tool_using": ("🛠️ 工具调用", "正在查询景点详细信息..."),
}


def _build_system_prompt(rag_context: str) -> str:
    """
    构建完整系统提示词（对应 Java buildSystemPrompt 方法）。
    包含角色定义、数据边界规则、回答格式规范。
    """
    return f"""# 🤖 角色定义

你是**"全国旅游景点信息管理系统"的AI旅游助手小旅**，由智谱GLM-4-Flash驱动。

## 核心定位
- 你是这个系统的智能窗口，所有回答必须**基于系统数据库中的真实数据**
- 你服务的用户是这个系统的管理员和普通游客
- 你的数据来源于项目内置的景点数据库，共 **678个景点**，覆盖 **225个城市**

---

## ⚠️ 数据边界规则（严格执行）

1. **你必须以下面"【系统数据库】"部分提供的真实数据为唯一依据回答问题**
2. **严禁编造、推测或引入数据库之外的景点名称、价格、评分等具体信息**
3. **对于数据库中不存在的景点，明确告知用户"该景点不在系统数据范围内"**
4. **可以结合通用旅游常识做宏观建议，但必须标注"这是通用建议，非系统数据"**
5. **所有涉及具体数值的回答，必须与数据库完全一致**

---

## 📝 回答格式规范

你的回答分为**两个部分**，必须严格按以下结构输出：

### 第一部分：思考过程（以固定标记开头）

在给出正式回答之前，你必须先输出思考过程：

```
### 💭 思考中...

- **理解问题**：用户问的是...
- **数据检索**：我需要从数据库中查找...
- **分析推理**：根据数据，我发现...
- **生成回答**：我将用表格/列表/要点来回答...
```

思考过程输出完毕后，紧接着输出正式回答。

### 第二部分：正式回答（Markdown格式）

- 使用 `#` / `##` / `###` 标题分层组织内容
- 推荐类问题用 **有序列表** 或 **表格** 展示
- 关键信息（价格、评分、星级）用 **加粗** 强调
- 重要提示用 `>` 引用块
- 总字数控制在 **300-500字**

---

## 📊 系统数据库
{rag_context}
"""


def _convert_messages(state_messages: list[Message]) -> list:
    """将内部消息格式转换为 LangChain/OpenAI 格式"""
    result = []
    for msg in state_messages:
        role = msg["role"]
        if role == "system":
            result.append(SystemMessage(content=msg["content"]))
        elif role == "user":
            result.append(HumanMessage(content=msg["content"]))
        elif role == "assistant":
            result.append(AIMessage(content=msg["content"]))
        elif role == "tool":
            result.append(ToolMessage(content=msg["content"], tool_call_id=msg.get("tool_call_id", "")))
    return result


def _to_internal_format(msg) -> dict:
    """将 OpenAI 返回的消息转回内部格式"""
    role = msg.role if hasattr(msg, 'role') else msg.get('role', 'assistant')
    content = msg.content if hasattr(msg, 'content') else msg.get('content', '')
    return {"role": role, "content": content}


# ══════════════════════════════════════════════════════════════════════════════
# LangGraph 节点实现
# ══════════════════════════════════════════════════════════════════════════════

async def init_node(state: AgentState) -> AgentState:
    """
    初始化节点：加载系统提示词 + 构建历史上下文
    对应 Java: buildMessages()
    """
    rag_context = fetch_rag_context()
    system_prompt = _build_system_prompt(rag_context)

    # 构建完整消息列表（系统提示 + 历史 + 当前问题）
    messages = [{"role": "system", "content": system_prompt}]

    # 追加历史对话（最多保留6轮，与 Java 一致）
    history = state.get("messages", [])
    if len(history) > 6:
        history = history[-6:]
    messages.extend(history)

    return {
        **state,
        "messages": messages,
        "step": "init",
        "thinking_snapshot": THINKING_STEPS["init"][1],
        "updated_at": datetime.now().isoformat(),
    }


async def llm_node(state: AgentState) -> AgentState:
    """
    LLM 推理节点：调用大模型生成回答
    对应 Java: 调用智谱 API
    """
    messages = state["messages"]

    # 转换为 OpenAI 格式
    openai_messages = []
    for msg in messages:
        openai_messages.append({"role": msg["role"], "content": msg["content"]})

    try:
        # 调用 GLM-4-Flash（OpenAI 兼容协议）
        response = await _client.chat.completions.create(
            model=_settings.ai_model,
            messages=openai_messages,
            stream=False,           # 先获取完整回答用于思考过程提取
            temperature=0.7,
            max_tokens=2048,
        )

        assistant_msg = response.choices[0].message
        content = assistant_msg.content or ""

        # 提取思考过程（解析 ### 💭 思考中... 标记）
        thinking_text = ""
        answer_text = content

        if "### 💭 思考中..." in content:
            parts = content.split("### 💭 思考中...", 1)
            if len(parts) == 2:
                thinking_text = parts[0].strip()
                answer_text = parts[1].strip()

        return {
            **state,
            "messages": state["messages"] + [
                {"role": "assistant", "content": content}
            ],
            "step": "completed",
            "thinking_snapshot": thinking_text or THINKING_STEPS["analyzing"][1],
            "next_action": "stream_answer",
            "answer_chunks": [answer_text],  # 完整回答作为一块
        }

    except Exception as e:
        error_msg = f"AI服务调用失败: {str(e)}"
        return {
            **state,
            "step": "error",
            "error": error_msg,
        }


async def stream_answer_node(state: AgentState) -> AgentState:
    """
    流式输出节点：将 LLM 生成的回答分块推送
    对应 Java: 逐行解析 SSE 并推给前端
    """
    full_answer = "".join(state.get("answer_chunks", []))

    # 按句子/段落分块（每 chunk 约 50-100 字符）
    chunks = []
    current_chunk = ""
    for char in full_answer:
        current_chunk += char
        if len(current_chunk) >= 50 and char in "。！？\n":
            chunks.append(current_chunk.strip())
            current_chunk = ""
    if current_chunk:
        chunks.append(current_chunk.strip())

    return {
        **state,
        "step": "streaming",
        "answer_chunks": chunks,
        "thinking_snapshot": THINKING_STEPS["generating"][1],
    }


async def predict_next_node(state: AgentState) -> AgentState:
    """
    智能预测节点：基于对话历史预测用户下一个可能的问题
    对应原始 Java 中没有的功能
    """
    # 只提取 user/assistant 消息（排除巨大的 system prompt）
    recent_msgs = [m for m in state["messages"][-6:] if m.get("role") in ("user", "assistant")]
    conversation_summary = "\n".join(
        [f"[{m['role']}]: {m['content'][:150]}" for m in recent_msgs]
    )

    prediction_prompt = f"""基于以下旅游对话历史，预测用户接下来可能想了解的内容（最多3个方向）：

{conversation_summary}

请用 JSON 格式返回，只返回 JSON，不要其他内容：
{{\"predictions\": [\"预测问题1\", \"预测问题2\", \"预测问题3\"]}}"""

    try:
        response = await _client.chat.completions.create(
            model=_settings.ai_model,
            messages=[
                {"role": "system", "content": "你是一个旅游规划助手，擅长预测用户需求。请只返回JSON格式。"},
                {"role": "user", "content": prediction_prompt}
            ],
            temperature=0.5,
            max_tokens=200,
        )
        pred_text = (response.choices[0].message.content or "").strip()
        # 尝试解析 JSON
        try:
            predictions = json.loads(pred_text)
            pred_list = predictions.get("predictions", []) if isinstance(predictions, dict) else []
        except json.JSONDecodeError:
            # 如果不是合法JSON，尝试提取数组内容
            pred_list = []
            import re
            matches = re.findall(r'["一-鿿][^"\]]{5,50}', pred_text)
            pred_list = [m.strip('"').strip() for m in matches[:3]]

        return {
            **state,
            "step": "predicting",
            "thinking_snapshot": THINKING_STEPS["predicting"][1],
            "predictions": pred_list[:3],
        }
    except Exception as e:
        return {
            **state,
            "step": "predicting",
            "error": f"预测失败: {str(e)}",
            "predictions": [],
        }
