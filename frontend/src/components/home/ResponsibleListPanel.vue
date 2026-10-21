<template>
  <section class="responsible-section">
    <div class="panel-head">
      <div class="panel-title">
        <h2>负责人列表</h2>
        <span class="panel-count">共 {{ total.toLocaleString() }} 条</span>
      </div>
      <div class="toolbar-row">
        <el-input
          v-model="keyword"
          placeholder="学校 / 负责人 / 邮箱 / 区域 / 省份"
          clearable
          class="responsible-search"
          @keyup.enter="search"
          @clear="search"
        />
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
      class="responsible-table"
      empty-text="暂无可展示数据"
    >
      <el-table-column prop="ownerName" label="负责人" min-width="110" show-overflow-tooltip>
        <template #default="scope">{{ text(scope.row.ownerName) }}</template>
      </el-table-column>
      <el-table-column prop="email" label="邮箱" min-width="210" show-overflow-tooltip>
        <template #default="scope">{{ text(scope.row.email) }}</template>
      </el-table-column>
      <el-table-column prop="region" label="区域" min-width="90" show-overflow-tooltip>
        <template #default="scope">{{ text(scope.row.region) }}</template>
      </el-table-column>
      <el-table-column prop="province" label="省份" min-width="110" show-overflow-tooltip>
        <template #default="scope">{{ text(scope.row.province) }}</template>
      </el-table-column>
      <el-table-column prop="schoolName" label="学校" min-width="260" show-overflow-tooltip>
        <template #default="scope">{{ text(scope.row.schoolName) }}</template>
      </el-table-column>
      <el-table-column prop="expireDate" label="到期时间" min-width="120">
        <template #default="scope">
          <span :class="{ 'date-expired': isExpired(scope.row.expireDate) }">
            {{ text(scope.row.expireDate) }}
          </span>
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
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { getResponsiblePage } from '../../api/school'

const loading = ref(false)
const error = ref('')
const rows = ref([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const keyword = ref('')

const today = computed(() => new Date().toISOString().slice(0, 10))

function text(value) {
  if (value === null || value === undefined) return '—'
  const s = String(value).trim()
  return s === '' ? '—' : s
}

function isExpired(value) {
  if (typeof value !== 'string') return false
  const d = value.trim().slice(0, 10)
  return /^\d{4}-\d{2}-\d{2}$/.test(d) && d < today.value
}

async function fetchData() {
  loading.value = true
  error.value = ''
  try {
    const params = { pageNum: pageNum.value, pageSize: pageSize.value }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    const { data } = await getResponsiblePage(params)
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
/* 这个面板原来嵌在「学校列表」正下方，所以自带一段「和上面隔开」的上边距 + 分隔线。
   改成独立视图之后，那段就成了页面顶部的一块空白和一条多余的线，去掉。
   它现在是独立页面，上面没有东西需要隔开 —— 别再把这套加回来 */
.responsible-section {
  padding: 0;
}
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
.responsible-search { width: 300px; }
.responsible-table { margin-top: 12px; }
.table-alert { margin-bottom: 16px; }
.pagination-row { display: flex; justify-content: flex-end; margin-top: 16px; }
.date-expired { color: #e6813a; font-weight: 600; }
</style>
