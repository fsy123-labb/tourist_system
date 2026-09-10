import { getToken } from '@/utils/auth.js'

/**
 * AI旅游助手流式对话
 * axios不支持SSE流式读取, 这里用原生fetch + ReadableStream逐块解析
 * 后端协议: SSE事件 data: {"content":"增量文本"} ... data: [DONE]
 *
 * @param options { message, history, onChunk, onError, onDone }
 * @returns AbortController 调用.abort()可中断生成
 */
export function chatWithAIStream({ message, history = [], onChunk, onError, onDone }) {
  const controller = new AbortController()

  fetch('/api/ai/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': getToken() || ''
    },
    body: JSON.stringify({ message, history }),
    signal: controller.signal
  }).then(async (res) => {
    if (!res.ok || !res.body) {
      throw new Error('服务异常(' + res.status + ')')
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      // stream:true 处理中文多字节字符被截断的情况
      buffer += decoder.decode(value, { stream: true })

      // SSE事件以空行(\n\n)分隔, 最后一段可能不完整留在buffer
      const events = buffer.split('\n\n')
      buffer = events.pop()

      for (const evt of events) {
        const dataLine = evt.split('\n').find((l) => l.startsWith('data:'))
        if (!dataLine) continue
        const payload = dataLine.slice(5).trim()

        // 流结束标记
        if (payload === '[DONE]') {
          onDone && onDone()
          return
        }
        try {
          const json = JSON.parse(payload)
          if (json.error) {
            onError && onError(json.error)
            return
          }
          if (json.content) {
            onChunk && onChunk(json.content)
          }
        } catch (e) {
          // 忽略解析失败的碎片
        }
      }
    }
    onDone && onDone()
  }).catch((err) => {
    // 用户主动停止不算错误
    if (err.name !== 'AbortError') {
      onError && onError(err.message || '网络连接异常')
    }
  })

  return controller
}
