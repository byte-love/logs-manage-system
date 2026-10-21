<template>
  <div class="sync-wrap">
    <!-- 顶部：整体状态 + 一键同步 -->
    <div class="sync-hero">
      <div class="sync-hero-left">
        <div class="sync-hero-title">
          <span class="sync-hero-icon">🔄</span>
          <div>
            <h3>数据同步</h3>
            <p class="sync-hero-sub">{{ heroSubtitle }}</p>
          </div>
        </div>
      </div>
      <div class="sync-hero-right">
        <!-- 后端 onMessage 是同步死循环，关连接不会中断服务端游标，
             无法真取消，所以同步中只禁用按钮、不给「取消」这种做不到的出口 -->
        <el-button
          type="primary"
          size="large"
          :loading="running"
          :disabled="running || selectedKeys.length === 0"
          @click="handleStart()"
        >{{ running ? '同步中…' : `同步选中（${selectedKeys.length}）` }}</el-button>
      </div>
    </div>

    <!-- 总进度条：同步中才出现 -->
    <transition name="sync-fade">
      <div v-if="running" class="sync-total">
        <div class="sync-total-head">
          <span class="sync-total-label">整体进度</span>
          <span class="sync-total-num">{{ totalPercent }}%</span>
        </div>
        <div class="sync-bar sync-bar-lg">
          <div class="sync-bar-fill" :style="{ width: totalPercent + '%' }"></div>
        </div>
        <div class="sync-total-foot">
          <span>{{ runningLabel }}</span>
          <span>已用 {{ formatDuration(elapsedMs) }}</span>
        </div>
        <div class="sync-warn">
          <span class="sync-warn-ico">⚠️</span>
          <span>同步进行中，请勿刷新或关闭页面；过程无法中途取消</span>
        </div>
      </div>
    </transition>

    <!-- 三张表卡片 -->
    <div class="sync-grid">
      <div
        v-for="t in tables"
        :key="t.key"
        class="sync-card"
        :class="{
          selected: selectedKeys.includes(t.key),
          busy: t.status === 'running',
          done: t.status === 'success',
          failed: t.status === 'error'
        }"
        @click="toggleSelect(t.key)"
      >
        <div class="sync-card-top">
          <div class="sync-card-id">
            <span class="sync-card-icon">{{ t.icon }}</span>
            <div class="sync-card-name">
              <strong>{{ t.name }}</strong>
              <span class="sync-card-code">{{ t.collection }}</span>
            </div>
          </div>
          <el-checkbox
            :model-value="selectedKeys.includes(t.key)"
            :disabled="running"
            @click.stop
            @change="toggleSelect(t.key)"
          />
        </div>

        <div class="sync-metrics">
          <div class="sync-metric">
            <span class="sync-metric-label">现有</span>
            <strong>{{ formatNumber(t.targetTotal) }}</strong>
          </div>
          <div class="sync-metric">
            <span class="sync-metric-label">本次</span>
            <strong :class="{ hot: t.processed > 0 }">
              {{ t.processed ? formatNumber(t.processed) : '—' }}
            </strong>
          </div>
        </div>

        <div class="sync-card-progress">
          <!-- 账号表有真实百分比；日志表只有已处理条数没有总数，用不确定态滚动条 -->
          <div class="sync-bar" :class="{ indeterminate: t.status === 'running' && !t.hasPercent }">
            <div
              class="sync-bar-fill"
              :class="{
                'is-error': t.status === 'error',
                'is-done': t.status === 'success'
              }"
              :style="{ width: (t.status === 'running' && !t.hasPercent ? 40 : (t.percent || 0)) + '%' }"
            ></div>
          </div>
          <div class="sync-card-status">
            <span class="sync-phase" :class="phaseClass(t)">{{ phaseText(t) }}</span>
            <span v-if="t.status === 'running' && t.hasPercent" class="sync-pct">{{ t.percent || 0 }}%</span>
          </div>
        </div>

        <div class="sync-card-foot">
          <span class="sync-last">{{ lastSyncText(t) }}</span>
          <div class="sync-card-actions">
            <span v-if="t.lastSyncRows != null && t.status === 'success'" class="sync-rows">
              +{{ formatNumber(t.lastSyncRows) }}
            </span>
            <el-button
              type="primary"
              size="small"
              plain
              :loading="t.status === 'running'"
              :disabled="running"
              @click.stop="handleStart([t.key])"
            >同步</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 同步记录 -->
    <div class="sync-history">
      <div class="sync-history-head" @click="historyOpen = !historyOpen">
        <span class="sync-history-title">
          <span class="sync-history-caret" :class="{ open: historyOpen }">▸</span>
          同步记录
        </span>
        <span class="sync-history-count">{{ history.length }}</span>
      </div>
      <transition name="sync-collapse">
        <div v-if="historyOpen" class="sync-history-body">
          <div v-if="!history.length" class="sync-history-empty">暂无记录</div>
          <div v-for="h in history" :key="h.taskId" class="sync-history-row">
            <span class="sync-history-dot" :class="`st-${h.status}`"></span>
            <span class="sync-history-time">{{ formatTime(h.startedAt) }}</span>
            <span class="sync-history-tables">{{ tableNames(h.tables) }}</span>
            <span class="sync-history-rows">
              {{ h.status === 'success'
                ? `+${formatNumber(h.insertedRows)}${h.capped ? '（未拉完）' : ''}`
                : (h.message || '失败') }}
            </span>
            <span class="sync-history-cost">{{ formatDuration(h.elapsedMs) }}</span>
          </div>
        </div>
      </transition>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAccountLastSync,
  getLastSyncByType,
  getUserDataPage,
  getNewLogPage,
  getRegisterPage,
  getAgentTotal,
  getEnLogTotal
} from '../../api/userData'
import { getSchoolPage } from '../../api/school'

// 刚同步过多久内再次点击要二次确认，避免重复拉全量
const RECENT_SYNC_MS = 5 * 60 * 1000
// 单表 WebSocket 长时间无任何消息判为异常（账号表全量约 1 分钟，给足余量）
const WS_IDLE_TIMEOUT_MS = 5 * 60 * 1000

// key 直接用后端 @ServerEndpoint 的 {type} 值，避免再做一层映射。
// 旧日志表内部会顺序跑 Log 和 Log_Login，底层仍各写各的集合、_id 互不覆盖；
// 中英文日志是独立的 En_Log 任务，源 213/DataHub.En_Log，目标 181/DataHub_Log.En_Log。
// 用户侧不暴露旧日志两张源表的拆分。日志表字段数不再单独同步，随 Log 同步写入时就地补。
const TABLE_META = [
  { key: 'User_Data', name: '账号表', collection: 'User_Data', icon: '👤' },
  { key: 'Log', name: '日志表', collection: '全部操作日志', icon: '📄' },
  { key: 'En_Log', name: '中英文日志', collection: 'En_Log', icon: '🈯' },
  { key: 'Ip_Adr', name: '学校列表', collection: 'Ip_Adr', icon: '🏫' },
  { key: 'register', name: '注册信息', collection: 'register', icon: '📝' },
  { key: 'Agent', name: '智能体', collection: 'GptReplyHistory', icon: '🤖' }]

const tables = reactive(TABLE_META.map((m) => ({
  ...m,
  targetTotal: null,
  lastSyncAt: null,
  lastSyncRows: null,
  // idle | running | success | error
  status: 'idle',
  phase: '',
  percent: 0,
  // 是否拿到过真实百分比（账号表 true；日志表只有已处理条数 false）
  hasPercent: false,
  // 本次已处理条数
  processed: 0,
  // 本次是否因为达到行数上限而提前停止（不是源表真的拉完了）
  capped: false,
  message: ''
})))

const selectedKeys = ref([])
const activeKeys = ref([])
const running = ref(false)
const startedAt = ref(0)
const elapsedMs = ref(0)
const history = ref(loadHistory())
const historyOpen = ref(false)

const HISTORY_KEY = 'sync_history_v1'
const HISTORY_MAX = 20

function loadHistory() {
  try {
    const raw = localStorage.getItem(HISTORY_KEY)
    return raw ? JSON.parse(raw) : []
  } catch (e) {
    return []
  }
}

function saveHistory() {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history.value.slice(0, HISTORY_MAX)))
  } catch (e) { /* quota exceeded 等，静默 */ }
}

let clockTimer = null
// 正在同步的表 -> 其 WebSocket，用于卸载时收尾
const liveSockets = new Map()

const totalPercent = computed(() => {
  const keys = running.value ? activeKeys.value : selectedKeys.value
  const active = tables.filter((t) => keys.includes(t.key))
  if (!active.length) return 0
  // 日志表无真实百分比，完成前贡献 0、成功后按 100 计
  const sum = active.reduce((acc, t) => acc + (t.percent || 0), 0)
  return Math.round(sum / active.length)
})

const runningLabel = computed(() => {
  const now = tables.filter((t) => t.status === 'running')
  if (now.length) return `正在同步 ${now.map((t) => t.name).join('、')}`
  return running.value ? '准备中' : ''
})

const heroSubtitle = computed(() => {
  if (running.value) return runningLabel.value
  const latest = tables
    .map((t) => t.lastSyncAt)
    .filter(Boolean)
    .sort()
    .pop()
  if (!latest) return '尚未同步过'
  return `${relativeTime(latest)}同步过`
})

function toggleSelect(key) {
  if (running.value) return
  const i = selectedKeys.value.indexOf(key)
  if (i >= 0) {
    selectedKeys.value.splice(i, 1)
  } else {
    selectedKeys.value.push(key)
  }
}

function phaseText(t) {
  if (t.status === 'running') {
    if (!t.hasPercent && t.processed) return `已处理 ${formatNumber(t.processed)} 条`
    return t.phase || '同步中'
  }
  // 成功态要把条数带上。原来无条件返回「已完成」，跑的时候明明显示着
  // 「已处理 N 条」，一结束数字就没了，看着像什么都没同步。
  // Log_Login 没有查 total 的接口、「现有」恒为 —，这一行是唯一的条数出口。
  if (t.status === 'success') {
    if (!t.lastSyncRows) return '已完成'
    // 封顶停下和真正拉完，终态本来长得一模一样，看不出还剩多少没拉。
    // 这是操作相关信息（要不要再点一次），不是界面说明性文字。
    const suffix = t.capped ? '（未拉完）' : ''
    return `已完成，同步 ${formatNumber(t.lastSyncRows)} 条${suffix}`
  }
  if (t.status === 'error') return t.message || '同步失败'
  return '空闲'
}

function phaseClass(t) {
  return {
    'ph-run': t.status === 'running',
    'ph-ok': t.status === 'success',
    'ph-err': t.status === 'error'
  }
}

function lastSyncText(t) {
  if (!t.lastSyncAt) return '未同步过'
  return `${relativeTime(t.lastSyncAt)}同步`
}

function tableNames(keys) {
  if (!keys || !keys.length) return '—'
  return keys.map((k) => TABLE_META.find((m) => m.key === k)?.name || k).join('、')
}

// 账号表最近同步时间 + 现有条数；日志表现有条数直接取 getNewLogPage 的 total。
// getNewLogPage 后端已合并 NewLog + Log_Login 两个集合，其 total 就是日志数据页
// 显示的那个合计值——直接用它，同步页「现有」才能和日志数据页对得上。
// 别再叠加 getLogLoginTotal：那会把 Log_Login 计两遍，同步页数字凭空大出一个登录日志量。
async function loadStatus() {
  const account = tables.find((t) => t.key === 'User_Data')
  const log = tables.find((t) => t.key === 'Log')
  const school = tables.find((t) => t.key === 'Ip_Adr')
  const register = tables.find((t) => t.key === 'register')
  const agent = tables.find((t) => t.key === 'Agent')
  const enLog = tables.find((t) => t.key === 'En_Log')
  getAccountLastSync()
    .then((res) => { if (account) account.lastSyncAt = res.data?.lastUpdateTime || null })
    .catch(() => {})

  getLastSyncByType('Ip_Adr')
    .then((res) => { if (school) school.lastSyncAt = res.data?.lastUpdateTime || null })
    .catch(() => {})

  getLastSyncByType('register')
    .then((res) => { if (register) register.lastSyncAt = res.data?.lastUpdateTime || null })
    .catch(() => {})

  getLastSyncByType('Agent')
    .then((res) => { if (agent) agent.lastSyncAt = res.data?.lastUpdateTime || null })
    .catch(() => {})

  getLastSyncByType('En_Log')
    .then((res) => { if (enLog) enLog.lastSyncAt = res.data?.lastUpdateTime || null })
    .catch(() => {})

  getUserDataPage({ pageNum: 1, pageSize: 1 })
    .then((res) => { if (account) account.targetTotal = res.data?.data?.total ?? null })
    .catch(() => {})

  getNewLogPage({ pageNum: 1, pageSize: 1 })
    .then((res) => { if (log) log.targetTotal = res.data?.data?.total ?? null })
    .catch(() => {})

  getSchoolPage({ pageNum: 1, pageSize: 1 })
    .then((res) => { if (school) school.targetTotal = res.data?.total ?? null })
    .catch(() => {})

  // register 审核记录分页接口
  getRegisterPage({ pageNum: 1, pageSize: 1 })
    .then((res) => { if (register) register.targetTotal = res.data?.data?.total ?? null })
    .catch(() => {})

  // 智能体目标集合 GptReplyHistory 只有计数接口（没有分页查询），够同步页显示「现有」即可。
  // exists 为 false 表示这个集合还没被首次同步创建过，此时保持 null 显示「—」——
  // 显示 0 会让人误以为同步过但一条都没同步进来。
  getAgentTotal()
    .then((res) => {
      if (!agent) return
      agent.targetTotal = res.data?.exists ? (res.data?.total ?? 0) : null
    })
    .catch(() => {})

  // 中英文日志同样是首次同步才建集合，exists 为 false 时保持 null 显示「—」
  getEnLogTotal()
    .then((res) => {
      if (!enLog) return
      enLog.targetTotal = res.data?.exists ? (res.data?.total ?? 0) : null
    })
    .catch(() => {})
}

// ===== 同步流程 =====
// 后端是一条 WebSocket、一次连接同步一张表。多张表顺序跑（并发会对生产库
// 同时全量写、总进度语义也乱）。
function wsUrl(type) {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${scheme}//${location.host}/websocket/${encodeURIComponent(type)}`
}

// 开始同步确认弹窗的正文。账号表涉及生产库全量写，单独醒目提示；
// 同步无法中断、耗时较长，也一并讲清（这是操作风险告知，不是界面说明性文字）。
function confirmStartHtml(names, flags) {
  const lead = `<p class="mb-lead">即将同步 <b>${names}</b></p>`
  const account = flags.account
    ? `<div class="mb-alert"><span class="mb-alert-ico">⚠️</span><span>账号表将<b>全量写入生产库</b>(约 12.9 万条)，耗时较久(可达约一小时)。</span></div>`
    : ''
  const school = flags.school
    ? `<div class="mb-alert"><span class="mb-alert-ico">⚠️</span><span>学校列表将写入生产库，并同步删除源端不存在的记录。</span></div>`
    : ''
  const register = flags.register
    ? `<div class="mb-alert"><span class="mb-alert-ico">⚠️</span><span>注册信息将写入生产库，并上传缺失文件到 OSS(文件 URL 公开可访问)。</span></div>`
    : ''
  const agent = flags.agent
    ? `<div class="mb-alert"><span class="mb-alert-ico">⚠️</span><span>智能体数据将从 139 读取并写入生产库新集合。</span></div>`
    : ''
  const enLog = flags.enLog
    ? `<div class="mb-alert"><span class="mb-alert-ico">⚠️</span><span>中英文日志将从 213 读取并写入生产库新集合 En_Log。</span></div>`
    : ''
  const tip = `<p class="mb-sub">同步开始后<b>无法中途取消</b>，且请勿刷新或关闭页面。确定继续？</p>`
  return lead + account + school + register + agent + enLog + tip
}

async function handleStart(keys = selectedKeys.value) {
  console.log('[DEBUG] handleStart 被调用，keys=', keys)
  const syncKeys = Array.from(new Set(keys))
  console.log('[DEBUG] syncKeys=', syncKeys, 'running=', running.value, 'syncKeys.length=', syncKeys.length)
  if (running.value || !syncKeys.length) {
    console.log('[DEBUG] 提前返回：running 或 syncKeys 为空')
    return
  }

  // 账号表会向生产库全量 upsert 12.9 万条，是真实生产写操作，先明确确认。
  // 学校列表同步还会删除目标端源端不存在的记录，也必须在开始前讲清。
  // register 会上传文件到 OSS，URL 公开，也要确认。
  const willWriteAccount = syncKeys.includes('User_Data')
  const willSyncSchool = syncKeys.includes('Ip_Adr')
  const willWriteRegister = syncKeys.includes('register')
  const willWriteAgent = syncKeys.includes('Agent')
  const willWriteEnLog = syncKeys.includes('En_Log')
  const names = tables
    .filter((t) => syncKeys.includes(t.key))
    .map((t) => t.name)
    .join('、')
  console.log('[DEBUG] 准备弹出确认框，names=', names)
  try {
    await ElMessageBox.confirm(confirmStartHtml(names, {
      account: willWriteAccount,
      school: willSyncSchool,
      register: willWriteRegister,
      agent: willWriteAgent,
      enLog: willWriteEnLog
    }), '确认同步', {
      confirmButtonText: '开始同步',
      cancelButtonText: '取消',
      type: 'warning',
      dangerouslyUseHTMLString: true,
      customClass: 'sync-confirm-box'
    })
    console.log('[DEBUG] 用户点击了「开始同步」')
  } catch (e) {
    console.log('[DEBUG] 用户取消了同步或弹窗出错：', e)
    return
  }

  // 5 分钟内刚同步过（仅账号表有可靠时间）再点，二次确认
  const account = tables.find((t) => t.key === 'User_Data')
  if (
    syncKeys.includes('User_Data') &&
    account?.lastSyncAt &&
    Date.now() - new Date(account.lastSyncAt).getTime() < RECENT_SYNC_MS
  ) {
    try {
      await ElMessageBox.confirm(
        `<p class="mb-lead">账号表 <b>${relativeTime(account.lastSyncAt)}</b>刚同步过。</p>` +
          `<p class="mb-sub">再次同步会重新全量拉取，确定继续？</p>`,
        '重复同步',
        {
          confirmButtonText: '继续同步',
          cancelButtonText: '取消',
          type: 'warning',
          dangerouslyUseHTMLString: true,
          customClass: 'sync-confirm-box'
        }
      )
    } catch (e) {
      return
    }
  }

  const queue = tables.filter((t) => syncKeys.includes(t.key))
  queue.forEach((t) => {
    t.status = 'running'
    t.percent = 0
    t.hasPercent = false
    t.processed = 0
    t.phase = '等待中'
    t.message = ''
    t.lastSyncRows = null
    t.capped = false // 每轮重置，否则上次封顶的标记会留到这次
  })
  activeKeys.value = queue.map((t) => t.key)
  running.value = true
  startedAt.value = Date.now()
  elapsedMs.value = 0
  startClock()
  guardUnload(true) // 同步中拦截刷新/关页

  // 顺序执行：一张跑到终态再跑下一张
  for (const t of queue) {
    // eslint-disable-next-line no-await-in-loop
    await syncOne(t)
  }

  running.value = false
  activeKeys.value = []
  stopClock()
  guardUnload(false)
  // 同步会改「现有」（账号/日志条数等），结束后重拉一次
  loadStatus()
  const failed = queue.filter((t) => t.status === 'error')
  if (!failed.length) ElMessage.success('同步完成')
  else if (failed.length < queue.length) ElMessage.warning('部分表同步失败')
  else ElMessage.error('同步失败')
}

// 同步进行中拦截整页刷新/关闭标签页——浏览器弹原生「离开此网站？」确认框。
// 后端任务本身不会因此中断，但能挡住用户误操作、避免以为「还在跑」其实已断开显示。
function beforeUnloadHandler(e) {
  e.preventDefault()
  e.returnValue = ''
}
function guardUnload(on) {
  if (on) window.addEventListener('beforeunload', beforeUnloadHandler)
  else window.removeEventListener('beforeunload', beforeUnloadHandler)
}

// 单表同步：建连 -> 发触发消息 -> 解析消息直到终态 -> 关连接。
// 日志入口在这里顺带把登录日志也跑掉，但前端只展示一个「日志表」任务。
function syncSource(type, t, processedOffset = 0) {
  return new Promise((resolve) => {
    let settled = false
    let idleTimer = null
    const rowStart = Date.now()

    const finish = (status, message) => {
      if (settled) return
      settled = true
      clearTimeout(idleTimer)
      liveSockets.delete(`${t.key}:${type}`)
      try { ws.close() } catch (e) { /* 已关则忽略 */ }
      resolve({
        status,
        message,
        elapsed: Date.now() - rowStart,
        processed: t.processed || 0,
        capped: !!t.capped
      })
    }

    const bumpIdle = () => {
      clearTimeout(idleTimer)
      idleTimer = setTimeout(() => finish('error', '长时间无响应'), WS_IDLE_TIMEOUT_MS)
    }

    let ws
    try {
      ws = new WebSocket(wsUrl(type))
    } catch (e) {
      finish('error', '无法建立连接')
      return
    }
    liveSockets.set(`${t.key}:${type}`, ws)
    bumpIdle()

    ws.onopen = () => {
      t.phase = '连接数据源'
      // onOpen 只打招呼，必须 send 一条消息后端才开始跑；内容不限
      ws.send(type)
    }
    ws.onmessage = (ev) => {
      bumpIdle()
      handleFrame(t, ev.data, finish, processedOffset)
    }
    ws.onerror = () => finish('error', '连接出错')
    ws.onclose = () => finish('error', '连接已断开')
  })
}

async function syncOne(t) {
  const rowStart = Date.now()
  const stages = t.key === 'Log' ? ['Log', 'Log_Login'] : [t.key]
  let processedOffset = 0

  for (const type of stages) {
    // eslint-disable-next-line no-await-in-loop
    const stage = await syncSource(type, t, processedOffset)
    if (stage.status !== 'success') {
      t.status = 'error'
      t.message = stage.message || '同步失败'
      pushHistory(t, 'error', t.message, Date.now() - rowStart)
      return
    }
    processedOffset = t.processed || processedOffset
  }

  t.status = 'success'
  t.percent = 100
  t.hasPercent = true
  t.phase = '已完成'
  t.lastSyncRows = t.processed || null
  t.lastSyncAt = new Date().toISOString()
  pushHistory(t, 'success', '', Date.now() - rowStart)
}

// 解析一帧服务端消息，更新表状态；命中终态时调 finish。
function handleFrame(t, raw, finish, processedOffset = 0) {
  const text = typeof raw === 'string' ? raw.trim() : ''
  if (!text) return

  // 终态优先判断。成功必须精确匹配「所有同步任务执行完毕」——
  // 日志分支中途会发「✅ NewLog 同步完成…」，那不是终态。
  if (text.includes('所有同步任务执行完毕')) {
    finish('success')
    return
  }
  if (text.includes('同步任务执行失败') || text.startsWith('❌') || text.startsWith('错误')) {
    // 只剥掉开头的标记/前缀，保留完整报错原文——
    // SQL 报错里含大量冒号（JDBC URL、端口），不能按冒号切段
    const msg = text
      .replace(/^[❌\s]+/, '')
      .replace(/^同步任务执行失败\s*[:：]\s*/, '')
      .replace(/^错误\s*[:：]\s*/, '')
      .trim()
    finish('error', msg || '同步失败')
    return
  }

  // 进度 JSON：{"done":N,"count":M} 或 register 的 {"done":N,"count":M,"reused":R,"uploaded":U}
  // count 不一定可用——Log_Login 分支后端没调 getLogCount()，发出来是 count:0。
  // 所以只要有 done 就取它当已处理条数，只在 count 为正数时才算百分比，
  // 否则退回不确定态（与「已处理 N 条」的展示一致），别让原始 JSON 串漏到 phase 里。
  if (text.startsWith('{')) {
    try {
      const obj = JSON.parse(text)
      // 封顶停下的标记帧，紧跟在终态之前。记下来好在终态里显示「未拉完」——
      // 后端那句「已达本次行数上限」是自由文本，会被终态覆盖，不能靠它。
      if (obj && obj.capped === true) {
        t.capped = true
        return
      }
      if (obj && obj.done != null) {
        t.processed = processedOffset + obj.done
        if (obj.count > 0) {
          t.hasPercent = true
          t.percent = Math.min(99, Math.round((obj.done / obj.count) * 100))
        }
        // register 进度带文件计数：「写入数据（复用 M / 新上传 K）」
        if (obj.reused != null && obj.uploaded != null) {
          t.phase = `写入数据（复用 ${obj.reused} / 新上传 ${obj.uploaded}）`
        } else {
          t.phase = '写入数据'
        }
        return
      }
    } catch (e) { /* 非 JSON，落到下面按文本处理 */ }
  }

  // 日志表进度：裸数字（已处理条数，没有总数，不算百分比）
  if (/^\d+$/.test(text)) {
    t.processed = processedOffset + Number(text)
    t.phase = '写入数据'
    return
  }

  // 其余自由文本作为阶段提示
  t.phase = text.replace(/^[✅\s]+/, '')
}

// 会话内历史（后端无持久化历史，刷新即空）
function pushHistory(t, status, message, elapsed) {
  history.value.unshift({
    taskId: `${t.key}-${Date.now()}`,
    tables: [t.key],
    startedAt: new Date(Date.now() - elapsed).toISOString(),
    elapsedMs: elapsed,
    insertedRows: t.processed || 0,
    capped: !!t.capped,
    status,
    message
  })
  if (history.value.length > HISTORY_MAX) history.value.length = HISTORY_MAX
  saveHistory()
}

function startClock() {
  stopClock()
  clockTimer = setInterval(() => {
    elapsedMs.value = Date.now() - startedAt.value
  }, 250)
}

function stopClock() {
  if (clockTimer) {
    clearInterval(clockTimer)
    clockTimer = null
  }
}

// ===== 格式化 =====
function formatNumber(n) {
  if (n == null) return '—'
  return Number(n).toLocaleString('zh-CN')
}

function formatDuration(ms) {
  if (!ms || ms < 0) return '0 秒'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  const rest = s % 60
  if (m < 60) return rest ? `${m} 分 ${rest} 秒` : `${m} 分`
  const h = Math.floor(m / 60)
  return `${h} 小时 ${m % 60} 分`
}

function relativeTime(iso) {
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return ''
  const diff = Date.now() - t
  if (diff < 60 * 1000) return '刚刚'
  if (diff < 3600 * 1000) return `${Math.floor(diff / 60000)} 分钟前`
  if (diff < 86400 * 1000) return `${Math.floor(diff / 3600000)} 小时前`
  const days = Math.floor(diff / 86400000)
  if (days < 30) return `${days} 天前`
  return formatTime(iso)
}

function formatTime(iso) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

onMounted(() => {
  loadStatus()
})

onBeforeUnmount(() => {
  stopClock()
  guardUnload(false)
  // 关掉本端连接（服务端游标不受影响，会自行跑完）
  liveSockets.forEach((ws) => { try { ws.close() } catch (e) { /* 忽略 */ } })
  liveSockets.clear()
})
</script>

<style scoped>
.sync-wrap {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

/* ---------- 顶部 ---------- */
.sync-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 20px 24px;
  border-radius: 18px;
  background: linear-gradient(135deg, rgba(63, 140, 255, 0.1), rgba(108, 99, 255, 0.1));
  border: 1px solid rgba(99, 122, 165, 0.16);
}
.sync-hero-title {
  display: flex;
  align-items: center;
  gap: 14px;
}
.sync-hero-icon {
  font-size: 30px;
  filter: drop-shadow(0 3px 8px rgba(63, 140, 255, 0.3));
}
.sync-hero-title h3 {
  margin: 0;
  color: #2b3654;
  font-size: 19px;
  letter-spacing: 0.3px;
}
.sync-hero-sub {
  margin: 4px 0 0;
  color: #5a6688;
  font-size: 13px;
}

/* ---------- 总进度 ---------- */
.sync-total {
  padding: 16px 20px;
  border-radius: 16px;
  background: #fff;
  border: 1px solid rgba(99, 122, 165, 0.16);
  box-shadow: 0 6px 20px rgba(15, 26, 54, 0.06);
}
.sync-total-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}
.sync-total-label {
  color: #4a5573;
  font-size: 13px;
  font-weight: 600;
}
.sync-total-num {
  color: var(--brand-start);
  font-size: 22px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.sync-total-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  color: #8a95b0;
  font-size: 12px;
}
/* 同步中的操作风险提示条 */
.sync-warn {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(255, 154, 60, 0.1);
  border: 1px solid rgba(255, 154, 60, 0.28);
  color: #c9701a;
  font-size: 12.5px;
}
.sync-warn-ico {
  font-size: 14px;
  line-height: 1;
}

/* ---------- 进度条 ---------- */
.sync-bar {
  height: 8px;
  border-radius: 999px;
  background: rgba(99, 122, 165, 0.14);
  overflow: hidden;
}
.sync-bar-lg {
  height: 12px;
}
.sync-bar-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--brand-grad);
  transition: width 0.35s ease;
  position: relative;
  overflow: hidden;
}
/* 流动光带，让「正在进行」有动感 */
.sync-bar-fill::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    transparent,
    rgba(255, 255, 255, 0.45),
    transparent
  );
  transform: translateX(-100%);
  animation: syncFlow 1.4s linear infinite;
}
@keyframes syncFlow {
  to { transform: translateX(100%); }
}
.sync-bar-fill.is-done {
  background: linear-gradient(135deg, #40bf81, #2f9e68);
}
.sync-bar-fill.is-done::after,
.sync-bar-fill.is-error::after {
  animation: none;
}
.sync-bar-fill.is-error {
  background: linear-gradient(135deg, #ff9a3c, #e05252);
}
/* 日志表无总数：不确定态，滑块左右往返表示「在跑但进度未知」 */
.sync-bar.indeterminate {
  position: relative;
}
.sync-bar.indeterminate .sync-bar-fill {
  position: absolute;
  animation: syncIndeterminate 1.4s ease-in-out infinite;
}
.sync-bar.indeterminate .sync-bar-fill::after {
  animation: none;
}
@keyframes syncIndeterminate {
  0% { left: -40%; }
  100% { left: 100%; }
}

/* ---------- 卡片 ---------- */
.sync-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
}
.sync-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  border-radius: 16px;
  background: #fff;
  border: 1px solid rgba(99, 122, 165, 0.16);
  cursor: pointer;
  transition: transform 0.22s ease, box-shadow 0.24s ease, border-color 0.24s ease;
}
.sync-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 12px 28px rgba(15, 26, 54, 0.1);
}
.sync-card.selected {
  border-color: rgba(63, 140, 255, 0.5);
  box-shadow: 0 8px 22px rgba(63, 140, 255, 0.14);
}
.sync-card.busy {
  border-color: rgba(63, 140, 255, 0.6);
}
.sync-card.done {
  border-color: rgba(64, 191, 129, 0.45);
}
.sync-card.failed {
  border-color: rgba(224, 82, 82, 0.45);
}
.sync-card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.sync-card-id {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.sync-card-icon {
  font-size: 22px;
}
.sync-card-name {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.sync-card-name strong {
  color: #2b3654;
  font-size: 15px;
}
.sync-card-code {
  color: #a3adc2;
  font-size: 11.5px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.sync-metrics {
  display: flex;
  gap: 10px;
}
.sync-metric {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(240, 244, 253, 0.75);
}
.sync-metric-label {
  color: #8a95b0;
  font-size: 11.5px;
}
.sync-metric strong {
  color: #3d4864;
  font-size: 17px;
  font-variant-numeric: tabular-nums;
}
.sync-metric strong.hot {
  color: #e08a2f;
}

.sync-card-progress {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.sync-card-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.sync-phase {
  font-size: 12.5px;
  color: #8a95b0;
}
.sync-phase.ph-run {
  color: var(--brand-start);
  font-weight: 600;
}
.sync-phase.ph-ok {
  color: #2f9e68;
  font-weight: 600;
}
.sync-phase.ph-err {
  color: #e05252;
  font-weight: 600;
}
.sync-pct {
  color: var(--brand-start);
  font-size: 12.5px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.sync-card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px dashed rgba(99, 122, 165, 0.2);
}
.sync-last {
  color: #8a95b0;
  font-size: 12px;
}
.sync-card-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.sync-card-actions :deep(.el-button--primary.is-plain) {
  color: var(--brand-start);
  font-weight: 600;
  background: rgba(63, 140, 255, 0.08);
  border-color: rgba(63, 140, 255, 0.42);
}
.sync-card-actions :deep(.el-button--primary.is-plain:hover),
.sync-card-actions :deep(.el-button--primary.is-plain:focus) {
  color: #fff;
  background: var(--brand-grad);
  border-color: transparent;
}
.sync-rows {
  padding: 2px 9px;
  border-radius: 999px;
  background: rgba(64, 191, 129, 0.14);
  color: #2f9e68;
  font-size: 11.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

/* ---------- 同步记录 ---------- */
.sync-history {
  border-radius: 16px;
  background: #fff;
  border: 1px solid rgba(99, 122, 165, 0.16);
  overflow: hidden;
}
.sync-history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  cursor: pointer;
  transition: background 0.2s ease;
}
.sync-history-head:hover {
  background: rgba(240, 244, 253, 0.7);
}
.sync-history-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #4a5573;
  font-size: 13.5px;
  font-weight: 600;
}
.sync-history-caret {
  display: inline-block;
  transition: transform 0.25s ease;
  color: #a3adc2;
}
.sync-history-caret.open {
  transform: rotate(90deg);
}
.sync-history-count {
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--brand-grad-soft);
  color: #4a5b8a;
  font-size: 11.5px;
  font-weight: 700;
  line-height: 20px;
  text-align: center;
}
.sync-history-body {
  padding: 0 20px 14px;
}
.sync-history-empty {
  padding: 14px 0;
  color: #a3adc2;
  font-size: 12.5px;
  text-align: center;
}
.sync-history-row {
  display: grid;
  grid-template-columns: 10px 128px 1fr 110px 84px;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid rgba(99, 122, 165, 0.1);
  color: #5a6688;
  font-size: 12.5px;
}
.sync-history-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.sync-history-dot.st-success {
  background: #40bf81;
}
.sync-history-dot.st-error {
  background: #e05252;
}
.sync-history-time,
.sync-history-cost {
  font-variant-numeric: tabular-nums;
}
.sync-history-tables {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.sync-history-rows {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.sync-history-cost {
  color: #a3adc2;
  text-align: right;
}

/* ---------- 过渡 ---------- */
.sync-fade-enter-active,
.sync-fade-leave-active {
  transition: opacity 0.28s ease, transform 0.28s ease;
}
.sync-fade-enter-from,
.sync-fade-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
.sync-collapse-enter-active,
.sync-collapse-leave-active {
  transition: opacity 0.22s ease;
}
.sync-collapse-enter-from,
.sync-collapse-leave-to {
  opacity: 0;
}
</style>
