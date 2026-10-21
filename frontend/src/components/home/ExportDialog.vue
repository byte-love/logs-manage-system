<template>
  <el-dialog
    :model-value="modelValue"
    title="导出数据"
    width="520px"
    destroy-on-close
    @update:model-value="close"
  >
    <div class="ed-body">
      <div class="ed-section">
        <div class="ed-title">导出范围</div>
        <el-radio-group v-model="scope" class="ed-radio-group">
          <el-radio value="current">导出当前页（{{ currentRowCount }} 行）</el-radio>
          <el-radio value="all">
            导出全部{{ allRowCount != null ? `（共 ${allRowCount.toLocaleString()} 行）` : '' }}
          </el-radio>
        </el-radio-group>
      </div>
      <div v-if="currentFilters.length" class="ed-section">
        <div class="ed-title">当前筛选条件</div>
        <div class="ed-filters">
          <el-tag
            v-for="f in currentFilters"
            :key="f.key"
            size="small"
            type="info"
          >{{ f.label }}</el-tag>
        </div>
      </div>
    </div>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="exporting" @click="doExport">确定导出</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { exportAccount, exportRanking } from '../../api/userData'
import {
  supportsFilePicker,
  pickSaveFile,
  writeBlobToHandle,
  downloadBlobFallback
} from '../../utils/download'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  type: { type: String, required: true },
  filters: { type: Object, default: () => ({}) },
  currentFilters: { type: Array, default: () => [] },
  currentRowCount: { type: Number, default: 0 },
  allRowCount: { type: Number, default: null }
})

const emit = defineEmits(['update:modelValue'])

const scope = ref('current')
const exporting = ref(false)

function close() {
  emit('update:modelValue', false)
}

async function doExport() {
  const isAccount = props.type === 'account'
  const kind = isAccount ? '账号数据' : '异常操作排行'
  const ts = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19)
  const filename = `${kind}_${ts}.xlsx`

  // 第一步：在用户点击手势仍有效时先弹出保存位置选择框。
  // 必须在任何网络 await 之前，否则手势过期，Chrome 会拒绝弹框。
  let handle = null
  if (supportsFilePicker()) {
    try {
      handle = await pickSaveFile(filename)
    } catch (err) {
      if (err?.name === 'AbortError') return // 用户取消，直接中止
      handle = null // 其他异常则降级为默认下载
    }
  }

  exporting.value = true
  try {
    const params = isAccount ? accountExportParams() : rankingExportParams()
    const apiFn = isAccount ? exportAccount : exportRanking
    const res = await apiFn(params)
    const blob = res instanceof Blob ? res : res.data
    // 后端若抛异常，responseType=blob 会把 JSON/文本错误也包成 blob。
    // xlsx 的 type 含 spreadsheetml，否则视为错误，读出文本提示用户
    if (blob && blob.type && blob.type.indexOf('spreadsheet') === -1) {
      const text = await blob.text()
      let msg = '导出失败'
      try {
        msg = JSON.parse(text).message || msg
      } catch (e) {
        if (text) msg = text.slice(0, 200)
      }
      throw new Error(msg)
    }
    if (handle) {
      await writeBlobToHandle(handle, blob)
    } else {
      downloadBlobFallback(blob, filename)
    }
    ElMessage.success('导出成功')
    close()
  } catch (err) {
    ElMessage.error(err.response?.data?.message || err.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

function accountExportParams() {
  const p = { currentPage: scope.value === 'current' }
  if (scope.value === 'current') {
    p.pageNum = props.filters.pageNum || 1
    p.pageSize = props.filters.pageSize || 10
  }
  // 「全部」不再传 pageSize —— 后端流式导出按筛选取全量（上限 50 万，见 MAX_EXPORT_ROWS）
  // 透传所有筛选
  if (props.filters.keyword) p.keyword = props.filters.keyword
  if (props.filters.userName) p.userName = props.filters.userName
  if (props.filters.nickName) p.nickName = props.filters.nickName
  if (props.filters.mail) p.mail = props.filters.mail
  if (props.filters.institution) p.institution = props.filters.institution
  if (props.filters.college) p.college = props.filters.college
  if (props.filters.position) p.position = props.filters.position
  if (props.filters.xuezhi) p.xuezhi = props.filters.xuezhi
  if (props.filters.ruxueYear) p.ruxueYear = props.filters.ruxueYear
  if (props.filters.accountType) p.accountType = props.filters.accountType
  if (props.filters.accountStatus) p.accountStatus = props.filters.accountStatus
  if (props.filters.applyChannel) p.applyChannel = props.filters.applyChannel
  return p
}

function rankingExportParams() {
  const p = { currentPage: scope.value === 'current' }
  // limit = 用户选的档位（前 N 名，0=全部），后端据此取前 N 名，和榜单同一口径
  p.limit = props.filters.rankLimit != null ? props.filters.rankLimit : 0
  if (scope.value === 'current') {
    // 导出屏幕上当前这一页：分页时传真实页码/页大小，未分页时一页装下整个档位
    p.pageNum = props.filters.rankPaged ? (props.filters.rankPageNum || 1) : 1
    p.pageSize = props.filters.rankPaged
      ? (props.filters.rankPageSize || 50)
      : (props.filters.rankVisibleCount || 5000)
  }
  if (props.filters.withinDays) p.withinDays = props.filters.withinDays
  if (props.filters.position) p.position = props.filters.position
  if (props.filters.accountStatus) p.accountStatus = props.filters.accountStatus
  if (props.filters.applyChannel) p.applyChannel = props.filters.applyChannel
  return p
}
</script>

<style scoped>
.ed-body {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.ed-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ed-title {
  color: #4a5573;
  font-size: 13px;
  font-weight: 600;
}
.ed-radio-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ed-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
