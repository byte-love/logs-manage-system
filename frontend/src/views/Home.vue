<template>
  <!-- 一整块框架：侧栏通高，顶栏只占右侧并与内容区共边，
       内容区是唯一滚动容器。三区之间无缝、无独立阴影 -->
  <div class="app">
    <AppAside
      :active="activeLeaf"
      :collapsed="asideCollapsed"
      @update:active="setActiveLeaf"
      @update:collapsed="asideCollapsed = $event"
    />

    <div class="main">
      <AppTopbar
        :parent-title="currentTitle.parent"
        :title="currentTitle.name"
        :is-child="currentTitle.isChild"
        :now-text="nowText"
        :user-name="userLabel"
        @toggle="asideCollapsed = !asideCollapsed"
        @logout="handleLogout"
      />

      <main class="page">
        <!-- 账号数据 -->
        <section v-show="activeLeaf === 'account'" class="view">
          <div class="panel-head">
            <div class="panel-title">
              <h2>账号数据</h2>
              <span class="panel-count">共 {{ accountTotal.toLocaleString() }} 条</span>
            </div>
            <div class="toolbar-row">
              <el-button type="primary" @click="openAccountSearch">高级搜索</el-button>
              <el-button @click="resetAccountSearch">重置</el-button>
              <el-button @click="openExportDialog('account')">导出</el-button>
              <el-button :loading="accountLoading" @click="fetchAccountData">刷新</el-button>
            </div>
          </div>

          <div v-if="accountActiveFilters.length" class="active-filters">
            <span class="filter-hint">当前筛选：</span>
            <el-tag
              v-for="f in accountActiveFilters"
              :key="f.key"
              closable
              type="info"
              @close="clearAccountFilter(f.key)"
            >{{ f.label }}</el-tag>
          </div>

          <el-alert
            v-if="accountError"
            :title="accountError"
            type="error"
            show-icon
            class="table-alert"
            :closable="false"
          />

          <el-table
            v-loading="accountLoading"
            :data="accountData"
            stripe
            border
            class="log-table"
            empty-text="暂无可展示数据"
          >
            <el-table-column
              v-for="column in accountColumns"
              :key="column.prop"
              :prop="column.prop"
              :label="column.label"
              :min-width="column.minWidth"
              show-overflow-tooltip
            >
              <template #default="scope">
                {{ renderCell(column.prop, scope.row[column.prop]) }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110" align="center">
              <template #default="scope">
                <span class="status-cell" :class="accountStatusOf(scope.row.AccountStatus).cls">
                  <i class="status-dot"></i>{{ accountStatusOf(scope.row.AccountStatus).label }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="申请通道" width="112" align="center">
              <template #default="scope">
                <span class="channel-cell" :class="applyChannelOf(scope.row.ApplyChannel).cls">
                  <i class="channel-dot"></i>{{ applyChannelOf(scope.row.ApplyChannel).label }}
                </span>
              </template>
            </el-table-column>
            <!-- 使用协议 / 注册资料：来自 register 表，按邮箱关联 -->
            <el-table-column
              v-for="f in FILE_COLUMNS"
              :key="f.key"
              :label="f.label"
              width="128"
              align="center"
            >
              <template #default="scope">
                <button
                  v-if="scope.row[f.field]"
                  class="file-pill"
                  :class="f.key"
                  :title="`预览${f.label}`"
                  @click="openFiles(scope.row, f.key)"
                >
                  <span class="file-pill-icon">{{ f.icon }}</span>
                  <span>预览</span>
                  <span
                    v-if="scope.row.RegisterMatchCount > 1"
                    class="file-pill-badge"
                  >{{ scope.row.RegisterMatchCount }}</span>
                </button>
                <span v-else class="file-empty">—</span>
              </template>
            </el-table-column>
            <el-table-column label="用户日志" fixed="right" width="150" align="center">
              <template #default="scope">
                <div style="display: flex;">
                <el-button link type="primary" @click="openUserLogs(scope.row)">查看日志</el-button>
                 <el-button link type="primary" v-if="scope.row.xueguo_Url!=null" @click="toXueguo(scope.row)">学国主页</el-button>
</div>
              </template>
            
            </el-table-column>
          </el-table>

          <div class="pagination-row">
            <el-pagination
              v-model:current-page="accountPageNum"
              v-model:page-size="accountPageSize"
              background
              layout="total, sizes, prev, pager, next, jumper"
              :page-sizes="[5, 10, 20, 50]"
              :total="accountTotal"
              @current-change="fetchAccountData"
              @size-change="handleAccountSizeChange"
            />
          </div>
        </section>

        <!-- 日志数据 -->
        <section v-show="activeLeaf === 'log'" class="view">
          <div class="panel-head">
            <div class="panel-title">
              <h2>日志数据</h2>
              <span class="panel-count">共 {{ logTotal.toLocaleString() }}{{ logTotalCapped ? '+' : '' }} 条</span>
            </div>
            <div class="toolbar-row">
              <el-button type="primary" @click="openLogSearch">高级搜索</el-button>
              <el-button @click="resetLogSearch">重置</el-button>
              <el-button :loading="logLoading" @click="fetchLogData">刷新</el-button>
            </div>
          </div>

          <div v-if="logActiveFilters.length" class="active-filters">
            <span class="filter-hint">当前筛选：</span>
            <el-tag
              v-for="f in logActiveFilters"
              :key="f.key"
              closable
              type="info"
              @close="clearLogFilter(f.key)"
            >{{ f.label }}</el-tag>
          </div>

          <el-alert
            v-if="logError"
            :title="logError"
            type="error"
            show-icon
            class="table-alert"
            :closable="false"
          />

          <el-table
            v-loading="logLoading"
            :data="logData"
            stripe
            border
            class="log-table"
            empty-text="暂无可展示数据"
          >
            <el-table-column
              v-for="column in logColumns"
              :key="column.prop"
              :prop="column.prop"
              :label="column.label"
              :min-width="column.minWidth"
              show-overflow-tooltip
            >
              <template #default="scope">
                {{ renderCell(column.prop, scope.row[column.prop]) }}
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-row">
            <el-pagination
              v-model:current-page="logPageNum"
              v-model:page-size="logPageSize"
              background
              layout="total, sizes, prev, pager, next, jumper"
              :page-sizes="[5, 10, 20, 50]"
              :total="logTotal"
              @current-change="fetchLogData"
              @size-change="handleLogSizeChange"
            />
          </div>

        </section>

        <!-- 学校列表：原来负责人列表嵌在学校列表组件底部，现在各占一个视图 -->
        <section v-show="activeLeaf === 'school-list'" class="view">
          <SchoolListPanel v-if="isMounted('school-list')" />
        </section>
        <section v-show="activeLeaf === 'school-owner'" class="view">
          <ResponsibleListPanel v-if="isMounted('school-owner')" />
        </section>

        <!-- 审核记录：两个面板原来堆在同一个页签里，现在分开 -->
        <section v-show="activeLeaf === 'audit-register'" class="view">
          <AuditRecordPanel v-if="isMounted('audit-register')" />
        </section>
        <section v-show="activeLeaf === 'audit-delivery'" class="view">
          <DeliveryAuditRecordPanel v-if="isMounted('audit-delivery')" />
        </section>

        <!-- 实用工具：组件内部自带卡片墙与具体工具页，这里只当外壳 -->
        <section v-show="activeLeaf === 'tools'" class="view">
          <UtilityToolsPanel v-if="isMounted('tools')" @update:tool="activeToolKey = $event" />
        </section>

        <!-- 异常情况：原来三段堆在同一个页签里，现在各占一个视图 -->
        <section v-show="activeLeaf === 'abnormal-rank'" class="view">
          <!-- 异常操作：按总操作次数排行 -->
          <div class="rank-section">
            <div class="panel-head">
              <div class="panel-title">
                <h2>异常操作</h2>
                <span class="panel-count">{{ rankLimitLabel(rankLimit) }}</span>
                <span class="panel-count rank-range-tag">{{ rankRangeLabel }}</span>
                <span v-if="rankUpdatedAt" class="rank-time">
                  统计于 {{ rankUpdatedAt }}{{ rankFromCache ? '（缓存）' : '' }}
                </span>
              </div>
              <div class="toolbar-row">
                <el-select
                  v-model="rankFilters.withinDays"
                  placeholder="时间"
                  style="width:130px"
                  @change="handleRankFilterChange"
                >
                  <el-option
                    v-for="o in RANK_RANGE_OPTIONS"
                    :key="String(o.value)"
                    :label="o.label"
                    :value="o.value"
                  />
                </el-select>
                <el-select
                  v-model="rankFilters.position"
                  clearable
                  filterable
                  placeholder="身份不限"
                  style="width:140px"
                  @change="handleRankFilterChange"
                >
                  <el-option v-for="p in options.positions" :key="p" :label="p" :value="p" />
                </el-select>
                <el-select
                  v-model="rankFilters.accountStatus"
                  placeholder="状态不限"
                  style="width:130px"
                  @change="handleRankFilterChange"
                >
                  <el-option
                    v-for="o in ACCOUNT_STATUS_OPTIONS"
                    :key="o.value"
                    :label="o.value ? o.label : '状态不限'"
                    :value="o.value"
                  />
                </el-select>
                <el-select
                  v-model="rankFilters.applyChannel"
                  placeholder="通道不限"
                  style="width:130px"
                  @change="handleRankFilterChange"
                >
                  <el-option
                    v-for="o in APPLY_CHANNEL_OPTIONS"
                    :key="o.value"
                    :label="o.value ? o.label : '通道不限'"
                    :value="o.value"
                  />
                </el-select>
                <el-select v-model="rankLimit" style="width:120px" @change="handleRankLimitChange">
                  <el-option
                    v-for="n in RANK_LIMIT_OPTIONS"
                    :key="n"
                    :label="rankLimitLabel(n)"
                    :value="n"
                  />
                </el-select>
                <el-button @click="resetRankFilters">重置</el-button>
                <el-button @click="openExportDialog('ranking')">导出</el-button>
                <el-button :loading="rankLoading" @click="refreshOperationRanking">刷新</el-button>
              </div>
            </div>

            <el-alert
              v-if="rankError"
              :title="rankError"
              type="error"
              show-icon
              class="table-alert"
              :closable="false"
            />

            <el-table
              v-loading="rankLoading"
              :element-loading-text="rankLoadingTip"
              :data="rankData"
              stripe
              border
              class="log-table"
              empty-text="暂无排行数据"
            >
              <el-table-column label="排名" width="80" align="center">
                <template #default="scope">
                  <span class="rank-badge" :class="`rank-${scope.row.rank <= 3 ? scope.row.rank : 'normal'}`">
                    {{ scope.row.rank }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column
                v-for="column in rankColumns"
                :key="column.prop"
                :prop="column.prop"
                :label="column.label"
                :min-width="column.minWidth"
                show-overflow-tooltip
              >
                <template #default="scope">
                  <strong v-if="column.prop === 'operationCount'" class="rank-count">
                    {{ Number(scope.row.operationCount || 0).toLocaleString() }}
                  </strong>
                  <span
                    v-else-if="column.prop === 'AccountStatus'"
                    class="status-cell"
                    :class="accountStatusOf(scope.row.AccountStatus).cls"
                  >
                    <i class="status-dot"></i>{{ accountStatusOf(scope.row.AccountStatus).label }}
                  </span>
                  <span
                    v-else-if="column.prop === 'ApplyChannel'"
                    class="channel-cell"
                    :class="applyChannelOf(scope.row.ApplyChannel).cls"
                  >
                    <i class="channel-dot"></i>{{ applyChannelOf(scope.row.ApplyChannel).label }}
                  </span>
                  <span v-else-if="column.prop === 'UserName'" class="rank-user-cell">
                    <span class="rank-user-name">{{ formatCellValue(scope.row.UserName) }}</span>
                    <el-button link type="primary" @click="gotoAccountDetail(scope.row)">详情</el-button>
                  </span>
                  <template v-else>{{ formatCellValue(scope.row[column.prop]) }}</template>
                </template>
              </el-table-column>
              <el-table-column label="操作日志" fixed="right" width="130" align="center">
                <template #default="scope">
                  <el-button link type="primary" @click="openRankOperationLogs(scope.row)">查看日志</el-button>
                </template>
              </el-table-column>
            </el-table>

            <!-- 行数多时才分页，前 20/50 这种小档位不显示，免得多一排没用的控件 -->
            <div v-if="rankPaged" class="pagination-row">
              <el-pagination
                v-model:current-page="rankPageNum"
                v-model:page-size="rankPageSize"
                :page-sizes="[50, 100, 200]"
                :total="rankTotal"
                layout="total, sizes, prev, pager, next, jumper"
                background
              />
            </div>
          </div>
        </section>

        <section v-show="activeLeaf === 'abnormal-disabled'" class="view">
          <div class="panel-head">
            <div class="panel-title">
              <h2>禁用列表</h2>
              <span class="panel-count">封禁账号 {{ abnormalTotal.toLocaleString() }} 个</span>
            </div>
            <div class="toolbar-row">
              <el-input
                v-model="abnormalUserName"
                placeholder="用户名"
                clearable
                class="abnormal-search"
                @keyup.enter="searchAbnormalData"
                @clear="searchAbnormalData"
              />
              <el-button type="primary" @click="searchAbnormalData">搜索</el-button>
              <el-button @click="resetAbnormalSearch">重置</el-button>
              <el-button :loading="abnormalLoading" @click="fetchAbnormalData">刷新</el-button>
            </div>
          </div>

          <el-alert
            v-if="abnormalError"
            :title="abnormalError"
            type="error"
            show-icon
            class="table-alert"
            :closable="false"
          />

          <el-table
            v-loading="abnormalLoading"
            :data="abnormalData"
            stripe
            border
            class="log-table"
            empty-text="暂无封禁账号"
          >
            <el-table-column
              v-for="column in abnormalColumns"
              :key="column.prop"
              :prop="column.prop"
              :label="column.label"
              :min-width="column.minWidth"
              show-overflow-tooltip
            >
              <template #default="scope">
                <!-- 用户名列复用排行榜的「名字靠左 + 详情靠右」布局，点详情跳账号数据并按该用户名搜出来 -->
                <span v-if="column.prop === 'UserName'" class="rank-user-cell">
                  <span class="rank-user-name">{{ renderCell(column.prop, scope.row.UserName) }}</span>
                  <el-button link type="primary" @click="gotoAccountDetail(scope.row)">详情</el-button>
                </span>
                <template v-else>{{ renderCell(column.prop, scope.row[column.prop]) }}</template>
              </template>
            </el-table-column>
            <el-table-column
              v-if="user?.canCreateAccount === true"
              label="操作"
              fixed="right"
              width="100"
              align="center"
            >
              <template #default="scope">
                <el-button
                  link
                  type="success"
                  :loading="unbanningUserName === scope.row.UserName"
                  :disabled="Boolean(unbanningUserName)"
                  @click="handleUnban(scope.row)"
                >
                  解禁
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-row">
            <el-pagination
              v-model:current-page="abnormalPageNum"
              v-model:page-size="abnormalPageSize"
              background
              layout="total, sizes, prev, pager, next, jumper"
              :page-sizes="[5, 10, 20, 50]"
              :total="abnormalTotal"
              @current-change="fetchAbnormalData"
              @size-change="handleAbnormalSizeChange"
            />
          </div>
        </section>

        <section v-show="activeLeaf === 'abnormal-ipbind'" class="view">
          <IpBindRecordPanel v-if="isMounted('abnormal-ipbind')" />
        </section>

        <!-- 数据同步：必须 v-show 常驻。组件一卸载，正在跑的 WebSocket
             和已渲染的进度就全没了（后端任务不会因此中断，但界面再也接不回来） -->
        <section v-show="activeLeaf === 'sync'" class="view">
          <DataSyncPanel v-if="isMounted('sync')" />
        </section>

      </main>
    </div>
  </div>

    <!-- 账号数据 高级搜索 -->
    <el-dialog v-model="accountSearchVisible" title="账号数据 · 高级搜索" width="640px">
      <el-form :model="accountForm" label-width="88px" class="search-form">
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="用户名"><el-input v-model="accountForm.userName" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="昵称"><el-input v-model="accountForm.nickName" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="邮箱"><el-input v-model="accountForm.mail" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="机构"><el-input v-model="accountForm.institution" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="学院"><el-input v-model="accountForm.college" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="身份">
              <el-select v-model="accountForm.position" clearable filterable placeholder="请选择" style="width:100%">
                <el-option v-for="p in options.positions" :key="p" :label="p" :value="p" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="学制"><el-input v-model="accountForm.xuezhi" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="入学年份"><el-input v-model="accountForm.ruxueYear" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="账号类型">
              <el-select v-model="accountForm.accountType" clearable placeholder="不限" style="width:100%">
                <el-option
                  v-for="opt in ACCOUNT_TYPE_OPTIONS"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="账号状态">
              <el-radio-group v-model="accountForm.accountStatus" class="status-radio-group">
                <el-radio
                  v-for="opt in ACCOUNT_STATUS_OPTIONS"
                  :key="opt.value"
                  :label="opt.value"
                  class="status-radio"
                >
                  <span class="status-cell" :class="opt.cls">
                    <i class="status-dot"></i>{{ opt.label }}
                  </span>
                </el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="申请通道">
              <el-radio-group v-model="accountForm.applyChannel" class="status-radio-group">
                <el-radio
                  v-for="opt in APPLY_CHANNEL_OPTIONS"
                  :key="opt.value"
                  :label="opt.value"
                  class="status-radio"
                >
                  <span class="channel-cell" :class="opt.cls">
                    <i class="channel-dot"></i>{{ opt.label }}
                  </span>
                </el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="accountSearchVisible = false">取消</el-button>
        <el-button @click="resetAccountSearch">重置</el-button>
        <el-button type="primary" @click="applyAccountSearch">搜索</el-button>
      </template>
    </el-dialog>

    <!-- 日志数据 高级搜索 -->
    <el-dialog v-model="logSearchVisible" title="日志数据 · 高级搜索" width="640px">
      <el-form :model="logForm" label-width="88px" class="search-form">
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="用户名"><el-input v-model="logForm.userName" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="昵称"><el-input v-model="logForm.nickName" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="机构"><el-input v-model="logForm.institution" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="来源IP"><el-input v-model="logForm.srcIP" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="城市"><el-input v-model="logForm.city" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="系统"><el-input v-model="logForm.system" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="数据库"><el-input v-model="logForm.database" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="数据表"><el-input v-model="logForm.table" clearable placeholder="精确匹配" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="操作类型">
              <el-select v-model="logForm.operationType" clearable placeholder="请选择" style="width:100%">
                <el-option v-for="o in options.operationTypes" :key="o" :label="o" :value="o" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="操作时间">
              <el-date-picker
                v-model="logForm.dateRange"
                type="daterange"
                range-separator="至"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                value-format="YYYY-MM-DD"
                style="width:100%"
              />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="logSearchVisible = false">取消</el-button>
        <el-button @click="resetLogSearch">重置</el-button>
        <el-button type="primary" @click="applyLogSearch">搜索</el-button>
      </template>
    </el-dialog>

    <!-- 某用户日志 抽屉 -->
    <!-- 宽度按入口自适应：列少的仅登录入口窄一些，避免大片空白。
         两种情况都能一屏放下，不用横向拖动。
         表格自身带 max-height，纵向滚动发生在表体内部，
         拖动时不会滑到上方标题区造成误触 -->
    <el-drawer v-model="userLogsVisible" :title="userLogsTitle" :size="userLogsDrawerSize">
      <el-table
        v-loading="userLogsLoading"
        :data="userLogsData"
        stripe
        border
        max-height="calc(100vh - 220px)"
        empty-text="该用户暂无日志数据"
      >
        <el-table-column
          v-for="column in userLogsColumns"
          :key="column.prop"
          :prop="column.prop"
          :label="column.label"
          :min-width="column.minWidth"
          show-overflow-tooltip
        >
          <template #default="scope">
            {{ renderCell(column.prop, scope.row[column.prop]) }}
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-row" style="margin-top:16px">
        <el-pagination
          v-model:current-page="userLogsPageNum"
          v-model:page-size="userLogsPageSize"
          background
          layout="total, prev, pager, next"
          :page-size="userLogsPageSize"
          :total="userLogsTotal"
          @current-change="fetchUserLogs"
        />
      </div>
    </el-drawer>

    <!-- 使用协议 / 注册资料 图片预览 -->
    <RegisterFileViewer
      v-model="fileViewerVisible"
      :subject="fileSubject"
      :initial-key="fileViewerKey"
    />

    <!-- 导出 -->
    <ExportDialog
      v-model="exportDialogVisible"
      :type="exportType"
      :filters="exportFilters"
      :current-filters="exportActiveFilters"
      :current-row-count="exportCurrentRowCount"
      :all-row-count="exportAllRowCount"
    />
</template>

<script setup>
import { buildLogColumns } from '../constants/logColumns'
import { toBeijingMinute } from '../utils/format'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearLoginUser, getLoginUser } from '../utils/auth'
import { logout } from '../api/auth'
import AppAside from '../components/layout/AppAside.vue'
import AppTopbar from '../components/layout/AppTopbar.vue'
import RegisterFileViewer from '../components/home/RegisterFileViewer.vue'
import DataSyncPanel from '../components/home/DataSyncPanel.vue'
import SchoolListPanel from '../components/home/SchoolListPanel.vue'
import ResponsibleListPanel from '../components/home/ResponsibleListPanel.vue'
import AuditRecordPanel from '../components/home/AuditRecordPanel.vue'
import DeliveryAuditRecordPanel from '../components/home/DeliveryAuditRecordPanel.vue'
import UtilityToolsPanel from '../components/home/UtilityToolsPanel.vue'
import IpBindRecordPanel from '../components/home/IpBindRecordPanel.vue'
import ExportDialog from '../components/home/ExportDialog.vue'
import { DEFAULT_LEAF, findLeaf, toolName } from '../constants/nav'
import {
  getUserDataPage, getNewLogPage, getUserLogs, getSearchOptions,
  getDisabledUsers, getLoginRanking, unbanUser
} from '../api/userData'

const router = useRouter()
const user = computed(() => getLoginUser())
const nowText = ref('')
const clockTimer = ref(null)

// ===== 外壳状态 =====
// 当前叶子节点。一级、二级收敛成同一个 key，侧栏展开哪一组由 nav.js 反查得到，
// 不用另外记「打开了哪个分组」。
const activeLeaf = ref(DEFAULT_LEAF)
const asideCollapsed = ref(false)

// 懒挂载表：叶子第一次进入才挂上去，之后一直 v-show 常驻。
// 常驻是必需的 —— DataSyncPanel 一卸载，正在跑的 WebSocket 和已渲染的进度就全丢了
// （后端任务不受影响，但界面再也接不回来）。
const mountedLeaves = reactive({})

function isMounted(leafKey) {
  return mountedLeaves[leafKey] === true
}

function setActiveLeaf(key) {
  activeLeaf.value = key
}

// 工具页当前停在哪个子工具上（由 UtilityToolsPanel 上报）。空串 = 停在卡片墙
const activeToolKey = ref('')

// 顶栏面包屑：一级 / 二级。单页项两级同名，顶栏只显示一个。
// 实用工具没有二级菜单，但点进某个工具后要显示成「实用工具 / 用户日志导出」——
// 站在工具里得看得出来自己在哪一层，和学校列表、异常情况那些二级项一个形式
const currentTitle = computed(() => {
  const leaf = findLeaf(activeLeaf.value)
  if (leaf.top === 'tools') {
    const name = toolName(activeToolKey.value)
    if (name) {
      return { parent: leaf.name, name, top: leaf.top, isChild: true }
    }
  }
  return leaf
})

const userLabel = computed(() => {
  const u = user.value
  return u?.displayName || u?.username || '访客'
})

const options = reactive({ positions: [], operationTypes: [] })

// ===== 账号数据 =====
const accountLoading = ref(false)
const accountError = ref('')
const accountData = ref([])
const accountPageNum = ref(1)
const accountPageSize = ref(10)
const accountTotal = ref(0)
const accountSearchVisible = ref(false)
const accountForm = reactive(emptyAccountForm())
const accountFilters = reactive(emptyAccountForm())

function emptyAccountForm() {
  return {
    userName: '', nickName: '', mail: '', institution: '', college: '',
    position: '', xuezhi: '', ruxueYear: '', accountType: '', accountStatus: '',
    applyChannel: ''
  }
}

// 账号类型选项，'' 即不限（等同于清空）
const ACCOUNT_TYPE_OPTIONS = [
  { value: '', label: '不限' },
  { value: 'formal', label: '正式' },
  { value: 'trial', label: '试用' }
]

// 账号状态筛选选项（互斥单选），'' 为不限
const ACCOUNT_STATUS_OPTIONS = [
  { value: '', label: '不限', cls: 'status-unknown' },
  { value: 'normal', label: '正常', cls: 'status-normal' },
  { value: 'expired', label: '到期', cls: 'status-expired' },
  { value: 'upgraded', label: '升级', cls: 'status-upgraded' },
  { value: 'disabled', label: '禁用', cls: 'status-disabled' }
]

// 账号状态展示映射（后端 AccountStatus 派生字段）
const ACCOUNT_STATUS_MAP = {
  normal: { label: '正常', cls: 'status-normal' },
  expired: { label: '到期', cls: 'status-expired' },
  upgraded: { label: '升级', cls: 'status-upgraded' },
  disabled: { label: '禁用', cls: 'status-disabled' }
}

function accountStatusOf(value) {
  return ACCOUNT_STATUS_MAP[value] || { label: '未知', cls: 'status-unknown' }
}

// AccountType 后端值 -> 中文
const ACCOUNT_TYPE_DISPLAY = {
  formal: '正式',
  trial: '试用'
}

const accountColumns = computed(() => [
  { prop: 'UserName', label: '用户名', minWidth: 160 },
  { prop: 'NickName', label: '昵称', minWidth: 140 },
  { prop: 'Mail', label: '邮箱', minWidth: 220 },
  { prop: 'Institution', label: '机构', minWidth: 220 },
  { prop: 'College', label: '学院', minWidth: 180 },
  { prop: 'Position', label: '身份', minWidth: 100 },
  { prop: 'CreateDate', label: '创建时间', minWidth: 130 },
  { prop: 'xuezhi', label: '学制', minWidth: 90 },
  { prop: 'ruxueYear', label: '入学年份', minWidth: 110 },
  { prop: 'AccountType', label: '账号类型', minWidth: 120 }
])

// 申请通道：区分正常申请开通 vs 疑似脚本批量刷取。后端 ApplyChannel 派生字段，
// piliang 人工标记优先；否则「有申请材料 / 非正式账号 / (邮箱含 edu 且教师)」任一即为申请。
// 完整口径见 UserDataService.applyChannelOf
const APPLY_CHANNEL_MAP = {
  apply: { label: '申请', cls: 'channel-apply' },
  bulk: { label: '批量', cls: 'channel-bulk' }
}

function applyChannelOf(value) {
  return APPLY_CHANNEL_MAP[value] || { label: '未知', cls: 'channel-unknown' }
}

// 高级搜索里的申请通道选项（互斥单选），'' 为不限
const APPLY_CHANNEL_OPTIONS = [
  { value: '', label: '不限', cls: 'channel-unknown' },
  { value: 'apply', label: '申请', cls: 'channel-apply' },
  { value: 'bulk', label: '批量', cls: 'channel-bulk' }
]

// 使用协议 / 注册资料两列。数据来自 register 表，后端按邮箱关联后挂到行上。
// 不在单元格里放缩略图：文件最大有 3MB+，一页 50 行会把带宽打满，
// 所以只给一个按钮，点开才真正加载。
const FILE_COLUMNS = [
  { key: 'agree', field: 'AgreeFile', label: '使用协议', icon: '📜' },
  { key: 'work', field: 'Workfile', label: '注册资料', icon: '🪪' }
]

// 无材料统一显示占位符，不解释原因（原因涉及内部数据结构，不该出现在界面上）

const fileViewerVisible = ref(false)
const fileViewerKey = ref('agree')
const fileSubject = ref({})

function openFiles(row, key) {
  fileSubject.value = {
    userName: row.UserName,
    nickName: row.NickName,
    mail: row.Mail,
    AgreeFile: row.AgreeFile,
    Workfile: row.Workfile,
    matchCount: row.RegisterMatchCount || 0,
    registerId: row.RegisterId
  }
  fileViewerKey.value = key
  fileViewerVisible.value = true
}

// ===== 导出 =====
const exportDialogVisible = ref(false)
const exportType = ref('account')
const exportFilters = ref({})
const exportActiveFilters = ref([])
const exportCurrentRowCount = ref(0)
const exportAllRowCount = ref(null)

function openExportDialog(type) {
  exportType.value = type
  if (type === 'account') {
    exportFilters.value = {
      pageNum: accountPageNum.value,
      pageSize: accountPageSize.value,
      ...JSON.parse(JSON.stringify(accountFilters))
    }
    exportActiveFilters.value = accountActiveFilters.value
    exportCurrentRowCount.value = accountData.value.length
    // 无筛选时 total 就是全库，有筛选时也是后端返回的真实 total
    exportAllRowCount.value = accountTotal.value
  } else {
    exportFilters.value = {
      rankPageNum: rankPageNum.value,
      rankPageSize: rankPageSize.value,
      rankLimit: rankLimit.value,
      rankPaged: rankPaged.value,
      rankVisibleCount: rankSliced.value.length,
      withinDays: rankFilters.withinDays,
      position: rankFilters.position,
      accountStatus: rankFilters.accountStatus,
      applyChannel: rankFilters.applyChannel
    }
    exportActiveFilters.value = rankActiveFilters.value
    exportCurrentRowCount.value = rankData.value.length
    exportAllRowCount.value = rankSliced.value.length
  }
  exportDialogVisible.value = true
}

const ACCOUNT_LABELS = {
  userName: '用户名', nickName: '昵称', mail: '邮箱', institution: '机构',
  college: '学院', position: '身份', xuezhi: '学制', ruxueYear: '入学年份',
  accountType: '账号类型', accountStatus: '账号状态', applyChannel: '申请通道'
}


const accountActiveFilters = computed(() => {
  const list = []
  Object.keys(ACCOUNT_LABELS).forEach((k) => {
    if (!accountFilters[k]) return
    // accountType / accountStatus 存的是英文枚举，标签上要显示中文
    let shown = accountFilters[k]
    if (k === 'accountType') {
      shown = ACCOUNT_TYPE_OPTIONS.find((o) => o.value === shown)?.label || shown
    } else if (k === 'accountStatus') {
      shown = ACCOUNT_STATUS_OPTIONS.find((o) => o.value === shown)?.label || shown
    } else if (k === 'applyChannel') {
      shown = APPLY_CHANNEL_OPTIONS.find((o) => o.value === shown)?.label || shown
    }
    list.push({ key: k, label: `${ACCOUNT_LABELS[k]}: ${shown}` })
  })
  return list
})

// ===== 日志数据 =====
const logLoading = ref(false)
const logError = ref('')
const logData = ref([])
const logPageNum = ref(1)
const logPageSize = ref(10)
const logTotal = ref(0)
const logTotalCapped = ref(false)
const logLoaded = ref(false)
const logSearchVisible = ref(false)
const logForm = reactive(emptyLogForm())
const logFilters = reactive(emptyLogForm())

function emptyLogForm() {
  return {
    userName: '', nickName: '', institution: '', srcIP: '', city: '',
    system: '', database: '', table: '', operationType: '', dateRange: []
  }
}

// 列定义已抽到 constants/logColumns.js，与实用工具的学校日志导出共用

// 抽屉里不展示的列：去掉邮箱/用户名/来源IP（用户名在标题里已有）
const USER_LOGS_HIDDEN = ['Mail', 'UserName', 'O_srcIP']

// 日志数据页表头放开「表字段数」。列从首行字段推出：若首行恰好是 Log_Login
// 或未带字段的行，手动补上该列，保证每页表头一致。
const logColumns = computed(() => {
  const cols = buildLogColumns(logData.value, [], ['tableFieldCount'])
  if (logData.value.length && !cols.some((c) => c.prop === 'tableFieldCount')) {
    cols.push({ prop: 'tableFieldCount', label: '表字段数', minWidth: 90 })
  }
  return cols
})

const LOG_LABELS = {
  userName: '用户名', nickName: '昵称', institution: '机构', srcIP: '来源IP',
  city: '城市', system: '系统', database: '数据库', table: '数据表', operationType: '操作类型'
}

const logActiveFilters = computed(() => {
  const list = []
  Object.keys(LOG_LABELS).forEach((k) => {
    if (logFilters[k]) list.push({ key: k, label: `${LOG_LABELS[k]}: ${logFilters[k]}` })
  })
  if (logFilters.dateRange && logFilters.dateRange.length === 2) {
    list.push({ key: 'dateRange', label: `操作时间: ${logFilters.dateRange[0]} ~ ${logFilters.dateRange[1]}` })
  }
  return list
})

// ===== 异常操作（异常情况页上方，原「异常登录 / 登录次数排行」） =====
// 统计口径已从「登录次数」改为「总操作次数」——登录操作 2022 年后无数据
const rankLoading = ref(false)
const rankError = ref('')
const rankUpdatedAt = ref('')
const rankLoaded = ref(false)
// 本次结果是否来自服务端缓存（决定「统计于」旁边要不要标注）
const rankFromCache = ref(false)
// 0 = 全部（不限名次），其余为取前 N 名
const RANK_ALL = 0
const RANK_LIMIT_OPTIONS = [20, 50, 100, 500, RANK_ALL]
// 聚合成本几乎全在 $group 上，取 500 名和取 20 名耗时相同，
// 所以默认一次拉满 500 条缓存在前端，切档位纯本地切片；
// 只有切到「全部」或改筛选条件时才重新打后端
const RANK_FETCH_LIMIT = 500
const rankLimit = ref(20)
const rankAllData = ref([])
// 当前缓存是否来自「全部」那次请求（决定切档位要不要重新请求）
const rankCacheUnlimited = ref(false)
// 当前档位对应的完整结果集（「全部」就是后端返回的全部去重用户）
const rankSliced = computed(() =>
  rankLimit.value === RANK_ALL
    ? rankAllData.value
    : rankAllData.value.slice(0, rankLimit.value)
)

// el-table 不做虚拟滚动，行数大了会把整页 DOM 撑爆（「全部」有 7 万+ 用户），
// 所以超过这个行数就分页展示 —— 数据仍是完整的，只是不一次性渲染
const RANK_PAGE_THRESHOLD = 200
const rankPageNum = ref(1)
const rankPageSize = ref(50)
const rankPaged = computed(() => rankSliced.value.length > RANK_PAGE_THRESHOLD)
const rankData = computed(() => {
  if (!rankPaged.value) return rankSliced.value
  const start = (rankPageNum.value - 1) * rankPageSize.value
  return rankSliced.value.slice(start, start + rankPageSize.value)
})
const rankTotal = computed(() => rankSliced.value.length)

// 换档位/改筛选都会让结果集变短，停在旧页码上会显示空表
watch([rankSliced, rankPageSize], () => { rankPageNum.value = 1 })

function rankLimitLabel(n) {
  return n === RANK_ALL ? '全部' : `前 ${n} 名`
}

// 时间筛选档位：0 = 历史全部（默认口径）。
// 这里用 0 而不是 null 当哨兵 —— el-option 的 value 不接受 null，
// 传 null 会触发 prop 校验警告且下拉框显示空白
const RANK_ALL_TIME = 0
const RANK_RANGE_OPTIONS = [
  { value: RANK_ALL_TIME, label: '历史全部' },
  { value: 7, label: '一周内' },
  { value: 30, label: '一个月内' },
  { value: 365, label: '一年内' },
  { value: 1095, label: '3 年内' }
]

// 筛选条件全部在后端聚合阶段生效：选了「一年内 + 老师 + 前 100」，
// 得到的是「老师里一年内操作次数前 100」，而不是历史前 100 再筛
const rankFilters = reactive({
  withinDays: RANK_ALL_TIME,
  position: '',
  accountStatus: '',
  applyChannel: ''
})

const rankRangeLabel = computed(
  () => RANK_RANGE_OPTIONS.find((o) => o.value === rankFilters.withinDays)?.label || '历史全部'
)

const rankActiveFilters = computed(() => {
  const list = []
  if (rankFilters.withinDays) {
    list.push({ key: 'withinDays', label: rankRangeLabel.value })
  }
  if (rankFilters.position) {
    list.push({ key: 'position', label: `身份: ${rankFilters.position}` })
  }
  if (rankFilters.accountStatus) {
    const lbl = ACCOUNT_STATUS_OPTIONS.find((o) => o.value === rankFilters.accountStatus)?.label
    list.push({ key: 'accountStatus', label: `状态: ${lbl || rankFilters.accountStatus}` })
  }
  if (rankFilters.applyChannel) {
    const lbl = APPLY_CHANNEL_OPTIONS.find((o) => o.value === rankFilters.applyChannel)?.label
    list.push({ key: 'applyChannel', label: `通道: ${lbl || rankFilters.applyChannel}` })
  }
  return list
})

// 时间窗口越大聚合越久（历史全量约 50s），据此给个预期，别让人以为卡死了
const rankLoadingTip = computed(() => {
  const d = rankFilters.withinDays
  if (!d) return '正在统计历史全量操作次数，约需 1 分钟…'
  if (d >= 1095) return '正在统计，约需 45 秒…'
  if (d >= 365) return '正在统计，约需 20 秒…'
  return '正在统计…'
})

const rankColumns = [
  { prop: 'UserName', label: '用户名', minWidth: 180 },
  { prop: 'NickName', label: '姓名', minWidth: 140 },
  { prop: 'Institution', label: '学校', minWidth: 220 },
  { prop: 'Position', label: '身份', minWidth: 110 },
  { prop: 'AccountStatus', label: '状态', minWidth: 110 },
  { prop: 'operationCount', label: '操作次数', minWidth: 110 }
]

// ===== 异常情况（封禁账号） =====
const abnormalLoading = ref(false)
const abnormalError = ref('')
const abnormalData = ref([])
const abnormalPageNum = ref(1)
const abnormalPageSize = ref(10)
const abnormalTotal = ref(0)
const abnormalLoaded = ref(false)
const abnormalUserName = ref('')
const unbanningUserName = ref('')

const abnormalColumns = [
  { prop: 'UserName', label: '用户名', minWidth: 180 },
  { prop: 'NickName', label: '姓名', minWidth: 140 },
  { prop: 'Institution', label: '学校', minWidth: 240 },
  { prop: 'DisabledTime', label: '禁用时间', minWidth: 140 },
  { prop: 'DisabledReason', label: '禁用说明', minWidth: 200 }
]

// ===== 用户日志抽屉 =====
const userLogsVisible = ref(false)
const userLogsLoading = ref(false)
const userLogsData = ref([])
const userLogsPageNum = ref(1)
const userLogsPageSize = ref(10)
const userLogsTotal = ref(0)
const currentLogUser = ref('')
// 空 = 全部操作。两个入口（账号数据 / 异常操作排行）都看全部操作，
// 留着这个字段是为了以后要按操作类型过滤时不用再改结构
const userLogsOperationType = ref('')
const userLogsTitle = computed(() => {
  const suffix = userLogsOperationType.value ? ` · 仅${userLogsOperationType.value}` : ''
  return `用户日志 · ${currentLogUser.value}${suffix}`
})
const userLogsColumns = computed(() => buildLogColumns(
  userLogsData.value,
  USER_LOGS_HIDDEN
))

// 列宽合计 + 抽屉内边距，留点余量；仅登录入口列更少所以更窄
const userLogsDrawerSize = computed(() => {
  const total = userLogsColumns.value.reduce((sum, c) => sum + (c.minWidth || 120), 0)
  return `${Math.max(total + 90, 620)}px`
})

// ===== 公共格式化 =====
// 日志的操作时间要看到分钟（同一天多次操作要能排序）
const DATETIME_FIELDS = ['O_date']
const DATE_FIELDS = ['CreateDate']

function formatCellValue(value) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'object') {
    try { return JSON.stringify(value) } catch (e) { return String(value) }
  }
  return String(value)
}

// 操作时间统一北京时间：ISO 带 +00:00，转成 +8 再展示（见 utils/format.js）。
// 旧实现直接截 +00:00 的墙钟、比真实时间早 8 小时，和导出、直连源库都不一致。
function formatDateTime(value) {
  if (value === null || value === undefined || value === '') return '-'
  return toBeijingMinute(value)
}

function renderCell(prop, value) {
  if (prop === 'AccountType') {
    return ACCOUNT_TYPE_DISPLAY[value] || value || ''
  }
  if (DATE_FIELDS.includes(prop)) {
    if (value === null || value === undefined || value === '') return '-'
    const raw = String(value)
    const m = raw.match(/^(\d{4})[-/](\d{2})[-/](\d{2})/)
    if (m) return `${m[1]}-${m[2]}-${m[3]}`
    return raw
  }
  return DATETIME_FIELDS.includes(prop) ? formatDateTime(value) : formatCellValue(value)
}

function updateClock() {
  nowText.value = new Date().toLocaleString('zh-CN', { hour12: false })
}

// ===== 账号数据请求 =====
function accountParams() {
  const p = { pageNum: accountPageNum.value, pageSize: accountPageSize.value }
  Object.keys(ACCOUNT_LABELS).forEach((k) => { if (accountFilters[k]) p[k] = accountFilters[k] })
  return p
}

function rankParams() {
  const p = { limit: rankLimit.value === RANK_ALL ? 0 : RANK_FETCH_LIMIT }
  if (rankFilters.withinDays) p.withinDays = rankFilters.withinDays
  if (rankFilters.position) p.position = rankFilters.position
  if (rankFilters.accountStatus) p.accountStatus = rankFilters.accountStatus
  if (rankFilters.applyChannel) p.applyChannel = rankFilters.applyChannel
  return p
}

async function fetchAccountData() {
  accountLoading.value = true
  accountError.value = ''
  try {
    const res = await getUserDataPage(accountParams())
    const d = res.data?.data || {}
    accountData.value = Array.isArray(d.records) ? d.records : []
    accountTotal.value = Number(d.total || 0)
    accountPageNum.value = Number(d.pageNum || 1)
    accountPageSize.value = Number(d.pageSize || accountPageSize.value)
  } catch (error) {
    accountData.value = []
    accountTotal.value = 0
    accountError.value = error.response?.data?.message || error.message || '数据加载失败'
    ElMessage.error(accountError.value)
  } finally {
    accountLoading.value = false
  }
}

function openAccountSearch() {
  Object.assign(accountForm, JSON.parse(JSON.stringify(accountFilters)))
  accountSearchVisible.value = true
}

function applyAccountSearch() {
  Object.assign(accountFilters, JSON.parse(JSON.stringify(accountForm)))
  accountSearchVisible.value = false
  accountPageNum.value = 1
  fetchAccountData()
}

function resetAccountSearch() {
  Object.assign(accountForm, emptyAccountForm())
  Object.assign(accountFilters, emptyAccountForm())
  accountPageNum.value = 1
  fetchAccountData()
}

function clearAccountFilter(key) {
  accountFilters[key] = ''
  accountPageNum.value = 1
  fetchAccountData()
}

function handleAccountSizeChange() {
  accountPageNum.value = 1
  fetchAccountData()
}

// ===== 日志数据请求 =====
function logParams() {
  const p = { pageNum: logPageNum.value, pageSize: logPageSize.value }
  Object.keys(LOG_LABELS).forEach((k) => { if (logFilters[k]) p[k] = logFilters[k] })
  if (logFilters.dateRange && logFilters.dateRange.length === 2) {
    p.dateStart = logFilters.dateRange[0]
    p.dateEnd = logFilters.dateRange[1]
  }
  return p
}

async function fetchLogData() {
  logLoading.value = true
  logError.value = ''
  try {
    const res = await getNewLogPage(logParams())
    const d = res.data?.data || {}
    logData.value = Array.isArray(d.records) ? d.records : []
    logTotal.value = Number(d.total || 0)
    logTotalCapped.value = Boolean(res.data?.totalCapped)
    logPageNum.value = Number(d.pageNum || 1)
    logPageSize.value = Number(d.pageSize || logPageSize.value)
    logLoaded.value = true
  } catch (error) {
    logData.value = []
    logTotal.value = 0
    logError.value = error.response?.data?.message || error.message || '数据加载失败'
    ElMessage.error(logError.value)
  } finally {
    logLoading.value = false
  }
}

function openLogSearch() {
  Object.assign(logForm, JSON.parse(JSON.stringify(logFilters)))
  logSearchVisible.value = true
}

function applyLogSearch() {
  Object.assign(logFilters, JSON.parse(JSON.stringify(logForm)))
  logSearchVisible.value = false
  logPageNum.value = 1
  fetchLogData()
}

function resetLogSearch() {
  Object.assign(logForm, emptyLogForm())
  Object.assign(logFilters, emptyLogForm())
  logPageNum.value = 1
  fetchLogData()
}

function clearLogFilter(key) {
  logFilters[key] = key === 'dateRange' ? [] : ''
  logPageNum.value = 1
  fetchLogData()
}

function handleLogSizeChange() {
  logPageNum.value = 1
  fetchLogData()
}

// ===== 异常操作请求 =====
// forceRefresh：点「刷新」时跳过服务端缓存重新聚合（会慢，约 50s）
async function fetchOperationRanking(forceRefresh = false) {
  rankLoading.value = true
  rankError.value = ''
  const unlimited = rankLimit.value === RANK_ALL
  try {
    // 非「全部」时固定拉满 500 条，档位切换在前端切片，不再重复请求
    const params = rankParams()
    if (forceRefresh) params.refresh = true
    const res = await getLoginRanking(params)
    rankAllData.value = Array.isArray(res.data?.records) ? res.data.records : []
    rankCacheUnlimited.value = unlimited
    rankFromCache.value = !!res.data?.cached
    rankUpdatedAt.value = res.data?.updatedAt
      ? new Date(res.data.updatedAt).toLocaleString('zh-CN', { hour12: false })
      : ''
    rankLoaded.value = true
  } catch (error) {
    rankAllData.value = []
    rankCacheUnlimited.value = false
    rankError.value = error.code === 'ECONNABORTED'
      ? '统计超时：历史全量聚合耗时较长，请重试或改用较短的时间范围'
      : error.response?.data?.message || error.message || '排行统计失败'
    ElMessage.error(rankError.value)
  } finally {
    rankLoading.value = false
  }
}

function refreshOperationRanking() {
  fetchOperationRanking(true)
}

// 档位切换：缓存够用就本地切片，切到「全部」才回后端补数据
function handleRankLimitChange() {
  if (!rankLoaded.value) return
  if (rankLimit.value === RANK_ALL && !rankCacheUnlimited.value) {
    fetchOperationRanking()
  }
}

// 筛选条件变化必须重新聚合 —— 否则「一年内前 100」会退化成
// 「历史前 100 里挑一年内的」，名次和口径都是错的
function handleRankFilterChange() {
  if (!rankLoaded.value) return
  fetchOperationRanking()
}

function resetRankFilters() {
  rankFilters.withinDays = RANK_ALL_TIME
  rankFilters.position = ''
  rankFilters.accountStatus = ''
  if (rankLoaded.value) fetchOperationRanking()
}

// ===== 异常情况请求 =====
async function fetchAbnormalData() {
  abnormalLoading.value = true
  abnormalError.value = ''
  try {
    const params = {
      pageNum: abnormalPageNum.value,
      pageSize: abnormalPageSize.value
    }
    if (abnormalUserName.value.trim()) params.userName = abnormalUserName.value.trim()
    const res = await getDisabledUsers(params)
    const d = res.data?.data || {}
    abnormalData.value = Array.isArray(d.records) ? d.records : []
    abnormalTotal.value = Number(d.total || 0)
    abnormalPageNum.value = Number(d.pageNum || 1)
    abnormalPageSize.value = Number(d.pageSize || abnormalPageSize.value)
    abnormalLoaded.value = true
  } catch (error) {
    abnormalData.value = []
    abnormalTotal.value = 0
    abnormalError.value = error.response?.data?.message || error.message || '数据加载失败'
    ElMessage.error(abnormalError.value)
  } finally {
    abnormalLoading.value = false
  }
}

function searchAbnormalData() {
  abnormalPageNum.value = 1
  fetchAbnormalData()
}

function resetAbnormalSearch() {
  abnormalUserName.value = ''
  abnormalPageNum.value = 1
  fetchAbnormalData()
}

function handleAbnormalSizeChange() {
  abnormalPageNum.value = 1
  fetchAbnormalData()
}

async function handleUnban(row) {
  const userName = String(row?.UserName || '').trim()
  if (!userName || unbanningUserName.value) return
  try {
    await ElMessageBox.confirm(
      `确定解禁账号“${userName}”吗？`,
      '确认解禁',
      { type: 'warning', confirmButtonText: '确认解禁', cancelButtonText: '取消' }
    )
  } catch (_) {
    return
  }

  unbanningUserName.value = userName
  try {
    const response = await unbanUser(userName)
    ElMessage.success(response.data?.message || '账号已解禁')
    if (abnormalData.value.length === 1 && abnormalPageNum.value > 1) {
      abnormalPageNum.value -= 1
    }
    await fetchAbnormalData()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '解禁失败')
  } finally {
    unbanningUserName.value = ''
  }
}

// ===== 用户日志抽屉 =====
// 账号数据里的「查看日志」：该用户全部操作
function openUserLogs(row) {
  currentLogUser.value = row.UserName || ''
  userLogsOperationType.value = ''
  userLogsPageNum.value = 1
  userLogsVisible.value = true
  fetchUserLogs()
}
// ===== 用户日志抽屉 =====
// 账号数据里的「查看日志」：该用户全部操作
function toXueguo(row) {
  const url = "https://example.com/searchwrap/scholardetail?scholarId=" + row.xueguo_Url || ''
  window.open( url ,'_blank')
}

// 排行榜里的「查看日志」：只看登录操作，字段与账号数据那个抽屉一致
// 排行入口的「查看日志」：统计口径已是全部操作，
// 日志也跟着看全部操作（只看登录会是空的 —— 登录数据 2022 年后就断了）
function openRankOperationLogs(row) {
  currentLogUser.value = row.UserName || ''
  userLogsOperationType.value = ''
  userLogsPageNum.value = 1
  userLogsVisible.value = true
  fetchUserLogs()
}

// 从排行榜跳到账号数据，并按用户名做一次精确搜索。
// 先清空其余条件，避免残留的旧筛选把目标用户过滤掉
function gotoAccountDetail(row) {
  const userName = row.UserName || ''
  if (!userName) return
  const fresh = emptyAccountForm()
  fresh.userName = userName
  Object.assign(accountForm, fresh)
  Object.assign(accountFilters, JSON.parse(JSON.stringify(fresh)))
  accountPageNum.value = 1
  activeLeaf.value = 'account'
  fetchAccountData()
}

async function fetchUserLogs() {
  userLogsLoading.value = true
  try {
    const params = {
      userName: currentLogUser.value,
      pageNum: userLogsPageNum.value,
      pageSize: userLogsPageSize.value
    }
    // 从排行榜进入时只看登录操作
    if (userLogsOperationType.value) params.operationType = userLogsOperationType.value
    const res = await getUserLogs(params)
    const d = res.data?.data || {}
    userLogsData.value = Array.isArray(d.records) ? d.records : []
    userLogsTotal.value = Number(d.total || 0)
  } catch (error) {
    userLogsData.value = []
    userLogsTotal.value = 0
    ElMessage.error(error.response?.data?.message || error.message || '日志加载失败')
  } finally {
    userLogsLoading.value = false
  }
}

async function loadOptions() {
  try {
    const res = await getSearchOptions()
    options.positions = res.data?.positions || []
    options.operationTypes = res.data?.operationTypes || []
  } catch (e) {
    // 选项加载失败不阻塞页面
  }
}

/**
 * 叶子切换：标记挂载 + 按需拉数据。
 *
 * 懒挂载放在这里而不是各个 v-if 上，是为了只有一个判断入口 —— 任何改变
 * activeLeaf 的路径（侧栏点击、排行榜「详情」跳账号数据）都会经过它。
 * immediate 让默认页在初始化时就挂上，不用另外写一遍。
 */
watch(activeLeaf, (leaf) => {
  mountedLeaves[leaf] = true
  if (leaf === 'log' && !logLoaded.value) fetchLogData()
  // 异常操作和禁用列表原来是同一个页签、进门一起拉，现在各进各的
  if (leaf === 'abnormal-rank' && !rankLoaded.value) fetchOperationRanking()
  if (leaf === 'abnormal-disabled' && !abnormalLoaded.value) fetchAbnormalData()
}, { immediate: true })

async function handleLogout() {
  try {
    await logout()
  } catch (_) {
    // 即使服务端已重启或会话已过期，也要清理浏览器本地登录状态。
  }
  clearLoginUser()
  ElMessage.success('已退出登录')
  router.replace('/login')
}

onMounted(() => {
  updateClock()
  clockTimer.value = window.setInterval(updateClock, 1000)
  loadOptions()
  fetchAccountData()
})

onBeforeUnmount(() => {
  if (clockTimer.value) window.clearInterval(clockTimer.value)
})
</script>

<style scoped>
/* ===== 外壳：一整块框架 =====
   侧栏通高（AppAside 自带 236px / 收起 68px），顶栏只占右侧并与内容区共边，
   内容区是整块框架里唯一的滚动容器。三区之间无缝、无独立阴影 */
.app {
  display: flex;
  height: 100vh;
  background: #fff;
  overflow: hidden;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
}

.page {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  background: #fff;
}
.page::-webkit-scrollbar { width: 8px; }
.page::-webkit-scrollbar-thumb { background: #cfd8e6; border-radius: 4px; }

/* 每个叶子一个视图。用 v-show 切换，不用 v-if —— 否则切回来会重新请求。
   这里的 padding 补的是原来 el-card 内边距的位置，去掉卡片壳后内容不至于贴边；
   后续做「表格通铺」时再逐页收窄 */
.view { padding: var(--content-gutter); }

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
.abnormal-search { width: 220px; }

.active-filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.filter-hint { font-size: 13px; color: #7a859f; }
.table-alert { margin-bottom: 16px; }

/* 排行榜用户名单元格：名字靠左，详情靠右。
   space-between 让详情各行落在同一竖线上；
   右侧留 1em 不完全贴边 */
.rank-user-cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding-right: 1em;
  box-sizing: border-box;
}

.rank-user-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rank-user-cell .el-button {
  flex: none;
}

/* 账号状态：绿=正常 橙=到期 红=禁用 */
.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
  background: currentColor;
}

/* ---------- 申请通道：绿=申请 红=批量 ---------- */
.channel-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 11px 3px 9px;
  border-radius: 999px;
  font-size: 12.5px;
  font-weight: 600;
  cursor: help;
}
.channel-dot {
  width: 7px;
  height: 7px;
  flex: none;
  border-radius: 50%;
  background: currentColor;
}
.channel-apply {
  background: rgba(64, 191, 129, 0.14);
  color: #2f9e68;
}
.channel-bulk {
  background: rgba(245, 108, 108, 0.14);
  color: #e05252;
}
.channel-unknown {
  background: rgba(148, 163, 184, 0.16);
  color: #8a95b0;
}

/* ---------- 使用协议 / 注册资料 单元格 ---------- */
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
/* 注册资料用青色调区分，避免两列看起来一模一样 */
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
.file-pill-icon {
  font-size: 13px;
  line-height: 1;
}
/* 同邮箱有多份申请记录时的条数徽章。
   放在胶囊内部随 flex 排布，不用绝对定位 —— 表格单元格会裁掉溢出内容，
   之前挂在右上角(-5px/-4px)的圆圈显示不全 */
.file-pill-badge {
  flex: none;
  min-width: 15px;
  height: 15px;
  padding: 0 4px;
  border-radius: 999px;
  background: linear-gradient(135deg, #ff9a3c, #ff6b6b);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 15px;
  text-align: center;
}
/* hover 时胶囊变成实心渐变，橙色徽章会打架，换成半透明白 */
.file-pill:hover .file-pill-badge {
  background: rgba(255, 255, 255, 0.28);
  color: #fff;
}
.file-empty {
  color: #b6bfd2;
  cursor: help;
  user-select: none;
}

/* 账号状态单选：独占一行，选项间留足间距 */
.status-radio-group {
  display: flex;
  align-items: center;
  gap: 28px;
  flex-wrap: wrap;
  width: 100%;
}

/* 升级状态使用蓝紫色，区别于正常绿、到期橘、禁用红 */
.status-upgraded {
  color: #5b6cf0;
}
.status-upgraded .status-dot {
  color: #5b6cf0;
}

.status-radio {
  margin-right: 0;
  height: 32px;
}

.status-radio :deep(.el-radio__label) {
  padding-left: 8px;
}

.status-normal { color: #34b56a; }
.status-expired { color: #e2941f; }
.status-disabled { color: #e04a4a; }
.status-unknown { color: #9aa4bc; }
.log-table { margin-bottom: 18px; }

/* 异常操作 */
/* 这段原来在异常情况页顶部、下面紧跟着禁用列表，所以用一条虚线隔开；
   现在自己占一个视图，分隔线没有意义了，去掉 */
.rank-section {
  margin-bottom: 0;
  padding-bottom: 0;
  border-bottom: none;
}

.rank-time { font-size: 12px; color: #9aa4bc; }

/* 当前时间口径，跟名次档位并排显示 */
.rank-range-tag { margin-left: 6px; }

.rank-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  font-size: 13px;
  font-weight: 600;
  color: #6b76a0;
  background: rgba(120, 137, 175, 0.12);
}

.rank-badge.rank-1 { color: #fff; background: linear-gradient(135deg, #f7b733, #fc8f2e); }
.rank-badge.rank-2 { color: #fff; background: linear-gradient(135deg, #b6c2d4, #8d9bb1); }
.rank-badge.rank-3 { color: #fff; background: linear-gradient(135deg, #e0a074, #c1794b); }

.rank-count { color: #2f4a86; }
.pagination-row { display: flex; justify-content: flex-end; }
.search-form { padding-top: 4px; }

@media (max-width: 900px) {
  /* 窄屏下侧栏压到 68px（只剩图标），把宽度让给表格 */
  .app :deep(.side) { width: 68px; }
  .app :deep(.side) .brand { padding: 0; justify-content: center; }
  .app :deep(.side) .brand-text,
  .app :deep(.side) .nav-item .txt,
  .app :deep(.side) .nav-item .chev,
  .app :deep(.side) .sub,
  .app :deep(.side) .side-foot { display: none; }
  .app :deep(.side) .nav-item { justify-content: center; padding: 11px 0; }
}
</style>
