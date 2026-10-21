<template>
  <div class="audit-record-panel">
    <div class="panel-head">
      <div class="panel-title">
        <h2>审核记录</h2>
        <span class="panel-count">共 {{ pagination.total.toLocaleString() }} 条</span>
      </div>
      <div class="toolbar-row toolbar-row-wrap filter-toolbar">
        <el-input
          v-model="filters.keyword"
          placeholder="姓名 / 邮箱 / 机构"
          clearable
          class="filter-input"
          @clear="handleSearch"
          @keyup.enter="handleSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>

        <el-button type="primary" @click="handleSearch">
          <el-icon><Search /></el-icon>
          搜索
        </el-button>
        <el-button @click="handleReset">
          <el-icon><RefreshLeft /></el-icon>
          重置
        </el-button>
        <el-button :loading="loading" @click="loadData">
          刷新
        </el-button>
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
      :data="tableData"
      stripe
      border
      class="log-table"
      empty-text="暂无可展示数据"
    >
      <el-table-column
        v-for="col in columns"
        :key="col.key"
        :label="col.label"
        :min-width="col.minWidth"
        :width="col.width"
        :align="col.align || 'left'"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          <template v-if="col.type === 'file'">
            <button
              v-if="getFieldValue(row, col.fields)"
              type="button"
              class="file-pill"
              :class="col.fileClass"
              :title="`预览${col.label}`"
              @click="openFiles(row, col.fileKey)"
            >
              <span class="file-pill-icon">{{ col.icon }}</span>
              <span>预览</span>
            </button>
            <span v-else class="file-empty">—</span>
          </template>
          <template v-else-if="col.format === 'bool'">
            <span class="bool-pill" :class="boolClass(getFieldValue(row, col.fields))">
              {{ boolText(getFieldValue(row, col.fields)) }}
            </span>
          </template>
          <template v-else>
            {{ cellText(row, col.fields, col.format) }}
          </template>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-row">
      <el-pagination
        v-model:current-page="pagination.pageNum"
        v-model:page-size="pagination.pageSize"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :page-sizes="[10, 20, 50, 100]"
        :total="pagination.total"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </div>

    <RegisterFileViewer
      v-model="fileViewerVisible"
      :subject="fileSubject"
      :initial-key="fileViewerKey"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { RefreshLeft, Search } from '@element-plus/icons-vue'
import RegisterFileViewer from './RegisterFileViewer.vue'
import { getRegisterPage } from '@/api/userData'

const filters = reactive({
  keyword: ''
})

const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0
})

const loading = ref(false)
const error = ref('')
const tableData = ref([])

// 审核记录展示口径要和账号数据页一样走中文列名，不再把原始字段名直接丢出来。
// 宽度按实测内容定的：姓名最长「刘倩雅」文本 42px、学制最长「—」15px，
// 加上单元格左右各 12px 内边距，66 / 52 就够。原来给的 140 / 90 是富余太多，
// 13 列因此撑到 1686px、比内容区 1648 宽出 38px，逼出一条横向滚动条。
// 收掉这 60px 之后列宽合计 1626，Element Plus 会把富余的 22px 再均摊回各列。
const COLUMN_DEFS = [
  { key: 'Name', fields: ['Name', 'name'], label: '姓名', minWidth: 100, order: 1 },
  { key: 'Email', fields: ['Email', 'email'], label: '邮箱', minWidth: 220, order: 2 },
  { key: 'School', fields: ['School', 'school'], label: '机构', minWidth: 180, order: 3 },
  { key: 'xuezhi', fields: ['xuezhi', 'Xuezhi'], label: '学制', minWidth: 70, order: 4 },
  { key: 'ruxueYear', fields: ['ruxueYear', 'ruxueyear', 'RuxueYear'], label: '入学年份', minWidth: 110, order: 5 },
  { key: 'applicationTime', fields: ['applicationTime', 'ApplicationTime'], label: '提交时间', minWidth: 130, order: 6, format: 'date' },
  { key: 'daitongzhiTime', fields: ['daitongzhiTime', 'DaitongzhiTime'], label: '开通时间', minWidth: 130, order: 7, format: 'date' },
  { key: 'registerEntry', fields: ['registerEntry'], label: '注册入口', minWidth: 100, order: 8, format: 'entry', align: 'center' },
  { key: 'isactive', fields: ['isactive', 'isActive'], label: '是否激活', minWidth: 100, order: 20, format: 'bool', align: 'center' },
  { key: 'isdoctorteach', fields: ['isdoctorteach', 'isDoctorTeach'], label: '是否博导/教师', minWidth: 130, order: 21, format: 'bool', align: 'center' },
  { key: 'isReject', fields: ['isReject', 'isreject'], label: '是否驳回', minWidth: 100, order: 22, format: 'bool', align: 'center' },
  { key: 'AgreeFile', fields: ['AgreeFile', 'agreeFile'], label: '使用协议', type: 'file', fileKey: 'agree', fileClass: 'agree', icon: '📜', width: 128, align: 'center', order: 90 },
  { key: 'Workfile', fields: ['Workfile', 'workFile'], label: '注册资料', type: 'file', fileKey: 'work', fileClass: 'work', icon: '🪪', width: 128, align: 'center', order: 91 }
]

const columns = computed(() => [...COLUMN_DEFS].sort((a, b) => a.order - b.order))



const fileViewerVisible = ref(false)
const fileViewerKey = ref('agree')
const fileSubject = ref({})

function getFieldValue(row, fields) {
  const candidates = Array.isArray(fields) ? fields : [fields]
  for (const field of candidates) {
    const value = row?.[field]
    if (value !== null && value !== undefined && value !== '') {
      return value
    }
  }
  return ''
}

function boolText(value) {
  if (value === true || value === 'true' || value === '1' || value === 1) return '是'
  if (value === false || value === 'false' || value === '0' || value === 0) return '否'
  return '—'
}

function boolClass(value) {
  if (value === true || value === 'true' || value === '1' || value === 1) return 'bool-yes'
  if (value === false || value === 'false' || value === '0' || value === 0) return 'bool-no'
  return 'bool-unknown'
}

function cellText(row, fields, format) {
  const value = getFieldValue(row, fields)
  if (value === '' || value === null || value === undefined) return '—'
  if (format === 'bool') return boolText(value)
  if (format === 'entry') {
    if (value === 'miniprogram') return '小程序'
    if (value === 'web') return '网页'
    return '—'
  }
  if (format === 'datetime') {
    const text = String(value)
    return text.replace(/\.\d+$/, '').replace('T', ' ')
  }
  if (format === 'date') {
    const text = String(value)
    const match = text.match(/^\d{4}-\d{2}-\d{2}/)
    return match ? match[0] : text.substring(0, 10)
  }
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function openFiles(row, key) {
  fileSubject.value = {
    userName: getFieldValue(row, ['Name', 'name']),
    nickName: getFieldValue(row, ['Name', 'name']),
    mail: getFieldValue(row, ['Email', 'email']),
    AgreeFile: getFieldValue(row, ['AgreeFile', 'agreeFile']),
    Workfile: getFieldValue(row, ['Workfile', 'workFile']),
    matchCount: Number(getFieldValue(row, ['RegisterMatchCount', 'matchCount']) || 0),
    registerId: getFieldValue(row, ['RegisterId', 'registerId', '_id'])
  }
  fileViewerKey.value = key
  fileViewerVisible.value = true
}

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const params = {
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize
    }

    if (filters.keyword) params.keyword = filters.keyword

    const res = await getRegisterPage(params)
    if (res.data?.data) {
      tableData.value = res.data.data.records || []
      pagination.total = res.data.data.total || 0
    } else {
      error.value = res.data?.message || '加载失败'
      ElMessage.error(error.value)
    }
  } catch (err) {
    console.error('加载审核记录失败:', err)
    error.value = '加载失败'
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.pageNum = 1
  loadData()
}

function handleReset() {
  filters.keyword = ''
  pagination.pageNum = 1
  loadData()
}

function handleSizeChange() {
  pagination.pageNum = 1
  loadData()
}

function handlePageChange() {
  loadData()
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.audit-record-panel {
  padding: 0;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.panel-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

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

.toolbar-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.filter-toolbar {
  justify-content: flex-end;
  margin-left: auto;
}

.filter-input {
  width: 280px;
}

.table-alert {
  margin-bottom: 16px;
}

.log-table {
  margin-bottom: 18px;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
}

.file-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 11px;
  border: 1px solid rgba(63, 140, 255, 0.28);
  border-radius: 999px;
  background: linear-gradient(135deg, rgba(63, 140, 255, 0.1), rgba(108, 99, 255, 0.1));
  color: #4462b8;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.18s cubic-bezier(0.34, 1.56, 0.64, 1),
              box-shadow 0.22s ease, background 0.22s ease, border-color 0.22s ease;
}

.file-pill:hover {
  transform: translateY(-2px);
  border-color: transparent;
  background: var(--brand-grad);
  color: #fff;
  box-shadow: 0 8px 18px rgba(63, 140, 255, 0.34);
}

.file-pill:active {
  transform: translateY(0);
}

.file-pill.work {
  border-color: rgba(90, 215, 255, 0.4);
  background: linear-gradient(135deg, rgba(90, 215, 255, 0.14), rgba(63, 140, 255, 0.12));
  color: #2f7d9e;
}

.file-pill.work:hover {
  border-color: transparent;
  background: linear-gradient(135deg, #34b6e6 0%, #3f8cff 100%);
  color: #fff;
  box-shadow: 0 8px 18px rgba(52, 182, 230, 0.34);
}

.bool-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 42px;
  padding: 4px 10px;
  border-radius: 999px;
  font-family: "Microsoft YaHei", "微软雅黑", "PingFang SC", "Helvetica Neue", sans-serif;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
  border: 1px solid transparent;
}

.bool-yes {
  color: #1f8f4f;
  background: rgba(31, 143, 79, 0.1);
  border-color: rgba(31, 143, 79, 0.18);
}

.bool-no {
  color: #d34d4d;
  background: rgba(211, 77, 77, 0.1);
  border-color: rgba(211, 77, 77, 0.18);
}

.bool-unknown {
  color: #8a93a8;
  background: rgba(138, 147, 168, 0.1);
  border-color: rgba(138, 147, 168, 0.18);
}

.file-pill-icon {
  font-size: 13px;
  line-height: 1;
}

.file-empty {
  color: #b6bfd2;
  cursor: help;
  user-select: none;
}
</style>
