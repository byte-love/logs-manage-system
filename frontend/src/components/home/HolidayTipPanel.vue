<template>
  <section class="holiday-panel">
    <header class="page-head">
      <button type="button" class="back-button" @click="emit('close')">
        <span class="arw">‹</span>返回
      </button>
      <h2>节假日公告</h2>
      <span class="head-sub">老版网站注册流程页提示</span>
      <button type="button" class="help-button" @click="helpVisible = true">
        <span class="help-mark">?</span>使用说明
      </button>
    </header>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
    />

    <!-- 主视觉：现在用户看到的那句话放在最显眼的位置 -->
    <section class="hero" :class="`is-${current.status}`">
      <div class="hero-top">
        <span class="hero-badge"><i class="dot" />{{ statusText }}</span>
        <span v-if="current.exists" class="hero-chip">{{ current.startDate }} → {{ current.endDate }}</span>
        <div class="hero-tools">
          <button
            type="button"
            class="hero-btn"
            title="重新读取当前公告"
            :disabled="loading"
            @click="load"
          >
            {{ loading ? '读取中' : '刷新' }}
          </button>
          <button
            v-if="current.status === 'active'"
            type="button"
            class="hero-btn is-danger"
            :disabled="disabling"
            @click="handleDisable"
          >停用公告</button>
        </div>
      </div>

      <p class="hero-tip">{{ current.tip || '—' }}</p>

      <div class="hero-foot">
        <span>{{ lastSaveText || '尚未通过本工具改过' }}</span>
      </div>

      <div v-if="notifyFailed" class="hero-fail">
        <span>上次的热更新通知未成功，用户端可能还没变。</span>
        <button type="button" :disabled="notifying" @click="handleNotify">重新通知生效</button>
      </div>

      <div v-if="backupIssue" class="hero-fail">
        <span>{{ backupIssue }}</span>
        <button type="button" :disabled="backingUp" @click="handleBackup">重新备份</button>
      </div>
    </section>

    <div class="workbench">
      <section class="card">
        <div class="sec">
          <div class="sec-head">
            <h3>有效期</h3>
            <span v-if="dayCount" class="sec-meta">共 {{ dayCount }} 天</span>
          </div>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="→"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :shortcuts="shortcuts"
            class="range-picker"
            @change="onRangeChange"
          />
        </div>

        <div class="sec">
          <div class="sec-head">
            <h3>公告文案</h3>
            <button type="button" class="link" @click="resetText">恢复默认</button>
          </div>
          <div class="chips">
            <button type="button" class="chip is-start" @click="insertDate('start')">开始日期</button>
            <button type="button" class="chip is-end" @click="insertDate('end')">结束日期</button>
            <button type="button" class="chip is-audit" @click="insertDate('audit')">审核日期</button>
          </div>
          <el-input
            ref="textRef"
            v-model="form.text"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            resize="none"
          />
        </div>

        <div class="sec is-last">
          <div class="sec-head">
            <h3>预览</h3>
            <button type="button" class="link" @click="copyTip">复制文案</button>
          </div>
          <div class="browser">
            <div class="browser-bar">
              <span class="tl-dot is-red" />
              <span class="tl-dot is-amber" />
              <span class="tl-dot is-green" />
              <span class="browser-url">login.api.example.com</span>
            </div>
            <div class="browser-body">
              <p class="browser-line">{{ form.text || '—' }}</p>
            </div>
          </div>
        </div>
      </section>

      <aside class="card history-card">
        <div class="sec-head">
          <h3>改动记录</h3>
          <span v-if="history.length" class="sec-meta">{{ history.length }} 条</span>
        </div>
        <ul v-if="history.length" class="rail">
          <li v-for="(item, index) in history" :key="`${item.time}-${index}`" class="rail-item">
            <i class="rail-dot" :class="`d-${item.action}`" />
            <div class="rail-body">
              <div class="rail-head">
                <span class="pill" :class="`p-${item.action}`">{{ actionText(item.action) }}</span>
                <time>{{ toBeijingMinute(item.time) }}</time>
                <button type="button" class="link is-tiny" @click="restoreHistory(item)">回填</button>
              </div>
              <p class="rail-tip">{{ item.tip || '—' }}</p>
              <p class="rail-sub">
                <span v-if="item.startDate">{{ item.startDate }} → {{ item.endDate }}</span>
                <span>{{ item.operator || '—' }}</span>
              </p>
            </div>
          </li>
        </ul>
        <p v-else class="history-empty">暂无改动记录</p>
      </aside>
    </div>

    <footer class="action-bar">
      <p class="action-hint" :class="{ 'is-warn': hintIsWarn }">{{ actionHint }}</p>
      <el-button type="primary" size="large" :loading="saving" @click="handleSave">保存并生效</el-button>
    </footer>

    <el-drawer
      v-model="helpVisible"
      title="节假日公告 · 使用说明"
      direction="rtl"
      size="480px"
      append-to-body
      class="holiday-help"
    >
      <div class="help">
        <p class="help-lead">
          这个工具改的是<strong>老版网站（login.api.example.com）注册流程页顶部</strong>那句红字提示 ——
          放假期间提交的注册申请什么时候统一审核。改完，访客一打开注册页就会看到。
        </p>

        <section class="help-sec">
          <h4>三步完成</h4>
          <ol class="help-list">
            <li>先看最上面那块：现在是<strong>生效中 / 已过期 / 未设置</strong>，以及用户正在看到的那句话。</li>
            <li>在「<strong>有效期</strong>」选起止日期；要改措辞就在「<strong>公告文案</strong>」里改，下面「<strong>预览</strong>」就是用户看到的样子。</li>
            <li>点右下角「<strong>保存并生效</strong>」，约 <strong>2 分钟</strong>后网站上的用户就能看到。</li>
          </ol>
        </section>

        <section class="help-sec">
          <h4>界面上每一块是干什么的</h4>
          <ul class="help-list is-define">
            <li><b>顶部状态区</b>：当前公告、状态和起止日期。右上角「刷新」是把库里的内容重新读一遍（别人用桌面端改过时用这个看最新的）；「停用公告」让用户立刻看不到，文字会保留。</li>
            <li><b>有效期</b>：选开始和结束日期。日历面板里有「从今天起 7 / 15 / 30 天」的快捷项。<strong>日期一改，文案里对应的日期会自动跟着变</strong>，不用手动改字。</li>
            <li><b>公告文案</b>：直接编辑给用户看的最终文字。三个彩色按钮把「开始日期 / 结束日期 / 审核日期」的<strong>真实日期</strong>插到光标处；「恢复默认」换回标准措辞（按当前日期填好）。</li>
            <li><b>预览</b>：做成网站窗口的样子，里面就是用户会看到的那句话。右上角「复制文案」可以复制去发邮件或群通知。</li>
            <li><b>改动记录</b>：最近 20 次改动 —— 谁、什么时候、改成了什么。「<strong>回填</strong>」是把某一次的内容填回编辑器（<strong>只是填好，不生效</strong>，还要再点保存）。</li>
            <li><b>底部横条</b>：左边一句提示（保存后多久生效、用户能看到哪几天），右边就是「保存并生效」。</li>
            <li><b>重新通知生效</b>：只在「上次保存没通知成功」时出现，点它是补发一次通知。</li>
            <li><b>重新备份</b>：只在「备份未同步」或「备份状态未知」时出现，点它把当前公告重新备份一份，不通知网站。</li>
          </ul>
        </section>

        <section class="help-sec">
          <h4>两条必须记住的规矩</h4>
          <div class="help-note">
            <p><strong>① 保存那一刻就生效，开始日期不控制显示。</strong>
              写一段未来的假期，保存后用户<strong>立刻</strong>就看到这条（约 2 分钟刷新后），
              这条公告<strong>不能"预约"</strong>。</p>
            <p><strong>② 结束日当天用户就看不到了。</strong>
              最后能看到的是结束日<strong>前一天</strong>。想让某天也在可见范围内，
              结束日期就要往后选一天 —— 例：想让 1 号到 7 号都能看到，结束日期选 <strong>7 号的后一天</strong>。</p>
          </div>
        </section>

        <section class="help-sec">
          <h4>关于备份</h4>
          <p>每次保存或停用，都会把这条公告<strong>同时备份一份到DemoText的数据库</strong>（老站和桌面端都不读它）。
            备份失败<strong>不影响</strong>公告已经生效，顶部会显示「备份未同步」，点一下「重新备份」即可补上。</p>
        </section>

        <section class="help-sec">
          <h4>常见操作</h4>
          <ul class="help-list is-define">
            <li><b>放假前挂公告</b>：选好区间 → 保存 → 2 分钟后自己上网站看一眼。</li>
            <li><b>假期临时延长</b>：把结束日期往后改 → 保存（文案里的日期会自动跟着变）。</li>
            <li><b>假期结束想撤掉</b>：点「停用公告」，用户立刻不再看到，文字留着下次能复用。</li>
            <li><b>改错了想退回上一版</b>：在「改动记录」里找到上一条 → 回填 → 保存。</li>
            <li><b>保存了但网站没变</b>：先等满 2 分钟；还不变，看顶部有没有「重新通知生效」并点一下；仍然不行就联系维护人。</li>
            <li><b>顶部提示备份未同步</b>：点「重新备份」再存一份即可，公告本身不受影响。</li>
          </ul>
        </section>

        <p class="help-foot">
          桌面端的「节假日公告」改的是同一条公告，两边谁后保存谁生效。
        </p>
      </div>
    </el-drawer>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  backupHolidayTip,
  disableHolidayTip,
  getHolidayTip,
  notifyHolidayTip,
  previewHolidayTip,
  saveHolidayTip
} from '../../api/holidayTip'
import { getLoginUser } from '../../utils/auth'
import { toBeijingMinute } from '../../utils/format'

const emit = defineEmits(['close'])

const loading = ref(false)
const saving = ref(false)
const disabling = ref(false)
const notifying = ref(false)
const backingUp = ref(false)
const error = ref('')
const history = ref([])
const defaultTemplate = ref('')
const textRef = ref(null)
const helpVisible = ref(false)

const current = reactive({
  exists: false,
  tip: '',
  startDate: '',
  endDate: '',
  lastVisibleDate: '',
  status: 'unset',
  lastSaveAt: '',
  lastOperator: '',
  // 备份那份（DemoText库）是不是就是当前这条：true/false，读不到是 null
  backupSynced: null
})

/** text 是「最终文案」（日期已替换好）—— 管理员不接触 #STARTDATE# 这类占位符 */
const form = reactive({ startDate: '', endDate: '', text: '' })

/** 上一次用于渲染文案的日期。改动区间时按它把正文里的旧日期就地换成新日期 */
const rendered = reactive({ start: '', end: '' })

const STATUS_TEXT = { active: '生效中', expired: '已过期', unset: '未设置' }
const statusText = computed(() => STATUS_TEXT[current.status] || '未设置')

const lastSaveText = computed(() => {
  if (!current.lastSaveAt) return ''
  const who = current.lastOperator ? ` · ${current.lastOperator}` : ''
  return `上次保存 ${toBeijingMinute(current.lastSaveAt)}${who}`
})

/** 最近一次动作是「通知失败」时给一个补救入口（保存成功但 145 没写进去） */
const notifyFailed = computed(() => history.value.length > 0 && history.value[0].notified === false)

/** 备份那份不新鲜时的提示；读不到就说状态未知，不假装正常也不谎报失败 */
const backupIssue = computed(() => {
  if (!current.exists) return ''
  if (current.backupSynced === true) return ''
  if (current.backupSynced === false) return '备份未同步。'
  return '备份状态未知。'
})

/** 一个控件管住起止区间，比两个日期框 + 快捷按钮干净 */
const dateRange = computed({
  get() {
    return form.startDate && form.endDate ? [form.startDate, form.endDate] : []
  },
  set(value) {
    if (Array.isArray(value) && value.length === 2) {
      form.startDate = value[0] || ''
      form.endDate = value[1] || ''
    } else {
      form.startDate = ''
      form.endDate = ''
    }
  }
})

/** 与老站一致：结束日当天就不显示了，所以最后可见日是它的前一天 */
const lastVisible = computed(() => (form.endDate ? shiftDay(form.endDate, -1) : ''))

const dayCount = computed(() => {
  if (!form.startDate || !form.endDate) return 0
  const start = new Date(`${form.startDate}T00:00:00`)
  const end = new Date(`${form.endDate}T00:00:00`)
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return 0
  return Math.round((end - start) / 86400000) + 1
})

/** 底部操作条左侧那句：给的是结论，不是解释 */
const actionHint = computed(() => {
  if (!form.startDate || !form.endDate) return '选择日期后即可保存'
  if (form.endDate <= todayIso()) return '结束日期已过，保存后用户看不到这条公告'
  return `保存后约 2 分钟对用户生效，用户可见至 ${lastVisible.value}`
})

const hintIsWarn = computed(() => !!form.endDate && form.endDate <= todayIso())

const shortcuts = [
  { text: '从今天起 7 天', value: () => rangeFromToday(7) },
  { text: '从今天起 15 天', value: () => rangeFromToday(15) },
  { text: '从今天起 30 天', value: () => rangeFromToday(30) }
]

function rangeFromToday(days) {
  const start = new Date()
  start.setHours(0, 0, 0, 0)
  const end = new Date(start)
  end.setDate(end.getDate() + days - 1)
  return [start, end]
}

function operatorName() {
  const user = getLoginUser() || {}
  return user.displayName || user.username || ''
}

function actionText(action) {
  if (action === 'save') return '保存'
  if (action === 'disable') return '停用'
  if (action === 'notify') return '重新通知'
  if (action === 'notify-failed') return '通知失败'
  if (action === 'backup') return '重新备份'
  if (action === 'backup-failed') return '备份失败'
  return action || '—'
}

function todayIso() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

/** 日期加减只走本地日期分量 —— 用 toISOString 会按 UTC 偏移，UTC+8 下会把日期挪错一天 */
function shiftDay(iso, delta) {
  const parts = String(iso).split('-').map(Number)
  const date = new Date(parts[0], parts[1] - 1, parts[2] + delta)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/** 与后端 cnDate 同一口径（%02d月%02d日），占位符按钮插的就是这个最终文本 */
function dateText(iso, plusDays = 0) {
  if (!iso) return ''
  const parts = String(iso).split('-').map(Number)
  if (parts.length !== 3 || parts.some((n) => Number.isNaN(n))) return ''
  const date = new Date(parts[0], parts[1] - 1, parts[2] + plusDays)
  return `${String(date.getMonth() + 1).padStart(2, '0')}月${String(date.getDate()).padStart(2, '0')}日`
}

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 改了区间就把正文里的旧日期换成新的：一次遍历替换（不是连着 replace 三次），
 * 否则新写进去的日期可能被后一次替换再改一遍。顺序按「审核日 → 结束日 → 开始日」入表，
 * 用 Map 查表替换，谁先谁后都不影响结果。
 */
function substituteDates(text, oldStart, oldEnd, newStart, newEnd) {
  const pairs = [
    [dateText(oldEnd, 1), dateText(newEnd, 1)],
    [dateText(oldEnd, 0), dateText(newEnd, 0)],
    [dateText(oldStart, 0), dateText(newStart, 0)]
  ].filter((pair) => pair[0] && pair[1] && pair[0] !== pair[1])
  if (!pairs.length) return text
  const map = new Map(pairs)
  const pattern = new RegExp(Array.from(map.keys()).map(escapeRegExp).join('|'), 'g')
  return text.replace(pattern, (hit) => map.get(hit))
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await getHolidayTip()
    const data = response.data || {}
    if (data.success === false) {
      error.value = data.message || '公告读取失败'
      return
    }
    defaultTemplate.value = data.defaultTemplate || ''
    Object.assign(current, {
      exists: !!data.exists,
      tip: data.tip || '',
      startDate: data.startDate || '',
      endDate: data.endDate || '',
      lastVisibleDate: data.lastVisibleDate || '',
      status: data.status || 'unset',
      lastSaveAt: data.lastSaveAt || '',
      lastOperator: data.lastOperator || '',
      backupSynced: data.exists && typeof data.backupSynced === 'boolean' ? data.backupSynced : null
    })
    history.value = Array.isArray(data.history) ? data.history : []
    if (data.exists && data.tip) {
      form.startDate = data.startDate || ''
      form.endDate = data.endDate || ''
      form.text = data.tip
    } else {
      // 一条公告都没有：直接给一份「今天起 7 天 + 默认文案」的可编辑初值，不留占位符
      form.startDate = data.defaultStartDate || todayIso()
      form.endDate = data.defaultEndDate || shiftDay(todayIso(), 6)
      form.text = data.defaultText || ''
    }
    rendered.start = form.startDate
    rendered.end = form.endDate
  } catch (err) {
    error.value = err.response?.data?.message || err.message || '公告读取失败'
  } finally {
    loading.value = false
  }
}

/** 只在用户真的改了区间时同步正文日期（程序化赋值不会触发 el-date-picker 的 change） */
function onRangeChange() {
  if (rendered.start && rendered.end && form.text) {
    form.text = substituteDates(form.text, rendered.start, rendered.end, form.startDate, form.endDate)
  }
  rendered.start = form.startDate
  rendered.end = form.endDate
}

function insertAtCursor(token) {
  const el = textRef.value?.textarea || textRef.value?.$el?.querySelector('textarea')
  if (!el) {
    form.text = `${form.text}${token}`
    return
  }
  const start = el.selectionStart ?? form.text.length
  const end = el.selectionEnd ?? start
  form.text = `${form.text.slice(0, start)}${token}${form.text.slice(end)}`
  nextTick(() => {
    el.focus()
    const caret = start + token.length
    el.setSelectionRange(caret, caret)
  })
}

/** 三个按钮插的是「真实日期」，不是占位符 */
function insertDate(kind) {
  const value =
    kind === 'start' ? dateText(form.startDate)
      : kind === 'end' ? dateText(form.endDate)
        : dateText(form.endDate, 1)
  if (!value) {
    ElMessage.warning('请先选择有效期')
    return
  }
  insertAtCursor(value)
}

/** 恢复默认：把默认模板按当前区间渲染好再放进编辑器（渲染仍由后端那一段代码负责） */
async function resetText() {
  if (!form.startDate || !form.endDate) {
    ElMessage.warning('请先选择有效期')
    return
  }
  try {
    const response = await previewHolidayTip({
      startDate: form.startDate,
      endDate: form.endDate,
      template: defaultTemplate.value
    })
    const text = response.data?.tip
    if (text) {
      form.text = text
      rendered.start = form.startDate
      rendered.end = form.endDate
    }
  } catch (err) {
    ElMessage.error(err.response?.data?.message || err.message || '默认文案获取失败')
  }
}

function restoreHistory(item) {
  if (item.startDate) form.startDate = item.startDate
  if (item.endDate) form.endDate = item.endDate
  if (item.tip) form.text = item.tip
  rendered.start = form.startDate
  rendered.end = form.endDate
  ElMessage.success('已回填到编辑器')
}

async function copyTip() {
  const text = form.text || current.tip
  if (!text) {
    ElMessage.warning('暂无可复制的文案')
    return
  }
  // 内网是 http，非安全上下文下没有 navigator.clipboard，退回 execCommand
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      ElMessage.success('已复制')
      return
    } catch (_) {
      /* 落到下面的兜底 */
    }
  }
  const holder = document.createElement('textarea')
  holder.value = text
  holder.style.position = 'fixed'
  holder.style.opacity = '0'
  document.body.appendChild(holder)
  holder.select()
  try {
    document.execCommand('copy')
    ElMessage.success('已复制')
  } catch (_) {
    ElMessage.error('复制失败，请手动选择文本')
  } finally {
    document.body.removeChild(holder)
  }
}

async function handleSave() {
  if (!form.startDate || !form.endDate) {
    ElMessage.warning('请选择开始日期和结束日期')
    return
  }
  if (form.endDate < form.startDate) {
    ElMessage.warning('结束日期不能早于开始日期')
    return
  }
  const text = (form.text || '').trim()
  if (!text) {
    ElMessage.warning('公告文案不能为空')
    return
  }

  const expired = form.endDate <= todayIso()
  const body =
    '<p class="mb-lead">公告将保存为：</p>' +
    `<p style="margin:8px 0 0;padding:10px 12px;border-radius:10px;background:#f4f7fd;color:#1f2a44;font-size:13.5px;line-height:1.7;word-break:break-all;">${escapeHtml(text)}</p>` +
    (expired
      ? '<div class="mb-alert"><span class="mb-alert-ico">⚠️</span><span>结束日期已过，保存后用户看不到这条公告。</span></div>'
      : `<div class="mb-alert"><span class="mb-alert-ico">⚠️</span><span>保存后约 2 分钟对用户生效，用户在 <b>${form.startDate}</b> 至 <b>${lastVisible.value}</b> 期间可见。</span></div>`)

  try {
    await ElMessageBox.confirm(body, '保存节假日公告', {
      dangerouslyUseHTMLString: true,
      type: 'warning',
      confirmButtonText: '确认保存',
      cancelButtonText: '取消',
      customClass: 'utility-export-confirm'
    })
  } catch (_) {
    return
  }

  saving.value = true
  try {
    const response = await saveHolidayTip({
      startDate: form.startDate,
      endDate: form.endDate,
      tip: text,
      operator: operatorName()
    })
    const data = response.data || {}
    if (data.success === false) {
      ElMessage.error(data.message || '保存失败')
      return
    }
    // 通知和备份是两个独立失败域，任一没成就不是「一切正常」，别用成功色糊过去
    if (data.notified === false || data.backedUp === false) {
      ElMessage.warning(data.message || '已保存，但有一步没成功')
    } else {
      ElMessage.success(data.message || '已保存')
    }
    await load()
  } catch (err) {
    ElMessage.error(err.response?.data?.message || err.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleDisable() {
  try {
    await ElMessageBox.confirm(
      '<p class="mb-lead">停用后用户将不再看到当前公告。</p>' +
        '<p class="mb-sub">公告文案会保留，重新设置日期即可恢复显示。</p>',
      '停用公告',
      {
        dangerouslyUseHTMLString: true,
        type: 'warning',
        confirmButtonText: '确认停用',
        cancelButtonText: '取消',
        customClass: 'utility-export-confirm'
      }
    )
  } catch (_) {
    return
  }
  disabling.value = true
  try {
    const response = await disableHolidayTip({ operator: operatorName() })
    const data = response.data || {}
    if (data.success === false) {
      ElMessage.error(data.message || '停用失败')
      return
    }
    if (data.notified === false || data.backedUp === false) {
      ElMessage.warning(data.message || '已停用，但有一步没成功')
    } else {
      ElMessage.success(data.message || '已停用')
    }
    await load()
  } catch (err) {
    ElMessage.error(err.response?.data?.message || err.message || '停用失败')
  } finally {
    disabling.value = false
  }
}

async function handleNotify() {
  notifying.value = true
  try {
    const response = await notifyHolidayTip({ operator: operatorName() })
    const data = response.data || {}
    if (data.success === false) {
      ElMessage.error(data.message || '通知失败')
      return
    }
    ElMessage.success(data.message || '已通知')
    await load()
  } catch (err) {
    ElMessage.error(err.response?.data?.message || err.message || '通知失败')
  } finally {
    notifying.value = false
  }
}

/** 只补备份那份，不碰公告也不通知网站 */
async function handleBackup() {
  backingUp.value = true
  try {
    const response = await backupHolidayTip({ operator: operatorName() })
    const data = response.data || {}
    if (data.success === false) {
      ElMessage.error(data.message || '备份失败')
      return
    }
    ElMessage.success(data.message || '已备份')
    await load()
  } catch (err) {
    ElMessage.error(err.response?.data?.message || err.message || '备份失败')
  } finally {
    backingUp.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/* 整页一个柔和的底面：上方深色主视觉和下方浅色卡片都落在这块底色上，不会一半深一半惨白 */
.holiday-panel {
  --ink: #16224a;
  --ink-2: #4e5b7c;
  --muted: #8b96ae;
  --hair: rgba(99, 122, 165, 0.14);
  --card: rgba(255, 255, 255, 0.92);
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px;
  border-radius: 24px;
  background: linear-gradient(180deg, #e7ecf8 0%, #f0f4fb 34%, #f6f8fd 100%);
}

.page-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.page-head .back-button {
  display: inline-flex;
  align-items: center;
  padding: 6px 12px;
  border: 1px solid var(--hair);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.9);
  color: var(--ink-2);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.18s ease;
}
.page-head .back-button:hover {
  border-color: rgba(63, 140, 255, 0.4);
  color: #2563eb;
  box-shadow: 0 4px 14px -6px rgba(63, 140, 255, 0.5);
}
.page-head .arw {
  margin-right: 4px;
  font-size: 15px;
  line-height: 1;
}
.page-head h2 {
  margin: 0;
  color: var(--ink);
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 0.2px;
}
.page-head .head-sub {
  color: var(--muted);
  font-size: 12.5px;
}
.page-head .help-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
  padding: 6px 13px;
  border: 1px solid rgba(63, 140, 255, 0.28);
  border-radius: 999px;
  background: rgba(63, 140, 255, 0.08);
  color: #1f5fd0;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.18s ease;
}
.page-head .help-button:hover {
  background: rgba(63, 140, 255, 0.16);
  border-color: rgba(63, 140, 255, 0.45);
}
.page-head .help-mark {
  display: grid;
  place-items: center;
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: #3f8cff;
  color: #fff;
  font-size: 10.5px;
  font-weight: 700;
  line-height: 1;
}

/* ---------- 使用说明（抽屉内） ---------- */
.help {
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.85;
}
.help-lead {
  margin: 0 0 18px;
  padding: 12px 14px;
  border-radius: 12px;
  background: linear-gradient(135deg, rgba(63, 140, 255, 0.1), rgba(108, 99, 255, 0.1));
  color: #2c3c66;
}
.help-lead strong {
  color: #1f5fd0;
}
.help-sec {
  margin-bottom: 20px;
}
.help-sec h4 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  color: var(--ink);
  font-size: 13.5px;
  font-weight: 700;
}
.help-sec h4::before {
  content: '';
  width: 4px;
  height: 13px;
  border-radius: 2px;
  background: linear-gradient(180deg, #3f8cff, #725cff);
}
.help-list {
  margin: 0;
  padding-left: 20px;
}
.help-list li {
  margin-bottom: 8px;
}
.help-list li b {
  color: var(--ink);
}
.help-list.is-define {
  padding-left: 0;
  list-style: none;
}
.help-list.is-define li {
  padding-left: 16px;
  position: relative;
}
.help-list.is-define li::before {
  content: '';
  position: absolute;
  top: 9px;
  left: 4px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: rgba(63, 140, 255, 0.55);
}
.help-note {
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid rgba(255, 154, 60, 0.32);
  background: rgba(255, 154, 60, 0.1);
}
.help-note p {
  margin: 0 0 10px;
  color: #8a5410;
}
.help-note p:last-child {
  margin-bottom: 0;
}
.help-note strong {
  color: #b5620f;
}
.help-foot {
  margin: 0;
  padding-top: 14px;
  border-top: 1px solid var(--hair);
  color: var(--muted);
  font-size: 12.5px;
}

/* ---------- 主视觉 ---------- */
.hero {
  position: relative;
  overflow: hidden;
  padding: 20px 22px 16px;
  border-radius: 20px;
  color: #fff;
  background: linear-gradient(135deg, #2f3a5c 0%, #3d4870 58%, #333d61 100%);
  box-shadow: 0 18px 40px -22px rgba(38, 48, 82, 0.55);
}
.hero.is-active {
  background: linear-gradient(135deg, #3f8cff 0%, #6c63ff 55%, #8b5cf6 100%);
  box-shadow: 0 20px 44px -20px rgba(76, 96, 255, 0.55);
}
.hero::after {
  content: '';
  position: absolute;
  top: -70px;
  right: -60px;
  width: 240px;
  height: 240px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.26), rgba(255, 255, 255, 0) 70%);
  pointer-events: none;
}

.hero-top {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 4px 12px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.3px;
}
.hero-badge .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 0 4px rgba(255, 255, 255, 0.22);
}
/* 状态色只放这一枚徽章上：已过期=琥珀、未设置=浅灰蓝、生效中=白 */
.hero.is-expired .hero-badge {
  background: rgba(255, 179, 71, 0.18);
  border-color: rgba(255, 179, 71, 0.45);
  color: #ffd08a;
}
.hero.is-expired .hero-badge .dot {
  background: #ffb347;
  box-shadow: 0 0 0 4px rgba(255, 179, 71, 0.16);
}
.hero.is-unset .hero-badge {
  background: rgba(255, 255, 255, 0.12);
  border-color: rgba(255, 255, 255, 0.22);
  color: #cdd6ea;
}
.hero.is-unset .hero-badge .dot {
  background: #aab6d0;
  box-shadow: none;
}
.hero-chip {
  padding: 4px 11px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
}
.hero-tools {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.hero-btn {
  padding: 5px 13px;
  border: 1px solid rgba(255, 255, 255, 0.28);
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  font-size: 12.5px;
  cursor: pointer;
  transition: all 0.18s ease;
}
.hero-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.22);
}
.hero-btn:disabled {
  opacity: 0.55;
  cursor: default;
}
.hero-btn.is-danger:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.92);
  border-color: transparent;
  color: #d9480f;
}

.hero-tip {
  position: relative;
  margin: 16px 0 0;
  padding: 14px 18px;
  border-radius: 14px;
  border-left: 3px solid rgba(255, 255, 255, 0.55);
  background: rgba(255, 255, 255, 0.14);
  font-size: 15.5px;
  line-height: 1.85;
  letter-spacing: 0.2px;
  word-break: break-all;
}
.hero.is-expired .hero-tip {
  border-left-color: #ffb347;
}
.hero-foot {
  margin-top: 12px;
  font-size: 12px;
  opacity: 0.72;
}
.hero-fail {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 8px 12px;
  border-radius: 11px;
  background: rgba(255, 255, 255, 0.16);
  font-size: 12.5px;
}
.hero-fail button {
  padding: 0;
  border: none;
  background: none;
  color: #fff;
  font-size: 12.5px;
  text-decoration: underline;
  cursor: pointer;
}

/* ---------- 工作台 ---------- */
.workbench {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 330px);
  gap: 16px;
  align-items: start;
}
@media (max-width: 1180px) {
  .workbench {
    grid-template-columns: minmax(0, 1fr);
  }
}

.card {
  padding: 16px;
  border-radius: 18px;
  background: var(--card);
  border: 1px solid rgba(99, 122, 165, 0.12);
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.03), 0 16px 34px -26px rgba(28, 42, 80, 0.4);
  backdrop-filter: blur(4px);
}

/* 三段各自一块淡底（跟整页同一个蓝灰色系），比原来只有一条分隔线更像"成组的编辑区" */
.sec {
  padding: 15px 17px;
  border: 1px solid rgba(99, 122, 165, 0.12);
  border-radius: 14px;
  background: linear-gradient(180deg, rgba(248, 250, 254, 0.96), rgba(238, 243, 252, 0.92));
}
.sec + .sec {
  margin-top: 12px;
}
.sec-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 13px;
}
.sec-head h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--ink);
  font-size: 14.5px;
  font-weight: 600;
  letter-spacing: 0.2px;
}
.sec-head h3::before {
  content: '';
  width: 4px;
  height: 14px;
  border-radius: 2px;
  background: linear-gradient(180deg, #3f8cff, #725cff);
}
.history-card .sec-head h3::before {
  background: linear-gradient(180deg, #ffb457, #f08a3c);
}
.sec-meta {
  color: #5b7cc9;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.link {
  margin-left: auto;
  padding: 0;
  border: none;
  background: none;
  color: #2563eb;
  font-size: 12.5px;
  cursor: pointer;
}
.link:hover {
  text-decoration: underline;
}
.link.is-tiny {
  font-size: 11.5px;
}

.range-picker {
  width: 100%;
  max-width: 420px;
  border-radius: 12px;
  background: linear-gradient(90deg, rgba(63, 140, 255, 0.09), rgba(108, 99, 255, 0.09));
  box-shadow: 0 0 0 1px rgba(99, 132, 255, 0.3) inset;
  transition: box-shadow 0.18s ease;
}
.range-picker:hover {
  box-shadow: 0 0 0 1px rgba(99, 132, 255, 0.55) inset;
}
.range-picker :deep(.el-range-input) {
  color: #22406f;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.range-picker :deep(.el-range-input::placeholder) {
  color: #7f8db0;
  font-weight: 500;
}
.range-picker :deep(.el-range-separator) {
  color: #6c63ff;
  font-weight: 700;
}

.chips {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.chip {
  padding: 5px 12px;
  border: 1px solid var(--hair);
  border-radius: 999px;
  background: #f7f9fd;
  color: var(--ink-2);
  font-size: 12.5px;
  cursor: pointer;
  transition: all 0.18s ease;
}
.chip:hover {
  transform: translateY(-1px);
}
.chip.is-start {
  border-color: rgba(63, 140, 255, 0.32);
  background: rgba(63, 140, 255, 0.09);
  color: #1f5fd0;
}
.chip.is-end {
  border-color: rgba(139, 92, 246, 0.32);
  background: rgba(139, 92, 246, 0.09);
  color: #6d3fd4;
}
.chip.is-audit {
  border-color: rgba(20, 184, 166, 0.32);
  background: rgba(20, 184, 166, 0.1);
  color: #0f8a7c;
}
.chip.is-start:hover {
  background: rgba(63, 140, 255, 0.18);
}
.chip.is-end:hover {
  background: rgba(139, 92, 246, 0.18);
}
.chip.is-audit:hover {
  background: rgba(20, 184, 166, 0.2);
}

.browser {
  border: 1px solid var(--hair);
  border-radius: 14px;
  overflow: hidden;
  background: #fff;
}
.browser-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 12px;
  background: #eef2fa;
  border-bottom: 1px solid var(--hair);
}
.tl-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  opacity: 0.85;
}
.tl-dot.is-red {
  background: #ff6b60;
}
.tl-dot.is-amber {
  background: #ffc14d;
}
.tl-dot.is-green {
  background: #4fd07a;
}
.browser-url {
  margin-left: 10px;
  padding: 2px 12px;
  border: 1px solid var(--hair);
  border-radius: 999px;
  background: #fff;
  color: var(--muted);
  font-size: 11.5px;
}
.browser-body {
  padding: 24px 22px;
  text-align: center;
}
.browser-line {
  margin: 0;
  color: #c0392b;
  font-size: 13px;
  line-height: 1.9;
  word-break: break-all;
}

/* ---------- 改动记录 ---------- */
.history-card .sec-head {
  margin: 0 0 6px;
}
.rail {
  position: relative;
  list-style: none;
  margin: 0;
  padding: 6px 0 14px;
  max-height: 560px;
  overflow-y: auto;
}
.rail::before {
  content: '';
  position: absolute;
  top: 12px;
  bottom: 18px;
  left: 4px;
  width: 1px;
  background: var(--hair);
}
.rail-item {
  position: relative;
  display: flex;
  gap: 14px;
  padding: 9px 8px 9px 22px;
  border-radius: 12px;
  transition: background 0.18s ease;
}
.rail-item:hover {
  background: rgba(240, 244, 252, 0.9);
}
.rail-dot {
  position: absolute;
  top: 15px;
  left: 0;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #c3cbdb;
  box-shadow: 0 0 0 4px #fff;
}
.rail-dot.d-save {
  background: #3f8cff;
}
.rail-dot.d-disable {
  background: #ff9a3c;
}
.rail-dot.d-notify-failed {
  background: #ff6b60;
}
.rail-dot.d-backup,
.rail-dot.d-backup-failed {
  background: #8b5cf6;
}
.rail-dot.d-backup-failed {
  background: #ff6b60;
}
.rail-body {
  min-width: 0;
  flex: 1;
}
.rail-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.rail-head time {
  color: var(--muted);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}
.pill {
  padding: 1px 8px;
  border-radius: 999px;
  background: #eef2f9;
  color: #5a6b8c;
  font-size: 11.5px;
  font-weight: 600;
}
.pill.p-save {
  background: rgba(63, 140, 255, 0.14);
  color: #2563eb;
}
.pill.p-disable,
.pill.p-notify-failed {
  background: rgba(255, 154, 60, 0.16);
  color: #b5620f;
}
.pill.p-backup {
  background: rgba(139, 92, 246, 0.14);
  color: #6d3fd4;
}
.pill.p-backup-failed {
  background: rgba(255, 107, 96, 0.16);
  color: #c0392b;
}
.rail-tip {
  margin: 6px 0 0;
  color: var(--ink-2);
  font-size: 12.5px;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.rail-sub {
  display: flex;
  gap: 10px;
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 11.5px;
}
.history-empty {
  margin: 0;
  padding: 26px 0 30px;
  color: #b9c1d2;
  font-size: 13px;
  text-align: center;
}

/* ---------- 底部浮动操作条 ---------- */
.action-bar {
  position: sticky;
  bottom: 12px;
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(99, 122, 165, 0.16);
  box-shadow: 0 16px 36px -18px rgba(16, 24, 40, 0.36);
  backdrop-filter: blur(10px);
}
.action-hint {
  margin: 0;
  color: #4f6ba8;
  font-size: 12.5px;
  line-height: 1.6;
}
.action-hint.is-warn {
  color: #b5620f;
  font-weight: 600;
}
</style>
