<template>
  <section class="ipbind-section">
    <div class="panel-head">
      <div class="panel-title">
        <h2>加绑 IP 登记列表</h2>
        <span class="panel-count">共 {{ total.toLocaleString() }} 条</span>
      </div>
      <div class="toolbar-row">
        <el-input
          v-model="keyword"
          placeholder="姓名 / 用户名"
          clearable
          class="ipbind-search"
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
      class="ipbind-table"
      empty-text="暂无可展示数据"
    >
      <el-table-column prop="operatorName" label="操作人" min-width="120" show-overflow-tooltip>
        <template #default="scope">{{ text(scope.row.operatorName) }}</template>
      </el-table-column>
      <el-table-column prop="realName" label="姓名" min-width="120" show-overflow-tooltip>
        <template #default="scope">{{ text(scope.row.realName) }}</template>
      </el-table-column>
      <el-table-column prop="userName" label="账号" min-width="180" show-overflow-tooltip>
        <template #default="scope">{{ text(scope.row.userName) }}</template>
      </el-table-column>
      <el-table-column prop="operateTime" label="加绑IP时间" min-width="180">
        <template #default="scope">{{ text(scope.row.operateTime) }}</template>
      </el-table-column>
      <el-table-column prop="ip" label="IP" min-width="150" show-overflow-tooltip>
        <template #default="scope">{{ text(scope.row.ip) }}</template>
      </el-table-column>
      <el-table-column prop="school" label="学校" min-width="240" show-overflow-tooltip>
        <template #default="scope">{{ text(scope.row.school) }}</template>
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
import { onMounted, ref } from 'vue'
import { getIpBindRecords } from '../../api/userData'

const loading = ref(false)
const error = ref('')
const rows = ref([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)
const keyword = ref('')

function text(value) {
  if (value === null || value === undefined) return '—'
  const s = String(value).trim()
  return s === '' ? '—' : s
}

async function fetchData() {
  loading.value = true
  error.value = ''
  try {
    const params = { pageNum: pageNum.value, pageSize: pageSize.value }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    const { data } = await getIpBindRecords(params)
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
/* 同 ResponsibleListPanel：原来是「异常情况」页里禁用列表下面的第二段，
   拆成独立视图后顶部的间隔和分隔线都成了残留 */
.ipbind-section {
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
.ipbind-search { width: 260px; }
.ipbind-table { margin-top: 12px; }
.table-alert { margin-bottom: 16px; }
.pagination-row { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
