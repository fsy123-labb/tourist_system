<template>
  <!-- 悬浮按钮 -->
  <div class="ai-fab" :class="{ 'is-open': visible }" @click="visible = !visible">
    <el-icon :size="26">
      <ChatDotRound v-if="!visible" />
      <Close v-else />
    </el-icon>
  </div>

  <!-- 对话面板 -->
  <transition name="ai-panel">
    <div v-show="visible" class="ai-panel" :style="panelStyle">
      <!-- 头部 -->
      <div class="ai-header">
        <div class="ai-header-title">
          <el-icon :size="18"><MagicStick /></el-icon>
          <span>AI 旅游助手 · 小旅</span>
        </div>
        <span class="ai-header-sub">🧠 LangGraph Agent · 实时思考 · 数据驱动</span>
      </div>

      <!-- 消息区域 -->
      <div class="ai-messages" ref="messagesRef" @mousedown.prevent>
        <div
          v-for="(msg, idx) in messages"
          :key="idx"
          class="ai-msg"
          :class="msg.role === 'user' ? 'is-user' : 'is-ai'"
        >
          <!-- 用户消息: 纯文本 -->
          <div v-if="msg.role === 'user'" class="ai-bubble ai-bubble-text">{{ msg.content }}</div>
          <!-- AI 消息: 思考过程 + 正式回答 -->
          <template v-else>
            <!-- 思考过程卡片（分阶段展示） -->
            <div v-if="msg.thinking" class="ai-thinking">
              <div class="thinking-header">
                <el-icon :size="14"><Lightning /></el-icon>
                <span>思考过程</span>
                <span v-if="loading && isLastAiMsg(idx)" class="thinking-live">● 实时</span>
              </div>
              <!-- 分阶段进度条 -->
              <div class="thinking-stages">
                <div
                  v-for="(stage, i) in thinkingStages"
                  :key="i"
                  class="stage-item"
                  :class="{ active: stage.active, done: stage.done }"
                >
                  <span class="stage-icon">{{ stage.icon }}</span>
                  <span class="stage-text">{{ stage.text }}</span>
                </div>
              </div>
              <div class="thinking-content ai-bubble-md" v-html="renderMarkdown(msg.thinking)"></div>
            </div>
            <!-- 正式回答 -->
            <div class="ai-bubble ai-bubble-md" v-html="renderMarkdown(msg.content)"></div>
            <!-- 预测问题 -->
            <div v-if="msg.predictions && msg.predictions.length" class="ai-predictions">
              <span class="predict-label">💡 你可能还想问：</span>
              <span
                v-for="(pred, i) in msg.predictions"
                :key="i"
                class="predict-chip"
                @click="handleSuggestion(pred)"
              >
                {{ pred }}
              </span>
            </div>
          </template>
        </div>
        <!-- 生成中的加载动画 -->
        <div v-if="showTyping" class="ai-msg is-ai">
          <div class="ai-bubble ai-typing"><span></span><span></span><span></span></div>
        </div>
      </div>

      <!-- 追问建议(每轮回答结束后刷新, 空闲时显示) -->
      <div v-if="!loading && followUpQuestions.length" class="ai-suggestions">
        <span class="suggestion-label">💡 猜你想问：</span>
        <span v-for="s in followUpQuestions" :key="s" class="suggestion-chip" @click="handleSuggestion(s)">
          {{ s }}
        </span>
      </div>

      <!-- 输入区域 -->
      <div class="ai-input-area">
        <el-input
          v-model="input"
          type="textarea"
          :rows="1"
          :autosize="{ minRows: 1, maxRows: 4 }"
          placeholder="问我任何旅游问题...（如：推荐几个必去的5A景区）"
          @keydown.enter.exact.prevent="handleSend"
        />
        <el-button
          v-if="!loading"
          type="primary"
          class="ai-send-btn"
          :disabled="!input.trim()"
          @click="handleSend"
        >
          <el-icon><Promotion /></el-icon>
        </el-button>
        <el-button v-else type="warning" class="ai-send-btn" @click="handleStop">
          <el-icon><VideoPause /></el-icon>
        </el-button>
      </div>
    </div>
  </transition>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ChatDotRound, Close, Lightning, MagicStick, Promotion, VideoPause } from '@element-plus/icons-vue'
import { chatWithAIStream } from '@/api/ai.js'
import { marked } from 'marked'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'

// ─── Markdown 渲染器配置 ─────────────────────────────────────────────────────
marked.setOptions({
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(code, { language: lang }).value
    }
    return hljs.highlightAuto(code).value
  },
  breaks: false,
  gfm: true
})

function restoreInlineCode(html) {
  return html
    .replace(/&lt;code class="hljs[^"]*"&gt;([\s\S]*?)&lt;\/code&gt;/g, '<code>$1</code>')
    .replace(/&lt;code&gt;([\s\S]*?)&lt;\/code&gt;/g, '<code>$1</code>')
    .replace(/&lt;pre&gt;&lt;code[^&gt;]*&gt;([\s\S]*?)&lt;\/code&gt;&lt;\/pre&gt;/g, '<pre><code>$1</code></pre>')
}

function renderMarkdown(raw) {
  if (!raw) return ''
  const html = marked.parse(raw)
  return restoreInlineCode(html)
}

// ─── 思考阶段配置 ─────────────────────────────────────────────────────────────
const THINKING_STAGES = [
  { key: 'init', icon: '🔄', text: '初始化' },
  { key: 'retrieving', icon: '🔍', text: '检索数据' },
  { key: 'analyzing', icon: '🧠', text: '分析推理' },
  { key: 'generating', icon: '✍️', text: '生成回答' },
  { key: 'predicting', icon: '💡', text: '预测需求' },
]

// ─── 状态 ────────────────────────────────────────────────────────────────────
const visible = ref(false)
const input = ref('')
const messages = ref([
  {
    role: 'assistant',
    content: '你好，我是AI旅游助手小旅 🧭\n\n我基于系统数据库中的 **678个景点** 和 **225个城市** 数据为你服务。\n\n可以帮你：\n- 🏆 推荐热门景点\n- 📍 规划行程路线\n- 💰 查询门票价格\n- 📊 分析景点数据\n\n直接问我吧！',
    thinking: null,
    predictions: null
  }
])
const messagesRef = ref(null)
const loading = ref(false)
let abortController = null

// 当前消息的思考阶段状态
const currentStage = ref('init')
const thinkingStages = ref(THINKING_STAGES.map(s => ({ ...s, active: false, done: false })))

// 面板尺寸响应式
const panelWidth = ref(420)
const panelHeight = ref(620)

function updatePanelSize() {
  const w = window.innerWidth
  const h = window.innerHeight
  panelWidth.value = w < 768 ? w - 16 : Math.min(440, w - 40)
  panelHeight.value = h < 700 ? h - 80 : Math.min(660, h - 120)
}
onMounted(() => { updatePanelSize(); window.addEventListener('resize', updatePanelSize) })
onUnmounted(() => window.removeEventListener('resize', updatePanelSize))

const panelStyle = computed(() => ({
  width: panelWidth.value + 'px',
  height: panelHeight.value + 'px'
}))

// 是否显示"打字中"动画
const showTyping = computed(() => {
  const last = messages.value[messages.value.length - 1]
  return loading.value && last && last.role === 'assistant' && last.content === '' && !last.thinking
})

// 判断是否为最后一条AI消息
function isLastAiMsg(idx) {
  const last = messages.value[messages.value.length - 1]
  return last && last.role === 'assistant' && messages.value[idx] === last
}

// 更新思考阶段状态
function updateStage(stageKey) {
  currentStage.value = stageKey
  thinkingStages.value = THINKING_STAGES.map(s => ({
    ...s,
    active: s.key === stageKey,
    done: s.key === stageKey || THINKING_STAGES.findIndex(x => x.key === s.key) < THINKING_STAGES.findIndex(x => x.key === stageKey)
  }))
}

// 推荐问题池
const questionPool = [
  '推荐几个必去的景点',
  '评分最高且免费的景点有哪些？',
  '预算2000元玩3天怎么安排？',
  '适合亲子游的景点有哪些？',
  '带老人出行有什么推荐？',
  '冬天去哪里旅游比较暖和？',
  '第一次去北京怎么规划行程？',
  '有哪些适合避暑的景点？',
  '适合拍照打卡的小众景点推荐',
  '情侣出游有什么浪漫的地方？',
  '各星级景点占比是多少？',
  '门票价格最便宜的景点'
]
const followUpQuestions = ref([])

function refreshFollowUps() {
  const asked = new Set(
    messages.value.filter((m) => m.role === 'user').map((m) => m.content)
  )
  followUpQuestions.value = questionPool
    .filter((q) => !asked.has(q))
    .sort(() => Math.random() - 0.5)
    .slice(0, 3)
}
refreshFollowUps()

function scrollToBottom() {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

function handleSuggestion(text) {
  input.value = text
  handleSend()
}

function handleSend() {
  const text = input.value.trim()
  if (!text || loading.value) return

  const history = messages.value
    .filter((m, i) => i > 0)
    .map((m) => ({ role: m.role, content: m.content, thinking: m.thinking }))

  // 重置思考阶段
  currentStage.value = 'init'
  thinkingStages.value = THINKING_STAGES.map(s => ({ ...s, active: false, done: false }))

  messages.value.push({ role: 'user', content: text, thinking: null, predictions: null })
  // AI 占位：先放入空思考和空回答
  messages.value.push({ role: 'assistant', content: '', thinking: null, predictions: null })
  input.value = ''
  loading.value = true
  scrollToBottom()

  // 流式接收
  abortController = chatWithAIStream({
    message: text,
    history: history.slice(-6),
    onThinking: (step, content) => {
      // 更新当前思考阶段
      updateStage(step)
      const last = messages.value[messages.value.length - 1]
      if (last && last.role === 'assistant') {
        last.thinking = (last.thinking || '') + content + '\n'
      }
      scrollToBottom()
    },
    onChunk: (chunk) => {
      const last = messages.value[messages.value.length - 1]
      if (last && last.role === 'assistant') {
        last.content = (last.content || '') + chunk
      }
      scrollToBottom()
    },
    onPredict: (content) => {
      try {
        const predictions = JSON.parse(content)
        if (Array.isArray(predictions)) {
          const last = messages.value[messages.value.length - 1]
          if (last && last.role === 'assistant') {
            last.predictions = predictions
          }
        }
      } catch (e) {
        // 忽略解析错误
      }
      scrollToBottom()
    },
    onError: (err) => {
      const last = messages.value[messages.value.length - 1]
      if (last && last.role === 'assistant') {
        if (!last.content && !last.thinking) {
          last.content = '❌ ' + err
        }
      }
      loading.value = false
      abortController = null
      scrollToBottom()
    },
    onDone: (meta) => {
      // 标记所有阶段为完成
      thinkingStages.value = THINKING_STAGES.map(s => ({ ...s, active: false, done: true }))
      loading.value = false
      abortController = null
      refreshFollowUps()
      scrollToBottom()
    }
  })
}

function handleStop() {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  loading.value = false
  const last = messages.value[messages.value.length - 1]
  if (last && last.role === 'assistant' && !last.content && !last.thinking) {
    last.content = '（已停止生成）'
  }
}
</script>

<style scoped>
/* ===== 悬浮按钮 ===== */
.ai-fab {
  position: fixed;
  right: 28px;
  bottom: 32px;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 6px 24px rgba(102, 126, 234, 0.5);
  z-index: 2000;
  transition: transform 0.25s ease, box-shadow 0.25s ease;
}
.ai-fab:hover {
  transform: translateY(-3px) scale(1.08);
  box-shadow: 0 10px 32px rgba(102, 126, 234, 0.6);
}
.ai-fab.is-open {
  background: linear-gradient(135deg, #f56c6c, #e84393);
  box-shadow: 0 6px 20px rgba(245, 108, 108, 0.45);
}

/* ===== 对话面板 ===== */
.ai-panel {
  position: fixed;
  right: 28px;
  bottom: 100px;
  background: #fafbfc;
  border-radius: 20px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.22);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  z-index: 2001;
  border: 1px solid rgba(255,255,255,0.6);
  backdrop-filter: blur(12px);
}

/* 头部 */
.ai-header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: #fff;
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  position: relative;
  overflow: hidden;
}
.ai-header::before {
  content: '';
  position: absolute;
  top: -30%;
  right: -10%;
  width: 120px;
  height: 120px;
  background: rgba(255,255,255,0.08);
  border-radius: 50%;
}
.ai-header::after {
  content: '';
  position: absolute;
  bottom: -40%;
  left: 10%;
  width: 80px;
  height: 80px;
  background: rgba(255,255,255,0.06);
  border-radius: 50%;
}
.ai-header-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 700;
  position: relative;
  z-index: 1;
  letter-spacing: 0.3px;
}
.ai-header-sub {
  font-size: 11px;
  opacity: 0.88;
  margin-top: 4px;
  position: relative;
  z-index: 1;
  font-weight: 400;
  letter-spacing: 0.2px;
}

/* 消息区域 */
.ai-messages {
  flex: 1;
  overflow-y: auto;
  background: linear-gradient(180deg, #f0f2f5 0%, #e8eaf0 100%);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.ai-msg {
  display: flex;
  animation: msg-in 0.3s ease;
}
@keyframes msg-in {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
.ai-msg.is-user {
  justify-content: flex-end;
}
.ai-msg.is-ai {
  justify-content: flex-start;
}

/* 通用气泡 */
.ai-bubble {
  max-width: 88%;
  padding: 12px 15px;
  border-radius: 16px;
  font-size: 13px;
  line-height: 1.75;
  word-break: break-word;
  overflow-wrap: break-word;
}
.ai-bubble-text {
  white-space: pre-wrap;
}

/* 用户气泡 */
.ai-msg.is-user .ai-bubble {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  border-top-right-radius: 6px;
  box-shadow: 0 3px 12px rgba(102, 126, 234, 0.3);
}

/* AI 气泡 - Markdown 渲染区 */
.ai-msg.is-ai .ai-bubble {
  background: #fff;
  color: #1a1a2e;
  border: 1px solid rgba(0,0,0,0.06);
  border-top-left-radius: 6px;
  box-shadow: 0 2px 10px rgba(0,0,0,0.06);
}
.ai-bubble-md {
  font-size: 13px;
  line-height: 1.8;
  word-break: break-word;
  overflow-wrap: break-word;
}
.ai-bubble-md p { margin: 0 0 8px; }
.ai-bubble-md p:last-child { margin-bottom: 0; }
.ai-bubble-md h1 {
  font-size: 17px;
  margin: 12px 0 8px;
  padding-bottom: 6px;
  border-bottom: 2px solid #667eea;
  color: #1a1a2e;
  font-weight: 700;
}
.ai-bubble-md h2 {
  font-size: 15px;
  margin: 10px 0 6px;
  color: #2d3748;
  font-weight: 700;
  padding-left: 8px;
  border-left: 3px solid #667eea;
}
.ai-bubble-md h3 {
  font-size: 13.5px;
  margin: 8px 0 4px;
  color: #2d3748;
  font-weight: 600;
}
.ai-bubble-md h4, .ai-bubble-md h5, .ai-bubble-md h6 {
  font-size: 13px;
  margin: 6px 0 4px;
  color: #4a5568;
  font-weight: 600;
}
.ai-bubble-md ul, .ai-bubble-md ol {
  margin: 6px 0;
  padding-left: 20px;
}
.ai-bubble-md li {
  margin: 4px 0;
  line-height: 1.7;
}
.ai-bubble-md li::marker {
  color: #667eea;
  font-weight: 700;
}
.ai-bubble-md blockquote {
  margin: 8px 0;
  padding: 8px 14px;
  border-left: 4px solid #667eea;
  background: linear-gradient(135deg, #f0f4ff, #f5f0ff);
  border-radius: 0 10px 10px 0;
  color: #4a5568;
  font-size: 12.5px;
}
.ai-bubble-md blockquote p { margin: 2px 0; }
.ai-bubble-md a {
  color: #667eea;
  text-decoration: none;
  border-bottom: 1px dashed #667eea;
  transition: all 0.2s;
}
.ai-bubble-md a:hover {
  color: #764ba2;
  border-bottom-color: #764ba2;
}
.ai-bubble-md strong {
  color: #1a1a2e;
  font-weight: 700;
}
.ai-bubble-md em { color: #718096; }
.ai-bubble-md table {
  border-collapse: collapse;
  width: 100%;
  margin: 10px 0;
  font-size: 12px;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(0,0,0,0.06);
}
.ai-bubble-md th {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  font-weight: 600;
  padding: 8px 10px;
  text-align: left;
  font-size: 12px;
}
.ai-bubble-md td {
  border: 1px solid #e2e8f0;
  padding: 6px 10px;
  text-align: left;
  color: #2d3748;
}
.ai-bubble-md tr:nth-child(even) { background: #f7fafc; }
.ai-bubble-md tr:hover { background: #edf2f7; }
.ai-bubble-md hr {
  border: none;
  border-top: 1px solid #e2e8f0;
  margin: 12px 0;
}
.ai-bubble-md img {
  max-width: 100%;
  border-radius: 8px;
}

/* 代码 */
.ai-bubble-md code {
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, Monaco, monospace;
  font-size: 11.5px;
  background: linear-gradient(135deg, #f6f8fa, #edf2f7);
  color: #c7254e;
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid #e2e8f0;
}
.ai-bubble-md pre {
  margin: 10px 0;
  padding: 0;
  border-radius: 10px;
  overflow-x: auto;
  background: linear-gradient(135deg, #1e1e2e, #2d2d44);
  box-shadow: 0 2px 8px rgba(0,0,0,0.15);
}
.ai-bubble-md pre code {
  background: none;
  color: #cdd6f4;
  padding: 14px 16px;
  font-size: 12px;
  border: none;
  display: block;
}

/* ─── 思考过程卡片 ─── */
.ai-thinking {
  max-width: 88%;
  margin-bottom: 6px;
  animation: msg-in 0.3s ease;
}
.thinking-header {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 600;
  color: #764ba2;
  margin-bottom: 6px;
  letter-spacing: 0.3px;
}
.thinking-live {
  margin-left: auto;
  font-size: 10px;
  color: #48bb78;
  animation: pulse 1.5s infinite;
  font-weight: 500;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

/* 思考阶段进度条 */
.thinking-stages {
  display: flex;
  gap: 4px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.stage-item {
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: 10px;
  color: #a0aec0;
  padding: 2px 6px;
  border-radius: 10px;
  background: #f0f4f8;
  transition: all 0.3s ease;
}
.stage-item.active {
  color: #764ba2;
  background: linear-gradient(135deg, rgba(102,126,234,0.15), rgba(118,75,162,0.15));
  font-weight: 600;
  animation: stage-pulse 1s infinite;
}
.stage-item.done {
  color: #48bb78;
  background: rgba(72, 187, 120, 0.1);
}
@keyframes stage-pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.05); }
}
.stage-icon {
  font-size: 10px;
}
.stage-text {
  font-size: 10px;
}

.thinking-content {
  background: linear-gradient(135deg, #faf5ff, #f0f4ff);
  border: 1px dashed #b794f4;
  border-radius: 12px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.7;
  color: #553c9a;
  box-shadow: 0 2px 8px rgba(118, 75, 162, 0.08);
}
.thinking-content .ai-bubble-md { font-size: 12px; }
.thinking-content code {
  font-size: 11px;
  background: rgba(118, 75, 162, 0.1);
  color: #764ba2;
  border: none;
  padding: 1px 4px;
}
.thinking-content pre {
  background: rgba(118, 75, 162, 0.08);
  border-radius: 6px;
}
.thinking-content pre code {
  color: #553c9a;
  background: none;
  padding: 8px;
}
.thinking-content blockquote {
  border-left-color: #b794f4;
  background: rgba(118, 75, 162, 0.06);
}
.thinking-content h2 {
  border-left-color: #b794f4;
  color: #553c9a;
  font-size: 12px;
}
.thinking-content h3 { color: #553c9a; font-size: 12px; }
.thinking-content ul, .thinking-content ol { padding-left: 16px; }
.thinking-content li { margin: 2px 0; font-size: 12px; }
.thinking-content li::marker { color: #b794f4; }
.thinking-content hr { border-color: #e9d8fd; }
.thinking-content table { font-size: 11px; }
.thinking-content th {
  background: linear-gradient(135deg, #b794f4, #9f7aea);
  font-size: 11px;
  padding: 5px 8px;
}
.thinking-content td {
  border-color: #e9d8fd;
  padding: 4px 8px;
  font-size: 11px;
  color: #553c9a;
}

/* ─── 预测问题 ─── */
.ai-predictions {
  max-width: 88%;
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  animation: msg-in 0.3s ease;
}
.predict-label {
  font-size: 11px;
  color: #a0aec0;
  font-weight: 500;
}
.predict-chip {
  font-size: 11px;
  color: #667eea;
  background: linear-gradient(135deg, rgba(102,126,234,0.08), rgba(118,75,162,0.08));
  border: 1px solid rgba(102,126,234,0.25);
  border-radius: 12px;
  padding: 3px 10px;
  cursor: pointer;
  transition: all 0.25s ease;
  font-weight: 500;
}
.predict-chip:hover {
  background: linear-gradient(135deg, rgba(102,126,234,0.18), rgba(118,75,162,0.18));
  border-color: rgba(102,126,234,0.5);
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(102,126,234,0.15);
}

/* 打字中动画 */
.ai-typing {
  display: flex;
  gap: 4px;
  align-items: center;
  padding: 12px 16px;
}
.ai-typing span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea, #764ba2);
  animation: ai-blink 1.2s infinite ease-in-out;
}
.ai-typing span:nth-child(2) { animation-delay: 0.2s; }
.ai-typing span:nth-child(3) { animation-delay: 0.4s; }
@keyframes ai-blink {
  0%, 80%, 100% { opacity: 0.3; transform: scale(0.85); }
  40% { opacity: 1; transform: scale(1); }
}

/* 快捷提问 */
.ai-suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 14px;
  border-top: 1px solid #e2e8f0;
  background: #fff;
  flex-shrink: 0;
}
.suggestion-label {
  font-size: 11px;
  color: #a0aec0;
  align-self: center;
  margin-right: 2px;
  font-weight: 500;
}
.suggestion-chip {
  font-size: 12px;
  color: #667eea;
  background: linear-gradient(135deg, rgba(102,126,234,0.08), rgba(118,75,162,0.08));
  border: 1px solid rgba(102,126,234,0.25);
  border-radius: 20px;
  padding: 5px 12px;
  cursor: pointer;
  transition: all 0.25s ease;
  font-weight: 500;
}
.suggestion-chip:hover {
  background: linear-gradient(135deg, rgba(102,126,234,0.18), rgba(118,75,162,0.18));
  border-color: rgba(102,126,234,0.5);
  transform: translateY(-1px);
  box-shadow: 0 2px 8px rgba(102,126,234,0.15);
}

/* 输入区域 */
.ai-input-area {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 12px 14px;
  border-top: 1px solid #e2e8f0;
  background: #fff;
  flex-shrink: 0;
}
.ai-send-btn {
  height: 34px;
  padding: 0 16px;
  flex-shrink: 0;
  border-radius: 10px;
  font-weight: 600;
  background: linear-gradient(135deg, #667eea, #764ba2);
  border: none;
}
.ai-send-btn:hover { opacity: 0.9; transform: translateY(-1px); }
.ai-send-btn:disabled { opacity: 0.5; transform: none; }

/* 面板出入场动画 */
.ai-panel-enter-active,
.ai-panel-leave-active {
  transition: opacity 0.3s ease, transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.ai-panel-enter-from,
.ai-panel-leave-to {
  opacity: 0;
  transform: translateY(20px) scale(0.95);
}

/* 滚动条美化 */
.ai-messages::-webkit-scrollbar { width: 5px; }
.ai-messages::-webkit-scrollbar-track { background: transparent; }
.ai-messages::-webkit-scrollbar-thumb {
  background: linear-gradient(180deg, #667eea, #764ba2);
  border-radius: 3px;
}
.ai-messages::-webkit-scrollbar-thumb:hover { background: linear-gradient(180deg, #764ba2, #667eea); }
</style>
