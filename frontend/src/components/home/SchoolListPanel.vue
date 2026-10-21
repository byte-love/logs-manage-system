<template>
  <div class="school-wrap">
    <div class="panel-head">
      <div class="panel-title">
        <h2>学校列表</h2>
        <span class="panel-count">共 {{ total.toLocaleString() }} 条</span>
      </div>
      <div class="toolbar-row">
        <el-input
          v-model="keyword"
          placeholder="单位 / IP / 管理员 / 邮箱后缀"
          clearable
          class="school-search"
          @keyup.enter="search"
          @clear="search"
        />
        <!-- 选完直接查，不用再点搜索：下拉本身就是一次明确的筛选动作 -->
        <el-select
          v-model="baseFilter"
          placeholder="正式库"
          clearable
          class="school-base-filter"
          @change="search"
        >
          <el-option label="不限" value="" />
          <el-option label="正式库" value="formal" />
          <el-option label="基础库" value="base" />
        </el-select>
        <el-button type="primary" @click="search">搜索</el-button>
        <el-button @click="reset">重置</el-button>
        <el-button :loading="loading" @click="fetchData">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      class="table-alert"
      :closable="false"
    />

    <el-table
      v-loading="loading"
      :data="rows"
      stripe
      border
      class="school-table"
      empty-text="暂无可展示数据"
    >
      <el-table-column prop="Adress" label="单位名称" min-width="210" show-overflow-tooltip>
        <template #default="scope">{{ text(scope.row.Adress) }}</template>
      </el-table-column>
      <el-table-column prop="suf" label="邮箱后缀" min-width="150" show-overflow-tooltip>
        <template #default="scope">{{ text(scope.row.suf) }}</template>
      </el-table-column>
      <el-table-column prop="Ip" label="IP 段" min-width="250">
        <template #default="scope">
          <!-- IP 段最长 2286 字符、84 段，单元格只显示首段 + 总数，全量进抽屉 -->
          <span v-if="!ipList(scope.row.Ip).length">—</span>
          <div v-else class="ip-cell">
            <span class="ip-first" :title="ipList(scope.row.Ip)[0]">{{ ipList(scope.row.Ip)[0] }}</span>
            <el-link
              v-if="ipList(scope.row.Ip).length > 1"
              type="primary"
              :underline="false"
              class="cell-link"
              @click="openIp(scope.row)"
            >+{{ ipList(scope.row.Ip).length - 1 }}</el-link>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="mangerName" label="管理员" min-width="130" show-overflow-tooltip>
        <template #default="scope">{{ text(scope.row.mangerName) }}</template>
      </el-table-column>
      <el-table-column prop="endDate" label="到期时间" min-width="120">
        <template #default="scope">
          <span :class="{ 'date-expired': isExpired(scope.row.endDate) }">
            {{ text(scope.row.endDate) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="base" label="正式库" min-width="80" align="center">
        <template #default="scope">
          <el-tag v-if="scope.row.base === false" type="success" size="small" effect="light">是</el-tag>
          <el-tag v-else-if="scope.row.base === true" type="info" size="small" effect="light">否</el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column prop="jurisdiction" label="授权库" min-width="110" align="center">
        <template #default="scope">
          <el-link
            v-if="jurisdictionCount(scope.row.jurisdiction) > 0"
            type="primary"
            :underline="false"
            @click="openJurisdiction(scope.row)"
          >{{ jurisdictionCount(scope.row.jurisdiction) }} 个</el-link>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="协议" min-width="170" align="center">
        <template #default="scope">
          <!-- 不直接链到 OSS 地址：那些对象带 Content-Disposition: attachment，
               点开只会下载。走后端代理换成 inline 后在弹窗里预览 -->
          <div class="file-links">
            <el-link
              v-if="hasUrl(scope.row.agreement)"
              type="primary"
              :underline="false"
              @click="openAgreement(scope.row, 'agree')"
            >使用协议</el-link>
            <span v-else class="file-missing">—</span>
            <el-link
              v-if="hasUrl(scope.row.agreement_student)"
              type="primary"
              :underline="false"
              @click="openAgreement(scope.row, 'student')"
            >学生协议</el-link>
            <span v-else class="file-missing">—</span>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-row">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :page-sizes="[5, 10, 20, 50]"
        :total="total"
        @current-change="fetchData"
        @size-change="handleSizeChange"
      />
    </div>

    <!-- 授权库明细 -->
    <el-dialog
      v-model="jurisdictionVisible"
      :title="detailTitle"
      width="720px"
      append-to-body
    >
      <el-table :data="jurisdictionRows" stripe border max-height="440" size="small">
        <el-table-column prop="code" label="库代码" min-width="120" />
        <el-table-column prop="level" label="level" min-width="90" align="center" />
        <el-table-column prop="expirydate" label="到期时间" min-width="160" />
      </el-table>
    </el-dialog>

    <!-- 完整 IP 段 -->
    <el-dialog
      v-model="ipVisible"
      :title="detailTitle"
      width="640px"
      append-to-body
    >
      <div class="ip-all">
        <div v-for="(seg, i) in ipRows" :key="i" class="ip-seg">{{ seg }}</div>
      </div>
    </el-dialog>

    <!-- 协议 PDF 预览 -->
    <AgreementViewer
      v-model="agreementVisible"
      :subject="agreementSubject"
      :initial-key="agreementKey"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { getSchoolPage } from '../../api/school'
import AgreementViewer from './AgreementViewer.vue'

const loading = ref(false)
const error = ref('')
const rows = ref([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const keyword = ref('')
// '' | 'formal' | 'base'，清空即不筛选。源字段 base=true 其实是基础库，
// 正式库要反过来查 base=false；存字符串避免和清空态混淆。
const baseFilter = ref('')

const jurisdictionVisible = ref(false)
const ipVisible = ref(false)
const detailTitle = ref('')
const jurisdictionRows = ref([])
const ipRows = ref([])

const agreementVisible = ref(false)
const agreementKey = ref('agree')
const agreementSubject = ref({})

const today = computed(() => new Date().toISOString().slice(0, 10))

function text(value) {
  if (value === null || value === undefined) return '—'
  const s = String(value).trim()
  return s === '' ? '—' : s
}

function hasUrl(value) {
  return typeof value === 'string' && value.trim().startsWith('http')
}

// endDate 是 'YYYY-MM-DD'（后端已 trim 掉 CHAR 填充空格），按字符串比即可，
// 不走 Date 解析以免时区偏移
function isExpired(value) {
  if (typeof value !== 'string') return false
  const d = value.trim().slice(0, 10)
  return /^\d{4}-\d{2}-\d{2}$/.test(d) && d < today.value
}

function ipList(value) {
  if (typeof value !== 'string') return []
  return value.split(',').map((s) => s.trim()).filter(Boolean)
}

// jurisdiction 存的是 JSON 字符串（不是嵌套文档），形如
// {"CNSP":{"level":2,"expirydate":"2027-07-23 00:00:00"}, ...}
// 实测 287 行全部可解析、每行 2~222 个库，原文最长 12000 字符。
// 解析失败就当没有，不让一行脏数据崩掉整页
function parseJurisdiction(value) {
  if (!value || typeof value !== 'string') return []
  let parsed
  try {
    parsed = JSON.parse(value)
  } catch (e) {
    return []
  }
  if (!parsed || typeof parsed !== 'object') return []
  return Object.keys(parsed).map((code) => {
    const v = parsed[code]
    const detail = v && typeof v === 'object' ? v : {}
    return {
      code,
      level: detail.level === undefined || detail.level === null ? '—' : String(detail.level),
      expirydate: detail.expirydate ? String(detail.expirydate) : '—'
    }
  })
}

function jurisdictionCount(value) {
  return parseJurisdiction(value).length
}

function openJurisdiction(row) {
  detailTitle.value = `${text(row.Adress)} · 授权库`
  jurisdictionRows.value = parseJurisdiction(row.jurisdiction)
  jurisdictionVisible.value = true
}

function openIp(row) {
  detailTitle.value = `${text(row.Adress)} · IP 段`
  ipRows.value = ipList(row.Ip)
  ipVisible.value = true
}

// 只把 id 和「有没有这份协议」交给查看器，URL 由后端按 id 查库得到，
// 前端不传地址（否则接口等于开了个任意地址代理）
function openAgreement(row, kind) {
  agreementSubject.value = {
    id: row._id,
    adress: text(row.Adress),
    hasAgree: hasUrl(row.agreement),
    hasStudent: hasUrl(row.agreement_student)
  }
  agreementKey.value = kind
  agreementVisible.value = true
}

async function fetchData() {
  loading.value = true
  error.value = ''
  try {
    const params = { pageNum: pageNum.value, pageSize: pageSize.value }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (baseFilter.value === 'formal') params.base = 'false'
    else if (baseFilter.value === 'base') params.base = 'true'
    const { data } = await getSchoolPage(params)
    if (data && data.success) {
      rows.value = data.records || []
      total.value = data.total || 0
    } else {
      rows.value = []
      total.value = 0
      error.value = (data && data.message) || '加载失败'
    }
  } catch (e) {
    rows.value = []
    total.value = 0
    error.value = e?.response?.data?.message || e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function search() {
  pageNum.value = 1
  fetchData()
}

function reset() {
  keyword.value = ''
  baseFilter.value = ''
  pageNum.value = 1
  fetchData()
}

function handleSizeChange() {
  pageNum.value = 1
  fetchData()
}

onMounted(fetchData)
</script>

<style scoped>
/* Home.vue 的样式是 scoped，子组件内部节点拿不到它的 .panel-head 等类，
   所以这里按同一套视觉重写一份，保持各页签外观一致 */
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.panel-title { display: flex; align-items: baseline; gap: 12px; }
.panel-title h2 {
  font-size: 22px;
  position: relative;
  padding-left: 14px;
  color: #192440;
}
.panel-title h2::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 5px;
  height: 22px;
  border-radius: 4px;
  background: var(--brand-grad);
}
.panel-count {
  font-size: 13px;
  color: #6b76a0;
  padding: 3px 12px;
  border-radius: 999px;
  background: var(--brand-grad-soft);
}
.toolbar-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }

.school-search { width: 240px; }
.school-base-filter { width: 130px; }
.school-table { margin-top: 12px; }
.table-alert { margin-bottom: 16px; }
.pagination-row { display: flex; justify-content: flex-end; margin-top: 16px; }

/* IP 首段与 +N 必须同排：段本身就长（最长 2286 字符/84 段），
   一换行整个表格行高会跳 */
.ip-cell { display: flex; align-items: center; gap: 8px; min-width: 0; }
.ip-first {
  font-variant-numeric: tabular-nums;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cell-link { font-size: 12px; flex-shrink: 0; }
.date-expired { color: #e6813a; font-weight: 600; }

/* 两个协议链接并排且不换行 */
.file-links { display: flex; align-items: center; justify-content: center; gap: 12px; }
.file-links :deep(.el-link) { white-space: nowrap; }
.file-missing { color: #9aa3bd; }

.ip-all {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 8px;
  max-height: 420px;
  overflow-y: auto;
}
.ip-seg {
  padding: 6px 10px;
  border-radius: 8px;
  background: rgba(99, 122, 165, 0.08);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
</style>
