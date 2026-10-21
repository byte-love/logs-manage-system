<template>
  <div class="delivery-panel">
    <div class="panel-head">
      <div class="panel-title">
        <h2>数据交付记录</h2>
        <span class="panel-count">共 {{ pagination.total.toLocaleString() }} 条</span>
      </div>
      <div class="toolbar-row toolbar-row-wrap filter-toolbar">
        <el-input
          v-model="filters.keyword"
          placeholder="账号 / 姓名 / 学校 / 申请ID"
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
          <template v-if="col.type === 'image'">
            <button
              v-if="valueOf(row, col.key)"
              type="button"
              class="file-pill"
              title="预览协议图片"
              @click="openImage(row, col.key)"
            >
              <span class="file-pill-icon">🖼️</span>
              <span>预览</span>
            </button>
            <span v-else class="file-empty">—</span>
          </template>
          <template v-else-if="col.type === 'status'">
            <span class="status-pill" :class="statusClass(valueOf(row, col.key))">
              {{ statusText(valueOf(row, col.key)) }}
            </span>
          </template>
          <template v-else-if="col.type === 'list-count'">
            <!-- 表格里只给个数：一屏十几个库名铺不开，数字才能一眼比大小。
                 完整清单分两个出口 —— 悬停即看，点开是能逐字过滤、复制的抽屉 -->
            <el-tooltip v-if="listCount(valueOf(row, col.key))" placement="top" :show-after="180">
              <template #content>
                <div style="max-width: 380px; max-height: 50vh; overflow: auto; line-height: 1.75">
                  <div v-for="(item, index) in listItems(valueOf(row, col.key))" :key="index">{{ item }}</div>
                </div>
              </template>
              <button
                type="button"
                class="count-pill"
                title="点击查看完整清单"
                @click="openDetail(col, valueOf(row, col.key))"
              >{{ listCount(valueOf(row, col.key)) }}</button>
            </el-tooltip>
            <span v-else class="file-empty">—</span>
          </template>
          <template v-else-if="isLongValue(valueOf(row, col.key))">
            <button type="button" class="detail-link" @click="openDetail(col, valueOf(row, col.key))">
              {{ summaryOf(valueOf(row, col.key)) }}
            </button>
          </template>
          <template v-else>
            {{ cellText(row, col) }}
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

    <!-- 长值（数据库列表这类）在抽屉里看全量，表格里只留摘要，否则列会被撑爆 -->
    <el-drawer v-model="detail.visible" :title="detail.title" size="min(620px, 92vw)">
      <div v-if="detail.isList" class="detail-tools">
        <el-input v-model="detail.filter" placeholder="过滤条目" clearable class="detail-filter">
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <span class="detail-count">{{ detailItems.length }} / {{ detail.list.length }}</span>
        <el-button size="small" @click="copyDetail">
          {{ detail.filter ? '复制筛选结果' : '复制全部' }}
        </el-button>
      </div>

      <div v-if="detail.isList" class="detail-list">
        <div v-for="(item, index) in detailItems" :key="index" class="detail-item">{{ item }}</div>
        <div v-if="!detailItems.length" class="detail-empty">—</div>
      </div>

      <template v-else>
        <div class="detail-text">{{ detail.text }}</div>
        <el-button type="primary" class="detail-copy" @click="copyDetail">复制</el-button>
      </template>
    </el-drawer>

    <RegisterFileViewer v-model="imageVisible" :subject="imageSubject" initial-key="agree" />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { RefreshLeft, Search } from '@element-plus/icons-vue'
import RegisterFileViewer from './RegisterFileViewer.vue'
import { getDeliveryAuditPage } from '@/api/userData'

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

// 列的显示顺序与中文名。
//
// 原先只有「主表那几列」是有序的，其余字段一律按接口返回顺序补在后面 ——
// 等于把顺序交给了 Mongo 文档的字段顺序，排出来是这样：
//   账号 / 姓名 / 学校 / 状态 / 操作时间 / 驳回原因 / 使用协议 / 确认时间 /
//   邮箱 / 审核人 / 申请状态 / 数据库列表 / 数据库编码 / 申请时间 / 审批时间
// 两处明显不顺：「使用协议」那张图把「操作时间 / 确认时间」劈成了两截；
// 「数据库列表」——这一页最该看的东西——被挤到倒数第四。
// （下面原本还有一段专门把 auditNickName 挪到邮箱后面的补丁，也是在对这个顺序打补丁。）
//
// 现在整表排死成一条阅读线：谁 → 申请了什么 → 审成什么样 → 证据 → 申请单其余字段。
// 两张表以后加字段，没列进来的按接口返回顺序补在最后，不会丢列。
const COLUMN_ORDER = [
  // 谁
  { key: 'account', label: '账号', minWidth: 140 },
  { key: 'nickName', label: '姓名', minWidth: 120 },
  { key: 'school', label: '学校', minWidth: 180 },
  { key: 'apply.email', label: '邮箱', minWidth: 180 },
  // 申请了什么。databases 在申请表上，带前缀和不带前缀两种写法都留着 ——
  // 前端一直靠「表里有什么显示什么」兜底，这里不把话说死。
  // 这一列表格里只显示库的数量，完整清单放悬停提示和点击展开的抽屉里（见 list-count 分支）；
  // 抽屉标题用 detailTitle 另起一个 —— 「库数量」当清单的标题不成话
  {
    key: 'apply.databases',
    label: '库数量',
    detailTitle: '数据库列表',
    minWidth: 100,
    align: 'center',
    type: 'list-count'
  },
  {
    key: 'databases',
    label: '库数量',
    detailTitle: '数据库列表',
    minWidth: 100,
    align: 'center',
    type: 'list-count'
  },
  // 审成什么样
  { key: 'operateStatus', label: '状态', minWidth: 100, align: 'center', type: 'status' },
  { key: 'responsiblePerson', label: '销售负责人', minWidth: 120 },
  { key: 'auditNickName', label: '审核人', minWidth: 120 },
  // 字段名虽然叫 operateTime，但这一列装的确实是审批时间，列名就按实际的叫
  { key: 'operateTime', label: '审批时间', minWidth: 170 },
  { key: 'confirmTime', label: '确认时间', minWidth: 170 },
  // 证据
  { key: 'protocolUrl', label: '使用协议', minWidth: 110, width: 110, align: 'center', type: 'image' },
  // 驳回原因排在协议之后是用户指定的，别按「先原因后证据」的逻辑挪回去
  { key: 'rejectReason', label: '驳回原因', minWidth: 160 },
  // 申请单上的其余字段（多数情况下申请侧和主表同值的列已被 isRedundantApplyColumn 挡掉）
  { key: 'apply.status', label: '申请状态', minWidth: 100, align: 'center', type: 'status' },
  { key: 'apply.createTime', label: '申请时间', minWidth: 170 },
  { key: 'apply.approveTime', label: '审批时间', minWidth: 170 },
  { key: 'apply.protocolUrl', label: '申请协议', minWidth: 110, width: 110, align: 'center', type: 'image' }
]

/**
 * 这几列无论如何都要有。Mongo 文档的字段是稀疏的，某页恰好缺某个字段时列不能跟着消失，
 * 否则翻页时表头会一跳一跳。申请表的列不在此列 —— 它们本来就是按当页数据长出来的。
 */
const ALWAYS_SHOWN = [
  'account', 'nickName', 'school', 'responsiblePerson', 'operateStatus', 'auditNickName',
  'operateTime', 'confirmTime', 'rejectReason', 'protocolUrl'
]

const COLUMN_MAP = new Map(COLUMN_ORDER.map((col) => [col.key, col]))
const ORDER_INDEX = new Map(COLUMN_ORDER.map((col, index) => [col.key, index]))

/** 没排进 COLUMN_ORDER 的字段一律靠后，并保持它们之间接口返回的原有顺序 */
function columnPosition(key, index) {
  return ORDER_INDEX.has(key) ? ORDER_INDEX.get(key) : COLUMN_ORDER.length + index
}

// 不上表格的键（apply 前缀的同理，一并按字段名挡掉）：
//   _id / deliveryApplyId —— 内部主键与关联键，用户按需求不要这两列
//   phone —— 手机号属敏感信息，后端已剥掉不回传，这里再挡一道
// responsiblePerson 原本也在这张名单里，2026-09-18 起要显示了，
// 列名「销售负责人」（见 COLUMN_ORDER）
const HIDDEN_KEYS = new Set(['_id', 'deliveryApplyId', 'phone'])

// 申请表的字段名按实际文档配中文；没配到的原样展示
const APPLY_LABELS = {
  account: '申请账号',
  nickName: '申请姓名',
  school: '申请学校',
  responsiblePerson: '申请负责人',
  phone: '手机号',
  email: '邮箱',
  status: '申请状态',
  rejectReason: '申请驳回原因',
  databases: '数据库列表',
  databaseCodes: '数据库编码',
  protocolUrl: '申请协议',
  createTime: '申请时间',
  confirmTime: '确认时间',
  approveTime: '审批时间'
}

function applyLabel(key) {
  if (!key.startsWith('apply.')) return key
  const field = key.slice(6)
  return APPLY_LABELS[field] || field
}

const columns = computed(() => {
  const keys = []
  const seen = new Set()

  const push = (key) => {
    if (!seen.has(key)) {
      seen.add(key)
      keys.push(key)
    }
  }

  ALWAYS_SHOWN.forEach((key) => push(key))

  for (const row of tableData.value) {
    if (!row || typeof row !== 'object') continue
    Object.keys(row).forEach((key) => {
      // _class 是 Spring Data 写进文档的实现字段，不是业务数据，不上表格
      if (key === 'apply' || key === '_class' || HIDDEN_KEYS.has(key)) return
      push(key)
    })
    Object.keys(row.apply || {}).forEach((key) => {
      // apply._id 就是关联用的申请 ID，和主表的申请ID同值，一并不上表格
      if (key === '_class' || HIDDEN_KEYS.has(key)) return
      push(`apply.${key}`)
    })
  }

  // 先记下各自在数据里的出现位置，排不进 COLUMN_ORDER 的字段靠它保留原有先后
  return keys
    .map((key, index) => ({ key, index }))
    .filter((item) => !isMergedApplyColumn(item.key) && !isRedundantApplyColumn(item.key))
    .sort((a, b) => columnPosition(a.key, a.index) - columnPosition(b.key, b.index))
    .map((item) => COLUMN_MAP.get(item.key) || fallbackColumn(item.key))
})

/** COLUMN_ORDER 没提到的字段（两张表以后新加的）：申请表字段配中文名，其余原样 */
function fallbackColumn(key) {
  if (key.endsWith('protocolUrl')) {
    return { key, label: applyLabel(key), minWidth: 110, width: 110, align: 'center', type: 'image' }
  }
  return { key, label: applyLabel(key), minWidth: 160 }
}

/**
 * 有两对字段说的是同一件事，但字段名不一样，按同名字段配对是配不上的 ——
 * 结果就是每一对各铺成两列、内容一模一样。
 *
 *   apply.status      ↔ 主表 operateStatus   状态
 *   apply.approveTime ↔ 主表 operateTime      审批时间
 *
 * 补一层别名让它们能对上。值真不一样时（比如申请单状态和数据交付状态没同步）
 * 依旧照常显示两列，不会把信息藏掉。
 */
const APPLY_ALIAS = {
  status: 'operateStatus',
  approveTime: 'operateTime'
}

/**
 * 已经并进别的列、不再单独成列的字段。数据库编码拼进了「数据库列表」
 * （见 mergeDatabases），再单列一遍就是同一个信息说两遍。
 */
function isMergedApplyColumn(key) {
  return key === 'apply.databaseCodes' || key === 'databaseCodes'
}

/**
 * 申请表的联查是把整份申请记录带了回来，其中账号/姓名/学校/负责人/确认时间/
 * 驳回原因/协议这些字段与主表同名同值，原样铺开就会成对出现「姓名 + 申请姓名」
 * 「学校 + 申请学校」这样的重复列。
 *
 * 逐行按值判断，而不是写死一份要隐藏的字段名单：只要本页没有任何一行在申请表里
 * 给出与主表不同的值，这一列就不带新信息，不展示。申请表以后加字段不用回来改；
 * 真有对不上的（比如协议换过一份）该列会照常显示，不会把数据藏掉。
 */
function isRedundantApplyColumn(key) {
  if (!key.startsWith('apply.')) return false
  const field = key.slice(6)
  // 字段名对不上的先经别名换成主表那边的名字，再逐行比
  const mainKey = APPLY_ALIAS[field] || field
  const applyType = COLUMN_MAP.get(key)?.type
  const mainType = COLUMN_MAP.get(mainKey)?.type
  return !tableData.value.some((row) => {
    if (!row || !row.apply || typeof row.apply !== 'object') return false
    const applyValue = row.apply[field]
    // 申请表这一行没给值（或给的是空），谈不上与主表「不同」
    if (applyValue === null || applyValue === undefined || applyValue === '') return false
    // 比的是「两列渲染出来是否一样」，不是原始值：同一个时刻，主表存的是
    // 毫秒时间戳、申请表存的是 ISO 字符串，原值不等，显示出来一个字都不差
    return displayText(field, applyValue, applyType) !== displayText(mainKey, row[mainKey], mainType)
  })
}

const imageVisible = ref(false)
const imageSubject = ref({})

const detail = reactive({
  visible: false,
  title: '',
  text: '',
  isList: false,
  list: [],
  filter: ''
})

const detailItems = computed(() => {
  if (!detail.isList) return []
  const needle = detail.filter.trim().toLowerCase()
  if (!needle) return detail.list
  return detail.list.filter((item) => item.toLowerCase().includes(needle))
})

function valueOf(row, key) {
  // 数据库名称和编码合成一列显示，两个写法都指同一个东西
  if (key === 'apply.databases' || key === 'databases') return mergeDatabases(row)
  return key.startsWith('apply.') ? row?.apply?.[key.slice(6)] : row?.[key]
}

/**
 * 「数据库列表」是把名称和编码拼成一列显示的（数据要素(EDRD)）。
 *
 * 申请表把它们存成 databases / databaseCodes 两个平行数组，分开铺成两列的话，
 * 一列名称一列编码，谁也看不出哪一项配哪个编码。这里按位配对。
 * 两边长度不齐时，多出来的那截原样带上，不丢数据。
 */
function mergeDatabases(row) {
  const raw = row?.apply?.databases ?? row?.databases
  const names = toList(raw)
  const codes = toList(row?.apply?.databaseCodes ?? row?.databaseCodes)
  if (!names) return codes || raw
  const merged = names.map((name, index) => (codes?.[index] ? `${name}(${codes[index]})` : name))
  if (codes && codes.length > names.length) merged.push(...codes.slice(names.length))
  return merged
}

/** 「库数量」列的明细与条数。明细只给悬停提示和抽屉，表格单元格里只放条数 */
function listItems(value) {
  if (value === null || value === undefined || value === '') return []
  // 兜底：真出现单个字符串（既不是数组也不是顿号分隔的长串）时当成一项，别显示成 0
  return toList(value) || [String(value)]
}

function listCount(value) {
  return listItems(value).length
}

const TIME_KEYS = /(^|\.)(operateTime|createTime|confirmTime|approveTime)$/

/**
 * 一个单元格最终显示出来的文本。表格渲染和「申请列是不是重复列」的判断共用它 ——
 * 两边必须是同一套转换，否则会出现「看着一模一样、却没被判成重复」的漏网列。
 */
function displayText(key, value, type) {
  if (value === null || value === undefined || value === '') return '—'
  if (type === 'status') return statusText(value)
  if (TIME_KEYS.test(key)) return formatTime(value)
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function cellText(row, col) {
  return displayText(col.key, valueOf(row, col.key), col.type)
}

function statusText(value) {
  const text = String(value ?? '').trim()
  if (!text) return '—'
  const upper = text.toUpperCase()
  if (upper === 'APPROVED' || upper === 'PASS' || upper === 'PASSED') return '已通过'
  if (upper === 'REJECTED' || upper === 'REJECT' || upper === 'REFUSED') return '已驳回'
  if (upper === 'PENDING' || upper === 'WAITING') return '待审批'
  return text
}

function statusClass(value) {
  const text = statusText(value)
  if (text === '已通过') return 'status-ok'
  if (text === '已驳回') return 'status-no'
  return 'status-unknown'
}

// operateTime 是 13 位毫秒时间戳，按上海时区显示，避免跟着浏览器时区漂
function formatTime(value) {
  const date = toDate(value)
  if (!date) return String(value)
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  })
    .formatToParts(date)
    .reduce((acc, part) => {
      acc[part.type] = part.value
      return acc
    }, {})
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`
}

function toDate(value) {
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value
  if (typeof value === 'number') {
    const ms = value < 1e12 ? value * 1000 : value
    const d = new Date(ms)
    return Number.isNaN(d.getTime()) ? null : d
  }
  const text = String(value ?? '').trim()
  if (!text) return null
  if (/^\d{10,13}$/.test(text)) {
    const n = Number(text)
    const d = new Date(text.length <= 10 ? n * 1000 : n)
    return Number.isNaN(d.getTime()) ? null : d
  }
  const d = new Date(text.replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? null : d
}

// 数组、对象、或者用顿号/逗号/换行堆起来的长串，都按「一列条目」处理
function toList(value) {
  if (Array.isArray(value)) return value.map(stringifyItem)
  if (value && typeof value === 'object') return null

  const text = String(value ?? '').trim()
  if (!text) return null
  if (text.startsWith('[') || text.startsWith('{')) {
    try {
      const parsed = JSON.parse(text)
      if (Array.isArray(parsed)) return parsed.map(stringifyItem)
      return null
    } catch (err) {
      return null
    }
  }
  const parts = text
    .split(/[\n、,;，；]+/)
    .map((item) => item.trim())
    .filter(Boolean)
  // 短句里的一个逗号不算列表（「材料不全, 请补充」是个句子），长串才按条目拆
  if (parts.length < 2) return null
  return text.length > 40 || parts.length >= 3 ? parts : null
}

function stringifyItem(item) {
  if (item === null || item === undefined) return ''
  if (typeof item === 'object') return JSON.stringify(item)
  return String(item)
}

function isLongValue(value) {
  if (Array.isArray(value)) return true
  if (value && typeof value === 'object') return true
  const text = String(value ?? '')
  if (text.length > 60) return true
  return !!toList(value)
}

function summaryOf(value) {
  const list = toList(value)
  if (list) {
    const head = list.slice(0, 3).join('、')
    return list.length > 3 ? `${head} 等 ${list.length} 项` : head
  }
  if (value && typeof value === 'object') {
    const text = JSON.stringify(value)
    return text.length > 60 ? `${text.slice(0, 60)}…` : text
  }
  const text = String(value ?? '')
  return text.length > 60 ? `${text.slice(0, 60)}…` : text
}

function openDetail(col, value) {
  // 列名和抽屉标题可以不一样：「库数量」列的单元格里只有个数，抽屉里是完整清单，
  // 标题跟着内容叫才不别扭（列定义里用 detailTitle 指定）
  detail.title = col.detailTitle || col.label
  detail.filter = ''
  const list = toList(value)
  detail.isList = !!list
  detail.list = list || []
  detail.text = list ? '' : summaryText(value)
  detail.visible = true
}

function summaryText(value) {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  return String(value)
}

async function copyDetail() {
  const text = detail.isList ? detailItems.value.join('\n') : detail.text
  if (!text) return
  try {
    // 局域网 http 下没有 Clipboard API，退回临时输入框选中
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      fallbackCopy(text)
    }
    ElMessage.success('已复制')
  } catch (err) {
    try {
      fallbackCopy(text)
      ElMessage.success('已复制')
    } catch (e) {
      ElMessage.error('复制失败，请手动选中复制')
    }
  }
}

function fallbackCopy(text) {
  const el = document.createElement('textarea')
  el.value = text
  el.style.position = 'fixed'
  el.style.opacity = '0'
  document.body.appendChild(el)
  el.select()
  document.execCommand('copy')
  document.body.removeChild(el)
}

function openImage(row, key) {
  // 只传当前这一条：不传历史计数，协议预览不会去查注册材料的历史记录
  imageSubject.value = {
    userName: valueOf(row, 'account'),
    nickName: valueOf(row, 'nickName'),
    mail: row?.apply?.email,
    AgreeFile: valueOf(row, key)
  }
  imageVisible.value = true
}

// 快速搜索/翻页时旧响应可能后到，用序号丢弃过期结果
let requestSeq = 0

async function loadData() {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const params = {
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize
    }
    if (filters.keyword) params.keyword = filters.keyword

    const res = await getDeliveryAuditPage(params)
    if (seq !== requestSeq) return

    const body = res.data?.data || res.data || {}
    if (body.success === false) {
      error.value = body.message || '加载失败'
      ElMessage.error(error.value)
      return
    }
    tableData.value = body.records || []
    pagination.total = Number(body.total) || 0
    if (body.pageNum) pagination.pageNum = Number(body.pageNum)
    if (body.pageSize) pagination.pageSize = Number(body.pageSize)
  } catch (err) {
    if (seq !== requestSeq) return
    console.error('加载数据交付记录失败:', err)
    error.value = '加载失败'
    ElMessage.error(error.value)
  } finally {
    if (seq === requestSeq) loading.value = false
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
/* margin-top 是原来「堆在审核记录下面」时留的间隔，
   现在两个面板各占一个视图，这个间隔就悬在页面顶部了 */
.delivery-panel {
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

.file-pill-icon {
  font-size: 13px;
  line-height: 1;
}

.file-empty {
  color: #b6bfd2;
  cursor: help;
  user-select: none;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 62px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  border: 1px solid transparent;
}

.status-ok {
  color: #1f8f4f;
  background: rgba(31, 143, 79, 0.1);
  border-color: rgba(31, 143, 79, 0.18);
}

.status-no {
  color: #d34d4d;
  background: rgba(211, 77, 77, 0.1);
  border-color: rgba(211, 77, 77, 0.18);
}

.status-unknown {
  color: #8a93a8;
  background: rgba(138, 147, 168, 0.1);
  border-color: rgba(138, 147, 168, 0.18);
}

.detail-link {
  padding: 0;
  border: none;
  background: none;
  color: #3f8cff;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.detail-link:hover {
  text-decoration: underline;
}

/* 「库数量」那一列的数字胶囊。做成一枚可点的记号而不是纯文本 ——
   它既能悬停出清单、又能点开抽屉，得让人看出来是可以动的 */
.count-pill {
  min-width: 36px;
  padding: 3px 11px;
  border: 1px solid rgba(63, 140, 255, 0.32);
  border-radius: 999px;
  background: var(--brand-grad-soft);
  color: #3d5fa8;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  transition: border-color 0.18s ease, background 0.18s ease, color 0.18s ease;
}

.count-pill:hover {
  border-color: var(--brand-start);
  background: #fff;
  color: #2563eb;
}

.detail-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.detail-filter {
  flex: 1;
}

.detail-count {
  flex: none;
  color: #6b76a0;
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
}

.detail-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 70vh;
  overflow-y: auto;
}

.detail-item {
  padding: 8px 12px;
  border-radius: 10px;
  background: #f6f8fc;
  color: #33405e;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-all;
}

.detail-empty {
  color: #b6bfd2;
  text-align: center;
}

.detail-text {
  max-height: 70vh;
  padding: 14px 16px;
  border-radius: 12px;
  background: #f6f8fc;
  color: #33405e;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  overflow: auto;
}

.detail-copy {
  margin-top: 14px;
}
</style>
