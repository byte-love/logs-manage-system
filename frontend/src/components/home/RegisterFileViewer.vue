<template>
  <el-dialog
    :model-value="modelValue"
    width="min(1080px, 92vw)"
    top="4vh"
    destroy-on-close
    class="rf-dialog"
    :show-close="false"
    @update:model-value="close"
  >
    <template #header>
      <div class="rf-head">
        <div class="rf-head-main">
          <span class="rf-head-icon">🗂</span>
          <div class="rf-head-text">
            <strong>{{ subject.nickName || subject.userName || '申请材料' }}</strong>
            <span class="rf-head-sub">{{ subject.mail || '无邮箱' }}</span>
          </div>
        </div>
        <button class="rf-close" title="关闭" @click="close">✕</button>
      </div>
    </template>

    <div class="rf-body">
      <!-- 两类材料切换 -->
      <div class="rf-tabs">
        <button
          v-for="t in tabs"
          :key="t.key"
          class="rf-tab"
          :class="{ active: activeKey === t.key, empty: !t.url }"
          :disabled="!t.url"
          @click="activeKey = t.key"
        >
          <span class="rf-tab-icon">{{ t.icon }}</span>
          <span>{{ t.label }}</span>
          <span v-if="!t.url" class="rf-tab-flag">未上传</span>
        </button>
      </div>
      <!-- 工具条 -->
      <div class="rf-toolbar">
        <div class="rf-tool-group">
          <button class="rf-tool" title="缩小" :disabled="!canShow" @click="zoom(-0.2)">－</button>
          <span class="rf-scale">{{ Math.round(scale * 100) }}%</span>
          <button class="rf-tool" title="放大" :disabled="!canShow" @click="zoom(0.2)">＋</button>
          <button class="rf-tool" title="向左旋转" :disabled="!canShow" @click="rotate(-90)">⟲</button>
          <button class="rf-tool" title="向右旋转" :disabled="!canShow" @click="rotate(90)">⟳</button>
          <button class="rf-tool" title="还原" :disabled="!canShow" @click="resetView">重置</button>
        </div>
        <div class="rf-tool-group">
          <a
            v-if="currentUrl"
            class="rf-tool rf-tool-link"
            :href="currentUrl"
            target="_blank"
            rel="noopener noreferrer"
          >新窗口打开</a>
          <a
            v-if="currentUrl"
            class="rf-tool rf-tool-primary"
            :href="currentUrl"
            :download="downloadName"
          >下载</a>
        </div>
      </div>

      <!-- 画布 -->
      <div class="rf-stage" @wheel.prevent="onWheel">
        <div v-if="loading" class="rf-state">
          <div class="rf-spinner"></div>
          <span>正在加载材料…</span>
        </div>

        <div v-else-if="!currentUrl" class="rf-state">
          <span class="rf-state-icon">📭</span>
          <strong>暂无该材料</strong>
        </div>

        <div v-else-if="failed" class="rf-state rf-state-error">
          <span class="rf-state-icon">🖼️</span>
          <strong>材料无法预览</strong>
          <a class="rf-tool rf-tool-link" :href="currentUrl" target="_blank" rel="noopener noreferrer">
            打开原始链接
          </a>
        </div>

        <img
          v-show="!loading && !failed && currentUrl"
          :key="currentUrl"
          :src="currentUrl"
          class="rf-img"
          :style="imgStyle"
          alt="申请材料"
          draggable="false"
          @load="onLoaded"
          @error="onError"
          @mousedown="startDrag"
        />
      </div>

      <!-- 历史材料 -->
      <div v-if="subject.matchCount > 1" class="rf-history">
        <div class="rf-history-head">
          <span class="rf-history-title">
            共 {{ subject.matchCount }} 份申请记录，当前显示最新一份
          </span>
          <button class="rf-tool" :disabled="historyLoading" @click="loadHistory">
            {{ historyLoaded ? '收起' : (historyLoading ? '加载中…' : '查看全部') }}
          </button>
        </div>
        <div v-if="historyLoaded" class="rf-history-list">
          <button
            v-for="h in history"
            :key="h.registerId"
            class="rf-history-item"
            :class="{ active: h.registerId === activeRegisterId }"
            @click="pickHistory(h)"
          >
            <span class="rf-history-id">#{{ h.registerId }}</span>
            <span class="rf-history-time">{{ formatTime(h.applicationTime) }}</span>
            <span class="rf-history-school">{{ h.school || '—' }}</span>
            <span class="rf-history-files">
              <i :class="h.AgreeFile ? 'ok' : 'no'" title="使用协议">协</i>
              <i :class="h.Workfile ? 'ok' : 'no'" title="注册资料">资</i>
            </span>
          </button>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getRegisterFiles } from '../../api/userData'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // { userName, nickName, mail, agreeFile, workFile, matchCount }
  subject: { type: Object, default: () => ({}) },
  // 打开时先展示哪一类：'agree' | 'work'
  initialKey: { type: String, default: 'agree' }
})

const emit = defineEmits(['update:modelValue'])

const activeKey = ref('agree')
const loading = ref(false)
const failed = ref(false)
const scale = ref(1)
const angle = ref(0)
const offset = reactive({ x: 0, y: 0 })

// 历史材料（同邮箱的多次申请）
const history = ref([])
const historyLoaded = ref(false)
const historyLoading = ref(false)
// 用户从历史里选了某一份时，覆盖默认的最新一份
const override = ref(null)

const tabs = computed(() => [
  { key: 'agree', label: '使用协议', icon: '📜', url: urlOf('agree') },
  { key: 'work', label: '注册资料', icon: '🪪', url: urlOf('work') }
])

function urlOf(key) {
  const src = override.value || props.subject
  return key === 'agree'
    ? (src.AgreeFile || src.agreeFile || null)
    : (src.Workfile || src.workFile || null)
}

const currentUrl = computed(() => urlOf(activeKey.value))
const canShow = computed(() => !!currentUrl.value && !failed.value && !loading.value)
const activeRegisterId = computed(() =>
  (override.value || props.subject).registerId ?? props.subject.registerId)

const downloadName = computed(() => {
  const who = props.subject.userName || props.subject.mail || 'register'
  const kind = activeKey.value === 'agree' ? '使用协议' : '注册资料'
  // OSS 上的对象没有扩展名，实测是 JPEG/PNG，统一给 .jpg 便于本地打开
  return `${who}_${kind}.jpg`
})

const imgStyle = computed(() => ({
  transform: `translate(${offset.x}px, ${offset.y}px) scale(${scale.value}) rotate(${angle.value}deg)`,
  cursor: scale.value > 1 ? 'grab' : 'default'
}))

watch(() => props.modelValue, (open) => {
  if (!open) return
  // 每次打开都回到干净状态
  override.value = null
  history.value = []
  historyLoaded.value = false
  const wanted = props.initialKey === 'work' ? 'work' : 'agree'
  // 想看的那类没有文件时，自动落到另一类
  activeKey.value = urlOf(wanted) ? wanted : (urlOf('agree') ? 'agree' : 'work')
  resetView()
  // 重开同一行时 currentUrl 不变、下面的 watcher 不会触发，
  // 这里显式复位，否则会残留上一次的失败/加载状态
  failed.value = false
  loading.value = !!urlOf(activeKey.value)
})

watch(currentUrl, (url) => {
  resetView()
  failed.value = false
  loading.value = !!url
})

function close() {
  emit('update:modelValue', false)
}

function onLoaded() {
  loading.value = false
  failed.value = false
}

function onError() {
  loading.value = false
  failed.value = true
}

function resetView() {
  scale.value = 1
  angle.value = 0
  offset.x = 0
  offset.y = 0
}

function zoom(delta) {
  const next = Math.min(6, Math.max(0.2, scale.value + delta))
  scale.value = Number(next.toFixed(2))
}

function rotate(deg) {
  angle.value = (angle.value + deg) % 360
}

function onWheel(e) {
  if (!canShow.value) return
  zoom(e.deltaY > 0 ? -0.15 : 0.15)
}

// 放大后可拖动查看细节
function startDrag(e) {
  if (scale.value <= 1) return
  const startX = e.clientX - offset.x
  const startY = e.clientY - offset.y
  const move = (ev) => {
    offset.x = ev.clientX - startX
    offset.y = ev.clientY - startY
  }
  const up = () => {
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
}

async function loadHistory() {
  if (historyLoaded.value) {
    historyLoaded.value = false
    return
  }
  if (!props.subject.mail) return
  historyLoading.value = true
  try {
    const res = await getRegisterFiles({ mail: props.subject.mail })
    history.value = res.data?.records || []
    historyLoaded.value = true
  } catch (err) {
    ElMessage.error(err.response?.data?.message || '历史材料加载失败')
  } finally {
    historyLoading.value = false
  }
}

function pickHistory(item) {
  override.value = item
  // 切过去后若当前类别在这份记录里没有文件，自动切到有的那一类
  if (!urlOf(activeKey.value)) {
    activeKey.value = activeKey.value === 'agree' ? 'work' : 'agree'
  }
}

function formatTime(value) {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
</script>

<style scoped>
/* ---------- 头部 ---------- */
.rf-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.rf-head-main {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.rf-head-icon {
  font-size: 26px;
  filter: drop-shadow(0 2px 6px rgba(0, 0, 0, 0.2));
}
.rf-head-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.rf-head-text strong {
  color: #17233d;
  font-size: 17px;
  letter-spacing: 0.3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rf-head-sub {
  /* 白底上 #8a95b0 只有 3.0，偏弱；换成一档更实的灰蓝（4.68，AA） */
  color: #68748f;
  font-size: 12.5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rf-close {
  flex: none;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 10px;
  background: #f2f5fa;
  color: #8a95b0;
  font-size: 15px;
  cursor: pointer;
  transition: background 0.2s ease, transform 0.25s ease;
}
.rf-close:hover {
  background: #e6ebf5;
  transform: rotate(90deg);
}

/* 标题栏底色由 theme.css 的 .el-dialog__header 统一给（现在是白底），
   所以这里不能再沿用原来「白字压在品牌渐变上」的配色，改成深色系 */

/* ---------- 主体 ---------- */
.rf-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* 类别切换 */
.rf-tabs {
  display: flex;
  gap: 10px;
}
.rf-tab {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 9px 18px;
  border: 1px solid rgba(99, 122, 165, 0.2);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.75);
  color: #4a5573;
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.22s ease;
}
.rf-tab:hover:not(:disabled) {
  border-color: var(--brand-start);
  color: var(--brand-start);
  transform: translateY(-1px);
}
.rf-tab.active {
  border-color: transparent;
  background: var(--brand-grad);
  color: #fff;
  box-shadow: 0 8px 18px rgba(63, 140, 255, 0.3);
}
.rf-tab.empty {
  opacity: 0.5;
  cursor: not-allowed;
}
.rf-tab-icon {
  font-size: 15px;
}
.rf-tab-flag {
  padding: 1px 7px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.25);
  font-size: 11px;
  font-weight: 500;
}

/* 工具条 */
.rf-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(63, 140, 255, 0.07), rgba(108, 99, 255, 0.07));
}
.rf-tool-group {
  display: flex;
  align-items: center;
  gap: 8px;
}
.rf-tool {
  min-width: 34px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid rgba(99, 122, 165, 0.22);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.9);
  color: #4a5573;
  font-size: 13px;
  font-weight: 600;
  line-height: 30px;
  text-align: center;
  text-decoration: none;
  cursor: pointer;
  transition: all 0.2s ease;
}
.rf-tool:hover:not(:disabled) {
  border-color: var(--brand-start);
  color: var(--brand-start);
  box-shadow: 0 6px 14px rgba(63, 140, 255, 0.16);
}
.rf-tool:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.rf-tool-primary {
  border-color: transparent;
  background: var(--brand-grad);
  color: #fff;
}
.rf-tool-primary:hover {
  color: #fff;
  box-shadow: 0 10px 20px rgba(63, 140, 255, 0.34);
}
.rf-scale {
  min-width: 52px;
  color: #5a6688;
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

/* 画布 */
.rf-stage {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 52vh;
  max-height: 62vh;
  overflow: hidden;
  border-radius: 18px;
  background:
    linear-gradient(135deg, #0f1729 0%, #16233d 100%);
  background-image:
    linear-gradient(45deg, rgba(255, 255, 255, 0.03) 25%, transparent 25%),
    linear-gradient(-45deg, rgba(255, 255, 255, 0.03) 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, rgba(255, 255, 255, 0.03) 75%),
    linear-gradient(-45deg, transparent 75%, rgba(255, 255, 255, 0.03) 75%);
  background-size: 22px 22px;
  background-position: 0 0, 0 11px, 11px -11px, -11px 0;
}
.rf-img {
  max-width: 100%;
  max-height: 60vh;
  border-radius: 8px;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.45);
  transition: transform 0.18s ease-out;
  user-select: none;
}

/* 状态占位 */
.rf-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 28px;
  color: rgba(220, 231, 255, 0.9);
  text-align: center;
}
.rf-state strong {
  font-size: 15px;
  color: #eaf1ff;
}
.rf-state-icon {
  font-size: 40px;
  opacity: 0.85;
}
.rf-state-tip {
  max-width: 380px;
  color: rgba(190, 205, 235, 0.75);
  font-size: 12.5px;
  line-height: 1.65;
}
.rf-state-error .rf-tool {
  margin-top: 6px;
  background: rgba(255, 255, 255, 0.14);
  border-color: rgba(255, 255, 255, 0.22);
  color: #eaf1ff;
}
.rf-state-error .rf-tool:hover {
  background: rgba(255, 255, 255, 0.24);
  color: #fff;
}
.rf-spinner {
  width: 34px;
  height: 34px;
  border: 3px solid rgba(255, 255, 255, 0.18);
  border-top-color: var(--brand-cyan);
  border-radius: 50%;
  animation: rfSpin 0.8s linear infinite;
}
@keyframes rfSpin {
  to { transform: rotate(360deg); }
}

/* 历史材料 */
.rf-history {
  padding: 12px 14px;
  border-radius: 14px;
  border: 1px dashed rgba(99, 122, 165, 0.3);
  background: rgba(240, 244, 253, 0.6);
}
.rf-history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.rf-history-title {
  color: #5a6688;
  font-size: 12.5px;
}
.rf-history-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 168px;
  margin-top: 10px;
  overflow-y: auto;
}
.rf-history-item {
  display: grid;
  grid-template-columns: 76px 96px 1fr 56px;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: #fff;
  color: #4a5573;
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
  transition: all 0.18s ease;
}
.rf-history-item:hover {
  border-color: rgba(63, 140, 255, 0.4);
  transform: translateX(2px);
}
.rf-history-item.active {
  border-color: var(--brand-start);
  background: var(--brand-grad-soft);
}
.rf-history-id {
  color: #8a95b0;
  font-variant-numeric: tabular-nums;
}
.rf-history-time {
  font-variant-numeric: tabular-nums;
}
.rf-history-school {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.rf-history-files {
  display: flex;
  gap: 4px;
  justify-content: flex-end;
}
.rf-history-files i {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  font-size: 11px;
  font-style: normal;
  line-height: 20px;
  text-align: center;
}
.rf-history-files i.ok {
  background: rgba(64, 191, 129, 0.16);
  color: #2f9e68;
}
.rf-history-files i.no {
  background: rgba(148, 163, 184, 0.18);
  color: #9aa5bb;
}
</style>
