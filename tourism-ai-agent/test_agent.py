"""测试 LangGraph AI Agent 接口"""
import asyncio
import requests
import json
import sys

# Fix Windows encoding
if sys.platform == 'win32':
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')


async def test_health():
    """测试健康检查"""
    r = requests.get("http://localhost:8001/api/ai/health", timeout=10)
    print(f"[Health] {r.status_code}")
    data = r.json()
    print(json.dumps(data, indent=2, ensure_ascii=False))
    assert r.status_code == 200
    assert data["status"] == "healthy"
    print("PASS: Health check OK\n")


async def test_stats():
    """测试统计接口"""
    r = requests.get("http://localhost:8001/api/ai/stats", timeout=10)
    print(f"[Stats] {r.status_code}")
    data = r.json()
    print(json.dumps(data, indent=2, ensure_ascii=False))
    assert r.status_code == 200
    assert data["total_spots"] == 678
    assert data["total_cities"] == 225
    print("PASS: Stats check OK\n")


async def test_chat():
    """测试流式对话接口"""
    payload = {
        "message": "北京有什么好玩的景点？",
        "history": [],
        "session_id": "test-session-001"
    }
    print(f"[Chat] POST /api/ai/chat")
    print(f"Request: {json.dumps(payload, ensure_ascii=False)}\n")

    r = requests.post(
        "http://localhost:8001/api/ai/chat",
        json=payload,
        stream=True,
        timeout=60
    )
    print(f"Response status: {r.status_code}")
    print(f"Content-Type: {r.headers.get('content-type')}")
    print("\n--- SSE Stream ---")

    events = []
    for line in r.iter_lines():
        if line:
            line = line.decode('utf-8')
            if line.startswith('data: '):
                try:
                    event = json.loads(line[6:])
                    events.append(event)
                    etype = event.get('type', 'unknown')
                    if etype == 'thinking':
                        content = event.get('content', '')
                        print(f"  [思考] {event.get('step')}: {content[:60]}...")
                    elif etype == 'content':
                        content = event.get('content', '')
                        print(f"  [内容] {content[:80]}...")
                    elif etype == 'predict':
                        content = event.get('content', '')
                        print(f"  [预测] {content[:80]}...")
                    elif etype == 'done':
                        meta = event.get('meta', {})
                        print(f"  [完成] step={meta.get('step')}, tokens={meta.get('token_count')}")
                    elif etype == 'error':
                        print(f"  [错误] {event.get('content', '')}")
                except Exception as e:
                    print(f"  Raw: {line[:80]}")

    print("--- End of Stream ---")
    print(f"\nPASS: Chat test completed, received {len(events)} events\n")
    return events


async def main():
    print("=" * 60)
    print("Tourism AI Agent (LangGraph) - Interface Test")
    print("=" * 60 + "\n")

    await test_health()
    await test_stats()
    await test_chat()

    print("=" * 60)
    print("All tests passed!")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
