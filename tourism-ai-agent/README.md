# Tourism AI Agent - LangGraph + FastAPI

基于 **LangGraph** 状态机的 AI 旅游助手微服务，替代原 Java Spring Boot 实现。

## 架构对比

| 特性 | 原 Java 实现 | 新 LangGraph 实现 |
|------|-------------|------------------|
| 框架 | Spring Boot | FastAPI + LangGraph |
| Agent 结构 | 单体方法 | 状态机（4节点） |
| RAG | 全量注入(678景点) | 按需检索 + 全量摘要 |
| 流式输出 | SSE 代理转发 | 原生 SSE + 打字机效果 |
| 思考过程 | 模型生成+解析 | 分阶段事件推送 |
| 智能预测 | ❌ 无 | ✅ 预测用户需求 |
| API 兼容 | `/api/ai/chat` | `/api/ai/chat` (兼容) |

## 快速启动

```bash
# 1. 安装依赖
pip install -r requirements.txt

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env 填入 AI_API_KEY

# 3. 启动服务
python main.py
# 或: uvicorn main:app --host 0.0.0.0 --port 8001 --reload
```

## API 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/ai/chat` | POST | 流式对话（SSE） |
| `/api/ai/predict` | POST | 预测下一个问题 |
| `/api/ai/health` | GET | 健康检查 |
| `/api/ai/stats` | GET | 统计数据 |

## 测试命令

```bash
# 健康检查
curl http://localhost:8001/api/ai/health

# 流式对话
curl -X POST http://localhost:8001/api/ai/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"推荐几个北京的景点","history":[]}'

# 查看统计
curl http://localhost:8001/api/ai/stats
```

## 目录结构

```
tourism-ai-agent/
├── main.py              # FastAPI 入口 + SSE 流式接口
├── requirements.txt     # Python 依赖
├── .env.example         # 环境变量模板
└── agent/
    ├── __init__.py      # 模块导出
    ├── models.py        # 数据模型（State, Request, Event）
    ├── db.py            # 数据库访问（MySQL → SQLAlchemy）
    ├── nodes.py         # LangGraph 节点实现（4个核心节点）
    └── graph.py         # 状态机构建（init→llm→stream→predict）
```

## 前端对接

前端无需改动，原 Java 接口路径 `POST /api/ai/chat` 完全兼容。
如需调整端口，修改 `.env` 中的 `AGENT_PORT` 即可。
