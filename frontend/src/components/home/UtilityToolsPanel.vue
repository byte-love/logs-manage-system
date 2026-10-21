<template>
  <div class="tools-wrap">
    <template v-if="!activeTool">
      <div class="tools-board">
        <button
          v-for="tool in TOOLS"
          :key="tool.key"
          class="tool-entry"
          type="button"
          @click="openTool(tool.key)"
        >
          <span class="entry-icon">{{ tool.icon }}</span>
          <span class="entry-main">
            <strong>{{ tool.name }}</strong>
            <span>{{ tool.desc }}</span>
          </span>
          <span class="entry-arrow">›</span>
        </button>
      </div>
    </template>

    <template v-else-if="activeTool === 'user-log-export'">
      <section class="tool-detail">
        <div class="detail-head">
          <div class="detail-title-row">
            <el-button class="back-button" @click="backToBoard"><span class="arw">‹</span>返回</el-button>
            <div class="detail-title">
              <h2>用户日志导出</h2>
              <span>{{ searchedUserName ? searchedUserName : '按用户名查询' }}</span>
            </div>
          </div>
          <div class="detail-actions">
            <el-input
              v-model="form.userName"
              placeholder="用户名"
              clearable
              class="user-search"
              @keyup.enter="search"
              @clear="reset"
            />
            <el-button type="primary" :loading="loading" @click="search">搜索</el-button>
            <el-button @click="reset">重置</el-button>
            <el-button
              type="success"
              :loading="exporting"
              :disabled="!canExport"
              @click="downloadLogs"
            >导出日志</el-button>
          </div>
        </div>

        <el-alert
          v-if="error"
          :title="error"
          type="error"
          show-icon
          class="tool-alert"
          :closable="false"
        />

        <div class="summary-row">
          <div class="summary-item">
            <span>搜索结果</span>
            <strong>{{ total.toLocaleString() }}</strong>
          </div>
          <div class="summary-item">
            <span>用户名</span>
            <strong>{{ searchedUserName || '—' }}</strong>
          </div>
          <div class="summary-item">
            <span>本页</span>
            <strong>{{ rows.length.toLocaleString() }}</strong>
          </div>
        </div>

        <div v-loading="loading" class="activity-list">
          <div v-if="!rows.length" class="activity-empty">暂无日志数据</div>
          <article v-for="row in rows" :key="`${row._id || row.ID}-${row.O_date}`" class="activity-item">
            <div class="activity-main">
              <div class="activity-type">{{ text(row.O_Operation_type) }}</div>
              <div class="activity-meta">
                <span>{{ formatDateTime(row.O_date) }}</span>
                <span>{{ text(row.O_system) }}</span>
                <span>{{ text(row.O_database) }}</span>
                <span>{{ text(row.O_table) }}</span>
              </div>
            </div>
            <div class="activity-side">
              <span>{{ text(row.NickName) }}</span>
              <span>{{ text(row.Institution) }}</span>
              <span>{{ text(row.O_city) }}</span>
              <span>{{ text(row.O_srcIP) }}</span>
            </div>
          </article>
        </div>

        <div v-if="searchedUserName" class="pagination-row">
          <el-pagination
            v-model:current-page="pageNum"
            v-model:page-size="pageSize"
            background
            layout="total, sizes, prev, pager, next, jumper"
            :page-sizes="[10, 20, 50]"
            :total="total"
            @current-change="fetchData"
            @size-change="handleSizeChange"
          />
        </div>
      </section>
    </template>

    <template v-else-if="activeTool === 'school-log-export'">
      <section class="tool-detail">
        <div class="detail-head">
          <div class="detail-title-row">
            <el-button class="back-button" @click="backToBoard"><span class="arw">‹</span>返回</el-button>
            <div class="detail-title">
              <h2>学校日志导出</h2>
              <span>按学校/机构名称查询</span>
            </div>
          </div>
          <div class="detail-actions school-actions">
            <el-date-picker
              v-model="schoolForm.dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              value-format="YYYY-MM-DD"
              class="school-date"
            />
            <el-autocomplete
              v-model="schoolForm.schoolName"
              :fetch-suggestions="querySchoolNames"
              :trigger-on-focus="false"
              placeholder="学校/机构名称"
              clearable
              class="user-search"
              @keyup.enter="searchSchool"
            />
            <el-button type="primary" :loading="schoolLoading" @click="searchSchool">搜索</el-button>
            <el-button @click="resetSchool">重置</el-button>
            <el-button
              type="success"
              :loading="schoolExporting"
              :disabled="!canExportSchool"
              @click="downloadSchoolLogs"
            >导出日志</el-button>
          </div>
        </div>

        <el-alert
          v-if="schoolError"
          :title="schoolError"
          type="error"
          show-icon
          class="tool-alert"
          :closable="false"
        />

        <div class="summary-row">
          <div class="summary-item">
            <span>搜索结果</span>
            <strong>{{ schoolTotalText }}</strong>
          </div>
          <div class="summary-item">
            <span>学校/机构</span>
            <strong>{{ searchedSchool.name || '—' }}</strong>
          </div>
          <div class="summary-item">
            <span>本页</span>
            <strong>{{ schoolRows.length.toLocaleString() }}</strong>
          </div>
        </div>

        <el-table
          v-loading="schoolLoading"
          :data="schoolRows"
          stripe
          border
          class="log-table"
          empty-text="暂无可展示数据"
        >
          <el-table-column
            v-for="column in schoolColumns"
            :key="column.prop"
            :prop="column.prop"
            :label="column.label"
            :min-width="column.minWidth"
            show-overflow-tooltip
          >
            <template #default="scope">
              {{ renderLogCell(column.prop, scope.row[column.prop]) }}
            </template>
          </el-table-column>
        </el-table>

        <div v-if="searchedSchool.name" class="pagination-row">
          <el-pagination
            v-model:current-page="schoolPageNum"
            v-model:page-size="schoolPageSize"
            background
            layout="total, sizes, prev, pager, next, jumper"
            :page-sizes="[10, 20, 50]"
            :total="schoolTotal"
            @current-change="fetchSchoolData"
            @size-change="handleSchoolSizeChange"
          />
        </div>
      </section>
    </template>

    <!-- 创建正式账号：和其他工具一样走「卡片 → 内嵌页面」，不再是弹窗。
         顶部工具栏有条目、底部吸底条管提交，所以这里不需要额外包壳 -->
    <CreateAccountPanel
      v-else-if="activeTool === 'create-account'"
      @close="backToBoard"
    />

    <!-- 节假日公告：写的是老版网站注册流程页上给用户看的那句话，
         所以没有独立的弹窗壳，面板自己把「用户现在看到什么、改完什么时候可见」摆在最上面 -->
    <HolidayTipPanel
      v-else-if="activeTool === 'holiday-tip'"
      @close="backToBoard"
    />

    <!-- 学校日志导出：后台任务的真实进度 -->
    <el-dialog
      v-model="schoolExportVisible"
      title="正在导出"
      width="430px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
      custom-class="export-progress-dialog"
      append-to-body
    >
      <div class="export-progress-body">
        <el-progress
          :percentage="schoolExportPercent"
          :status="schoolExportPercent >= 100 ? 'success' : undefined"
          :stroke-width="12"
          :text-inside="true"
        />
        <p class="export-progress-meta">
          已生成 {{ schoolExportProcessed.toLocaleString() }} / {{ schoolExportTotal.toLocaleString() }} 条
        </p>
        <p class="export-progress-tip">生成中请勿刷新或关闭页面</p>
        <div v-if="schoolExportRunning" class="export-progress-actions">
          <el-button @click="cancelSchoolRun">取消导出</el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  exportUserLogs,
  getUserLogs,
  getSchoolLogs,
  startSchoolExport,
  getSchoolExportProgress,
  cancelSchoolExport,
  downloadSchoolExport
} from '../../api/userData'
import { getAllSchoolNames } from '../../api/school'
import { buildLogColumns } from '../../constants/logColumns'
import {
  downloadBlobFallback,
  pickSaveFile,
  supportsFilePicker,
  writeBlobToHandle
} from '../../utils/download'
import CreateAccountPanel from '../../components/home/CreateAccountPanel.vue'
import HolidayTipPanel from '../../components/home/HolidayTipPanel.vue'
import { TOOLS } from '../../constants/nav'
import { toBeijingMinute } from '../../utils/format'

const emit = defineEmits(['update:tool'])

const activeTool = ref('')

// 把「现在停在哪个工具」报给 Home.vue，顶栏靠它把面包屑补成
// 「实用工具 / 用户日志导出」。空串 = 停在卡片墙，顶栏退回只显示「实用工具」
watch(activeTool, (key) => emit('update:tool', key || ''), { immediate: true })
const form = reactive({ userName: '' })
const schoolForm = reactive({ schoolName: '', dateRange: [] })
// 已提交的查询条件，与输入框分开存：翻页要用提交时的条件，
// 不能用输入框当前值（用户改了输入框但没点搜索时会错位）
const searchedSchool = reactive({ name: '', dateStart: '', dateEnd: '' })
const schoolLoading = ref(false)
const schoolExporting = ref(false)
// 学校日志导出后台任务：进度弹窗状态
const schoolExportVisible = ref(false)
const schoolExportRunning = ref(false)
const schoolExportPercent = ref(0)
const schoolExportProcessed = ref(0)
const schoolExportTotal = ref(0)
let activeExportTaskId = ''
const schoolError = ref('')
const schoolRows = ref([])
const schoolTotal = ref(0)
const schoolTotalCapped = ref(false)
const schoolPageNum = ref(1)
const schoolPageSize = ref(10)
const searchedUserName = ref('')
const loading = ref(false)
const exporting = ref(false)
const error = ref('')
const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

const canExport = computed(() => !!searchedUserName.value && total.value > 0 && !loading.value && !exporting.value)
const canExportSchool = computed(() =>
  !!searchedSchool.name && schoolTotal.value > 0 && !schoolLoading.value && !schoolExporting.value)

// 学校名候选（285 条），首次进入学校日志导出时拉一次，之后本地过滤
const schoolNames = ref([])

function openTool(key) {
  activeTool.value = key
  if (key === 'school-log-export') loadSchoolNames()
}

async function loadSchoolNames() {
  if (schoolNames.value.length) return
  try {
    const { data } = await getAllSchoolNames()
    if (data && data.success) {
      schoolNames.value = (data.records || []).map((name) => ({ value: name }))
    }
  } catch (e) {
    // 联想只是辅助输入，拉不到不阻断手工输入
    schoolNames.value = []
  }
}

// el-autocomplete 的回调式过滤：命中项按「越靠前越优先」排，避免长名把短名挤下去
function querySchoolNames(queryString, cb) {
  const kw = String(queryString || '').trim().toLowerCase()
  if (!kw) {
    cb([])
    return
  }
  const hit = schoolNames.value
    .filter((item) => item.value.toLowerCase().includes(kw))
    .sort((a, b) => a.value.toLowerCase().indexOf(kw) - b.value.toLowerCase().indexOf(kw))
  cb(hit)
}

// 机构列不展示：整页都是同一个机构，值已在搜索框和汇总栏里
const SCHOOL_LOGS_HIDDEN = ['Institution']

// 表字段数默认隐藏名单里，学校面板要显式放开这一列。
// 列从「首行字段」推出来：万一首页首行是字典查不到的表（没这个字段），
// 就手动补上该列，保证表头每页都有一致的「表字段数」。
const schoolColumns = computed(() => {
  const cols = buildLogColumns(schoolRows.value, SCHOOL_LOGS_HIDDEN, ['tableFieldCount'])
  if (schoolRows.value.length && !cols.some((c) => c.prop === 'tableFieldCount')) {
    cols.push({ prop: 'tableFieldCount', label: '表字段数', minWidth: 90 })
  }
  return cols
})

// 与日志数据页的 renderCell 同口径：操作时间截到分钟，其余空值显示 '-'
function renderLogCell(prop, value) {
  return prop === 'O_date' ? formatDateTime(value) : text(value)
}

// 学校日志走专用接口（/school-logs），精确计数、不封顶
const schoolTotalText = computed(() =>
  schoolTotalCapped.value
    ? `${schoolTotal.value.toLocaleString()}+`
    : schoolTotal.value.toLocaleString()
)

async function searchSchool() {
  const name = String(schoolForm.schoolName || '').trim()
  if (!name) {
    ElMessage.warning('请输入学校/机构名称')
    return
  }
  const range = Array.isArray(schoolForm.dateRange) ? schoolForm.dateRange : []
  searchedSchool.name = name
  searchedSchool.dateStart = range.length === 2 ? range[0] : ''
  searchedSchool.dateEnd = range.length === 2 ? range[1] : ''
  schoolPageNum.value = 1
  await fetchSchoolData()
}

async function fetchSchoolData() {
  if (!searchedSchool.name) return
  schoolLoading.value = true
  schoolError.value = ''
  try {
    // 机构名 + 操作时间。excludeAnonymous 不传，走后端默认 true（排除匿名访问）；
    // 机构名在后端按「容忍首尾空格」匹配 —— 日志里的机构名有带尾填充空格的，
    // 等值会漏掉「学校账号」那批。导出接口必须与这里保持同一口径，
    // 否则页面 N 条、导出 M 条
    const params = {
      pageNum: schoolPageNum.value,
      pageSize: schoolPageSize.value,
      institution: searchedSchool.name
    }
    if (searchedSchool.dateStart) params.dateStart = searchedSchool.dateStart
    if (searchedSchool.dateEnd) params.dateEnd = searchedSchool.dateEnd

    const res = await getSchoolLogs(params)
    const d = res.data?.data || {}
    schoolRows.value = Array.isArray(d.records) ? d.records : []
    schoolTotal.value = Number(d.total || 0)
    // totalCapped 在响应顶层、不在 data 里（与 getNewLogPage 一致）。
    // 本接口是精确 count、恒为 false，这里仍读一下以防后端口径变化
    schoolTotalCapped.value = Boolean(res.data?.totalCapped)
    schoolPageNum.value = Number(d.pageNum || schoolPageNum.value)
    schoolPageSize.value = Number(d.pageSize || schoolPageSize.value)
  } catch (e) {
    schoolRows.value = []
    schoolTotal.value = 0
    schoolTotalCapped.value = false
    schoolError.value = e.response?.data?.message || e.message || '日志加载失败'
    ElMessage.error(schoolError.value)
  } finally {
    schoolLoading.value = false
  }
}

function handleSchoolSizeChange() {
  schoolPageNum.value = 1
  fetchSchoolData()
}

function resetSchool() {
  schoolForm.schoolName = ''
  schoolForm.dateRange = []
  searchedSchool.name = ''
  searchedSchool.dateStart = ''
  searchedSchool.dateEnd = ''
  schoolError.value = ''
  schoolRows.value = []
  schoolTotal.value = 0
  schoolTotalCapped.value = false
  schoolPageNum.value = 1
  schoolPageSize.value = 10
}

function backToBoard() {
  activeTool.value = ''
}

function trimUserName() {
  return String(form.userName || '').trim()
}

function text(value) {
  if (value === null || value === undefined || value === '') return '—'
  return String(value)
}

// 操作时间统一北京时间(ISO +00:00 → +8)。见 utils/format.js 说明。
function formatDateTime(value) {
  return toBeijingMinute(value)
}

async function search() {
  const userName = trimUserName()
  if (!userName) {
    ElMessage.warning('请输入用户名')
    return
  }
  searchedUserName.value = userName
  pageNum.value = 1
  await fetchData()
}

async function fetchData() {
  if (!searchedUserName.value) return
  loading.value = true
  error.value = ''
  try {
    const res = await getUserLogs({
      userName: searchedUserName.value,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    const d = res.data?.data || {}
    rows.value = Array.isArray(d.records) ? d.records : []
    total.value = Number(d.total || 0)
    pageNum.value = Number(d.pageNum || pageNum.value)
    pageSize.value = Number(d.pageSize || pageSize.value)
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = e.response?.data?.message || e.message || '日志加载失败'
    ElMessage.error(error.value)
  } finally {
    loading.value = false
  }
}

function handleSizeChange() {
  pageNum.value = 1
  fetchData()
}

function reset() {
  form.userName = ''
  searchedUserName.value = ''
  error.value = ''
  rows.value = []
  total.value = 0
  pageNum.value = 1
  pageSize.value = 10
}

function timestamp() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

function safeFileNamePart(value) {
  return String(value || '').replace(/[\\/:*?"<>|]/g, '_')
}

async function readBlobError(blob) {
  try {
    return await blob.text()
  } catch (e) {
    return ''
  }
}

async function downloadLogs() {
  if (!canExport.value) return
  const userName = searchedUserName.value
  const filename = `用户操作日志_${safeFileNamePart(userName)}_${timestamp()}.xlsx`
  let handle = null

  try {
    if (supportsFilePicker()) {
      handle = await pickSaveFile(filename)
    }
  } catch (e) {
    if (e && e.name === 'AbortError') return
    handle = null
  }

  try {
    await ElMessageBox.confirm(
      `<p class="mb-lead">即将导出 <b>${userName}</b> 的全部操作日志。</p>` +
        `<p class="mb-sub">数据量较大时会耗时较久，确定继续？</p>`,
      '确认导出',
      {
        confirmButtonText: '开始导出',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: true,
        customClass: 'utility-export-confirm'
      }
    )
  } catch (e) {
    return
  }

  exporting.value = true
  try {
    const res = await exportUserLogs({ userName })
    const blob = res.data
    if (blob && blob.type && !blob.type.includes('spreadsheetml')) {
      const body = await readBlobError(blob)
      let msg = '导出失败'
      try {
        msg = JSON.parse(body).message || msg
      } catch (e) {
        if (body) msg = body.slice(0, 200)
      }
      throw new Error(msg)
    }
    if (handle) await writeBlobToHandle(handle, blob)
    else downloadBlobFallback(blob, filename)
    ElMessage.success('导出完成')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

// 机构查询栏只读、日期不可改（导出范围恒等于当前查询），确认框是纯文字提示。
// 预填信息都来自已提交的 searchedSchool / schoolTotal，不发额外请求。
function schoolDateLabel() {
  if (searchedSchool.dateStart && searchedSchool.dateEnd) {
    return `${searchedSchool.dateStart} ~ ${searchedSchool.dateEnd}`
  }
  return '全部时间'
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

async function downloadSchoolLogs() {
  if (!canExportSchool.value) return
  const school = searchedSchool.name
  const overSplit = schoolTotal.value > 500000
  const rowsText = schoolTotal.value.toLocaleString()
  const splitLine = overSplit
    ? `<p class="mb-sub">超过 50 万条，将导出为多个独立文件（.zip）</p>`
    : ''
  const body =
    `<div class="mb-alert"><span class="mb-alert-ico">⚠️</span>` +
    `<span>建议先执行日志同步，以取得最新数据</span></div>` +
    `<p class="mb-lead">机构：<b>${school}</b></p>` +
    `<p class="mb-sub">操作时间：${schoolDateLabel()}</p>` +
    `<p class="mb-sub">预估导出 ${rowsText} 条</p>` +
    splitLine

  // 先出品牌确认框。点「开始导出」是新的用户手势，那时再调 pickSaveFile 仍有效
  try {
    await ElMessageBox.confirm(body, '确认导出', {
      confirmButtonText: '开始导出',
      cancelButtonText: '取消',
      type: 'warning',
      dangerouslyUseHTMLString: true,
      customClass: 'utility-export-confirm'
    })
  } catch (e) {
    return
  }

  // 超量时后端返回 zip（多个独立 .xlsx），按总数在请求前就定好扩展名
  const ext = overSplit ? 'zip' : 'xlsx'
  const filename = `机构操作日志_${safeFileNamePart(school)}_${timestamp()}.${ext}`
  let handle = null
  try {
    if (supportsFilePicker()) {
      handle = await pickSaveFile(filename)
    }
  } catch (e) {
    if (e && e.name === 'AbortError') return
    handle = null
  }

  schoolExporting.value = true
  schoolExportVisible.value = true
  schoolExportRunning.value = true
  schoolExportPercent.value = 0
  schoolExportProcessed.value = 0
  schoolExportTotal.value = 0
  try {
    const params = { institution: school }
    if (searchedSchool.dateStart) params.dateStart = searchedSchool.dateStart
    if (searchedSchool.dateEnd) params.dateEnd = searchedSchool.dateEnd
    const startRes = await startSchoolExport(params)
    const startData = startRes.data || {}
    if (!startData.taskId) {
      throw new Error(startData.message || '导出任务启动失败')
    }
    activeExportTaskId = startData.taskId
    schoolExportTotal.value = Number(startData.total || 0)

    // 轮询真实进度；返回 true 表示已生成完可下载
    const ready = await pollSchoolExport()
    if (!ready) return

    const res = await downloadSchoolExport(activeExportTaskId)
    const blob = res.data
    // 正常返回 xlsx 或 zip；两者都不是才按错误文本读（后端回 JSON 错误）
    const isSpreadsheet = blob.type.includes('spreadsheetml')
    const isZip = blob.type.includes('zip')
    if (blob && !isSpreadsheet && !isZip) {
      const errBody = await readBlobError(blob)
      let msg = '导出失败'
      try {
        msg = JSON.parse(errBody).message || msg
      } catch (e) {
        if (errBody) msg = errBody.slice(0, 200)
      }
      throw new Error(msg)
    }
    if (handle) await writeBlobToHandle(handle, blob)
    else downloadBlobFallback(blob, filename)
    ElMessage.success(overSplit ? '导出完成，请解压压缩包' : '导出完成')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '导出失败')
  } finally {
    schoolExporting.value = false
    schoolExportVisible.value = false
    schoolExportRunning.value = false
    activeExportTaskId = ''
  }
}

/** 每秒轮询后台任务进度，直到 done/error/cancelled。true=已生成完、可下载。 */
async function pollSchoolExport() {
  while (schoolExportRunning.value) {
    await sleep(800)
    let p = null
    try {
      const res = await getSchoolExportProgress(activeExportTaskId)
      p = res.data || {}
    } catch (e) {
      if (e.response && e.response.status === 404) {
        throw new Error('导出任务已失效，请重新导出')
      }
      continue // 瞬时网络问题，下一轮重试
    }
    schoolExportProcessed.value = Number(p.processed || 0)
    if (p.total != null) schoolExportTotal.value = Number(p.total)
    const st = p.status
    if (st === 'done') {
      schoolExportPercent.value = 100
      return true
    }
    if (st === 'error') {
      throw new Error(p.error || '导出失败')
    }
    if (st === 'cancelled') {
      ElMessage.info('导出已取消')
      return false
    }
    // 生成中：最多显示到 99，done 时才跳 100
    schoolExportPercent.value = schoolExportTotal.value > 0
      ? Math.min(99, Math.round((schoolExportProcessed.value / schoolExportTotal.value) * 100))
      : 0
  }
  return false
}

/** 进度弹窗里的「取消导出」：停止轮询并通知后端中断（会删掉半成品文件）。 */
function cancelSchoolRun() {
  schoolExportRunning.value = false
  if (activeExportTaskId) {
    cancelSchoolExport(activeExportTaskId).catch(() => {})
  }
}
</script>

<style scoped>
.tools-wrap {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.tools-board {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
  max-width: 900px;
}

.tool-entry {
  min-height: 150px;
  padding: 18px;
  border: 1px solid rgba(99, 122, 165, 0.16);
  border-radius: 16px;
  background:
    linear-gradient(135deg, rgba(63, 140, 255, 0.08), rgba(64, 191, 129, 0.07)),
    #fff;
  box-shadow: 0 8px 24px rgba(15, 26, 54, 0.06);
  cursor: pointer;
  display: flex;
  align-items: flex-start;
  gap: 14px;
  text-align: left;
  transition: transform 0.2s ease, box-shadow 0.22s ease, border-color 0.22s ease;
}

.tool-entry:hover {
  transform: translateY(-3px);
  border-color: rgba(63, 140, 255, 0.35);
  box-shadow: 0 14px 30px rgba(15, 26, 54, 0.1);
}

.entry-icon {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: var(--brand-grad);
  box-shadow: 0 10px 22px rgba(63, 140, 255, 0.22);
  font-size: 22px;
}

.entry-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.entry-main strong {
  color: #2b3654;
  font-size: 18px;
}

.entry-main span {
  color: #8a95b0;
  font-size: 13px;
}

.entry-arrow {
  margin-left: auto;
  color: var(--brand-start);
  font-size: 28px;
  line-height: 1;
}

.tool-detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.detail-head {
  display: flex;
  justify-content: space-between;
  /* 返回按钮和右侧操作排都在这一行里**垂直居中**。
     标题块是两行（h2 + 副标题），它比按钮高，所以「居中」居的是整个标题块的中心 ——
     这是要的效果：按钮落在框的中间，不是贴到标题那一行上 */
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 16px 18px;
  border: 1px solid rgba(99, 122, 165, 0.16);
  border-radius: 16px;
  background: #fff;
}

/* 标题行：返回按钮和标题块一起垂直居中；再往上一层，整行又在 detail-head 里居中 */
.detail-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

/* 返回按钮 = 页面里标准的次要按钮，和「重置」同一套尺寸，另外加一层底色。
   底色是要的：卡片本身是白的，不给底色就只剩一圈极淡的描边，存在感太弱
   （el-button 默认底色是 rgba(255,255,255,.7)，在白卡上等于没有）。
   只覆盖底色和 hover，字号 / 圆角 / 内边距仍然走 el-button 的统一定义 ——
   早先这里自己配过 13px / 圆角 8px，和「重置」的 14px / 12px 对不上，
   摆在同一行里就不像一套东西，别再那么写 */
.detail-title-row .back-button {
  background: #f2f5fa;
  border-color: #dde5f0;
}
.detail-title-row .back-button:hover,
.detail-title-row .back-button:focus {
  background: #e8eefb;
  border-color: #cddffb;
  color: #2563eb;
}
.detail-title-row .back-button .arw {
  margin-right: 4px;
  font-size: 15px;
  line-height: 1;
}

.detail-title {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.detail-title h2 {
  margin: 0;
  color: #2b3654;
  font-size: 20px;
}

.detail-title span {
  margin-top: 3px;
  color: #8a95b0;
  font-size: 13px;
}

.detail-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.user-search {
  width: 220px;
}

.school-actions {
  flex-wrap: nowrap;
}

.school-actions :deep(.el-button) {
  flex-shrink: 0;
}

/*
 * 日期区间 300px / 机构名 220px。
 *
 * 必须用 :deep() 从父元素往下选，不能直接写 .school-date { width } ——
 * el-date-picker 和 el-autocomplete 是多根（input + teleport 出去的下拉面板），
 * Vue 的 scoped 无法给多根组件的根元素加 data-v 属性。实测这两个元素上
 * 一个 data-v 都没有，所以 .school-date[data-v-x] 这条规则永远匹配不上，
 * 表现就是宽度完全不生效（日期框停在自带的 350px 变量、机构名框撑满剩余空间）。
 * el-input 是单根、能拿到 data-v，所以用户日志导出那边直接写 .user-search 有效 ——
 * 别照抄那边的写法。
 *
 * :deep() 编译成 .school-actions[data-v-x] .school-date，data-v 落在
 * .school-actions（普通 div，有该属性）上，同时特异性 (0,3,0) 也压过
 * Element Plus 的 .el-date-editor.el-input__wrapper (0,2,0)。
 */
.school-actions :deep(.school-date) {
  flex: 0 0 300px;
  width: 300px;
}

.school-actions :deep(.user-search) {
  flex: 0 0 220px;
  width: 220px;
}

.tool-alert {
  margin-bottom: 0;
}

/* 学校日志导出进度弹窗正文 */
.export-progress-body {
  text-align: center;
}

.export-progress-body .el-progress {
  margin-top: 4px;
}

.export-progress-meta {
  margin: 18px 0 4px;
  color: #3d4864;
  font-size: 15px;
  font-variant-numeric: tabular-nums;
}

.export-progress-tip {
  margin: 6px 0 0;
  color: #b5620f;
  font-size: 12px;
}

.export-progress-actions {
  margin-top: 16px;
  display: flex;
  justify-content: center;
}

.summary-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.summary-item {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid rgba(99, 122, 165, 0.14);
  border-radius: 14px;
  background: #fff;
}

.summary-item span {
  display: block;
  color: #8a95b0;
  font-size: 12px;
}

.summary-item strong {
  display: block;
  margin-top: 6px;
  color: #3d4864;
  font-size: 18px;
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 学校日志导出用表格展示（与日志数据页一致）；下面的 .activity-* 是
   用户日志导出的卡片列表，两边展示形态不同，别当重复删掉 */
.log-table {
  margin-bottom: 18px;
}

.activity-list {
  min-height: 180px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.activity-empty {
  min-height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed rgba(99, 122, 165, 0.22);
  border-radius: 14px;
  color: #a3adc2;
  font-size: 13px;
}

.activity-item {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) minmax(260px, 0.8fr);
  gap: 14px;
  padding: 13px 15px;
  border: 1px solid rgba(99, 122, 165, 0.12);
  border-radius: 14px;
  background: #fff;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.activity-item:hover {
  transform: translateY(-1px);
  border-color: rgba(63, 140, 255, 0.28);
  box-shadow: 0 8px 18px rgba(15, 26, 54, 0.07);
}

.activity-main,
.activity-side {
  min-width: 0;
}

.activity-type {
  display: inline-flex;
  max-width: 100%;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--brand-grad-soft);
  color: #3d5fa8;
  font-size: 12.5px;
  font-weight: 700;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.activity-meta,
.activity-side {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 9px;
}

.activity-meta span,
.activity-side span {
  max-width: 220px;
  padding: 3px 8px;
  border-radius: 8px;
  background: rgba(99, 122, 165, 0.08);
  color: #5a6688;
  font-size: 12px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 2px;
}

@media (max-width: 980px) {
  .activity-item,
  .summary-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .tools-board {
    max-width: none;
  }

  .detail-actions,
  .detail-actions :deep(.el-button),
  .user-search {
    width: 100%;
  }

  /* 窄屏放开 nowrap，否则这一行会横向溢出撑破卡片 */
  .school-actions {
    flex-wrap: wrap;
  }

  /* 窄屏两个框各占满一行，同样要走 :deep() */
  .school-actions :deep(.school-date),
  .school-actions :deep(.user-search) {
    flex: 1 1 100%;
    width: 100%;
  }
}
</style>
