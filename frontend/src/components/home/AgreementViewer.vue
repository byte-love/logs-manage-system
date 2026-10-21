<template>
  <el-dialog
    :model-value="modelValue"
    width="min(1100px, 94vw)"
    top="4vh"
    destroy-on-close
    class="ag-dialog"
    :show-close="false"
    append-to-body
    @update:model-value="close"
  >
    <template #header>
      <div class="ag-head">
        <div class="ag-head-main">
          <span class="ag-head-icon">📄</span>
          <div class="ag-head-text">
            <strong>{{ subject.adress || '协议' }}</strong>
            <span class="ag-head-sub">编号 {{ subject.id ?? '—' }}</span>
          </div>
        </div>
        <button class="ag-close" title="关闭" @click="close">✕</button>
      </div>
    </template>

    <div class="ag-body">
      <!-- 两类协议切换 -->
      <div class="ag-tabs">
        <button
          v-for="t in tabs"
          :key="t.key"
          class="ag-tab"
          :class="{ active: activeKey === t.key, empty: !t.has }"
          :disabled="!t.has"
          @click="activeKey = t.key"
        >
          <span class="ag-tab-icon">{{ t.icon }}</span>
          <span>{{ t.label }}</span>
          <span v-if="!t.has" class="ag-tab-flag">未上传</span>
        </button>
      </div>

      <!-- 工具条：不自绘缩放/旋转，浏览器原生 PDF 阅读器已自带 -->
      <div class="ag-toolbar">
        <span class="ag-hint">{{ hint }}</span>
        <div class="ag-tool-group">
          <a
            class="ag-tool"
            :class="{ disabled: !canOpen }"
            :href="canOpen ? frameUrl : undefined"
            target="_blank"
            rel="noopener noreferrer"
          >新窗口打开</a>
          <!-- 带 download 属性：不加也能下（后端已给 Content-Disposition: attachment），
               但那样要先把当前页导航到该地址再由浏览器转成下载；显式声明更直接，
               同源所以文件名会被采纳 -->
          <a
            class="ag-tool ag-tool-primary"
            :class="{ disabled: !currentHas }"
            :href="currentHas ? downloadHref : undefined"
            :download="downloadName"
          >下载</a>
        </div>
      </div>

      <!-- 画布 -->
      <div class="ag-stage">
        <div v-if="loading" class="ag-state">
          <div class="ag-spinner"></div>
          <span>正在加载协议…</span>
        </div>

        <div v-else-if="!currentHas" class="ag-state">
          <span class="ag-state-icon">📭</span>
          <strong>暂无该协议</strong>
        </div>

        <div v-else-if="error" class="ag-state ag-state-error">
          <span class="ag-state-icon">⚠️</span>
          <strong>{{ error }}</strong>
          <span v-if="notPdf" class="ag-state-tip">
            该文件不是 PDF，浏览器无法内嵌预览，可下载后用本地程序打开。
          </span>
          <a v-if="notPdf" class="ag-tool" :href="downloadHref" :download="downloadName">下载文件</a>
          <button v-else class="ag-tool" @click="load">重试</button>
        </div>

        <iframe
          v-else-if="frameUrl"
          :key="frameUrl"
          :src="frameUrl"
          class="ag-frame"
          title="协议预览"
        ></iframe>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { agreementUrl, getAgreementBlob } from '../../api/school'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // { id, adress, hasAgree, hasStudent }
  subject: { type: Object, default: () => ({}) },
  initialKey: { type: String, default: 'agree' }
})

const emit = defineEmits(['update:modelValue'])

const activeKey = ref('agree')
const loading = ref(false)
const error = ref('')
const notPdf = ref(false)
// iframe 指向接口地址而不是 blob：接口带 Content-Disposition 的中文文件名，
// 原生阅读器的标题栏和它自带的下载按钮都能拿到可读名字；
// 用 blob 的话两处都会显示一串 UUID、存下来还没扩展名。
// 先用 axios 取一遍是为了拿到真实的加载中/失败态（iframe 对 PDF 没有可靠的 onerror，
// 后端返回的 JSON 错误会被直接渲染成文本），响应带 max-age=300，iframe 那次是缓存命中。
const frameUrl = ref('')

const tabs = computed(() => [
  { key: 'agree', label: '使用协议', icon: '📜', has: !!props.subject.hasAgree },
  { key: 'student', label: '学生协议', icon: '🎓', has: !!props.subject.hasStudent }
])

const currentHas = computed(() =>
  activeKey.value === 'agree' ? !!props.subject.hasAgree : !!props.subject.hasStudent)
const canOpen = computed(() => !!frameUrl.value && !loading.value && !error.value)

const downloadHref = computed(() => props.subject.id
  ? agreementUrl({ id: props.subject.id, kind: activeKey.value, mode: 'download' })
  : undefined)

// 与后端 Content-Disposition 里的名字保持一致（同源，download 属性会被采纳）
const downloadName = computed(() => {
  const label = activeKey.value === 'agree' ? '使用协议' : '学生协议'
  const who = props.subject.adress || props.subject.id || 'agreement'
  return `${who}_${label}.pdf`
})

const hint = computed(() => {
  if (loading.value) return '加载中…'
  if (!currentHas.value) return '该单位未上传此协议'
  if (error.value) return ''
  return '滚轮翻页，缩放与打印用阅读器自带的控件'
})

async function load() {
  frameUrl.value = ''
  error.value = ''
  notPdf.value = false
  if (!props.subject.id || !currentHas.value) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const res = await getAgreementBlob({ id: props.subject.id, kind: activeKey.value })
    const body = res.data
    // 后端出错时回的是 JSON，responseType=blob 会把它也包成 blob，要读出来看
    if (body.type && body.type.includes('application/json')) {
      const txt = await body.text()
      let msg = '协议加载失败'
      try {
        msg = JSON.parse(txt).message || msg
      } catch (e) { /* 非 JSON 就用默认文案 */ }
      error.value = msg
      return
    }
    if (body.type !== 'application/pdf') {
      notPdf.value = true
      error.value = '无法在线预览'
      return
    }
    frameUrl.value = agreementUrl({ id: props.subject.id, kind: activeKey.value })
  } catch (e) {
    error.value = e?.response?.data?.message || e.message || '协议加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.modelValue, (open) => {
  if (!open) {
    // 关掉时清空，避免下次打开先闪一眼上一份协议
    frameUrl.value = ''
    return
  }
  const wanted = props.initialKey === 'student' ? 'student' : 'agree'
  const hasWanted = wanted === 'agree' ? props.subject.hasAgree : props.subject.hasStudent
  // 想看的那类没上传时，落到另一类
  activeKey.value = hasWanted ? wanted : (props.subject.hasAgree ? 'agree' : 'student')
  load()
})

watch(activeKey, () => {
  if (props.modelValue) load()
})

function close() {
  emit('update:modelValue', false)
}
</script>

<style scoped>
/* ---------- 头部 ---------- */
.ag-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.ag-head-main {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.ag-head-icon {
  font-size: 26px;
  filter: drop-shadow(0 2px 6px rgba(0, 0, 0, 0.2));
}
.ag-head-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.ag-head-text strong {
  color: #17233d;
  font-size: 17px;
  letter-spacing: 0.3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ag-head-sub {
  /* 白底上 #8a95b0 只有 3.0，偏弱；换成一档更实的灰蓝（4.68，AA） */
  color: #68748f;
  font-size: 12.5px;
}
.ag-close {
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
.ag-close:hover {
  background: #e6ebf5;
  transform: rotate(90deg);
}

/* 标题栏底色由 theme.css 的 .el-dialog__header 统一给（现在是白底），
   所以这里不能再沿用原来「白字压在品牌渐变上」的配色，改成深色系 */

/* ---------- 主体 ---------- */
.ag-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.ag-tabs {
  display: flex;
  gap: 10px;
}
.ag-tab {
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
.ag-tab:hover:not(:disabled) {
  border-color: var(--brand-start);
  color: var(--brand-start);
  transform: translateY(-1px);
}
.ag-tab.active {
  border-color: transparent;
  background: var(--brand-grad);
  color: #fff;
  box-shadow: 0 8px 18px rgba(63, 140, 255, 0.3);
}
.ag-tab.empty {
  opacity: 0.5;
  cursor: not-allowed;
}
.ag-tab-icon { font-size: 15px; }
.ag-tab-flag {
  padding: 1px 7px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.25);
  font-size: 11px;
  font-weight: 500;
}

.ag-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(63, 140, 255, 0.07), rgba(108, 99, 255, 0.07));
}
.ag-hint {
  color: #5a6688;
  font-size: 12.5px;
}
.ag-tool-group {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ag-tool {
  min-width: 34px;
  height: 32px;
  padding: 0 14px;
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
.ag-tool:hover:not(:disabled):not(.disabled) {
  border-color: var(--brand-start);
  color: var(--brand-start);
  box-shadow: 0 6px 14px rgba(63, 140, 255, 0.16);
}
.ag-tool:disabled,
.ag-tool.disabled {
  opacity: 0.45;
  cursor: not-allowed;
  pointer-events: none;
}
.ag-tool-primary {
  border-color: transparent;
  background: var(--brand-grad);
  color: #fff;
}
.ag-tool-primary:hover:not(:disabled) {
  color: #fff;
  box-shadow: 0 10px 20px rgba(63, 140, 255, 0.34);
}

/* 画布：PDF 交给浏览器原生阅读器渲染 */
.ag-stage {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 68vh;
  overflow: hidden;
  border-radius: 18px;
  background: linear-gradient(135deg, #0f1729 0%, #16233d 100%);
}
.ag-frame {
  width: 100%;
  height: 100%;
  border: none;
  border-radius: 18px;
  background: #fff;
}

.ag-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 28px;
  color: rgba(220, 231, 255, 0.9);
  text-align: center;
}
.ag-state strong {
  font-size: 15px;
  color: #eaf1ff;
}
.ag-state-icon {
  font-size: 40px;
  opacity: 0.85;
}
.ag-state-tip {
  max-width: 400px;
  color: rgba(190, 205, 235, 0.75);
  font-size: 12.5px;
  line-height: 1.65;
}
.ag-state-error .ag-tool {
  margin-top: 6px;
  background: rgba(255, 255, 255, 0.14);
  border-color: rgba(255, 255, 255, 0.22);
  color: #eaf1ff;
}
.ag-state-error .ag-tool:hover {
  background: rgba(255, 255, 255, 0.24);
  color: #fff;
}
.ag-spinner {
  width: 34px;
  height: 34px;
  border: 3px solid rgba(255, 255, 255, 0.18);
  border-top-color: var(--brand-cyan);
  border-radius: 50%;
  animation: agSpin 0.8s linear infinite;
}
@keyframes agSpin {
  to { transform: rotate(360deg); }
}
</style>
