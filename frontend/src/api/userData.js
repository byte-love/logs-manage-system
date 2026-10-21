import api from './http'

export function getUserDataPage(params) {
  return api.get('/user-data/page', { params })
}

export function getNewLogPage(params) {
  return api.get('/user-data/new-log/page', { params })
}

export function getUserLogs(params) {
  return api.get('/user-data/new-log/by-user', { params })
}

/**
 * 按机构查操作日志（实用工具 → 学校日志导出）。
 *
 * 专用接口，不复用 /new-log/page —— 那边按机构筛只给封顶 10000 的总数，
 * 学校报表要真实条数。查 NewLog + 已同步的 En_Log；Log_Login 没有 Institution 列，
 * 按机构筛恒为空，不参与（所以 2022-11 起的登录/登出不在这个入口）。
 * 机构名在后端按「容忍首尾空格」匹配，日志里的机构名有带尾填充空格的（对齐 SQL Server
 * 定长列），等值会漏掉「学校账号」那批。
 * Institution 无索引、要全表扫 2250 万行，实测 2s 起，故放宽超时。
 */
export function getSchoolLogs(params) {
  return api.get('/user-data/school-logs', { params, timeout: 120000 })
}

export function getSearchOptions() {
  return api.get('/user-data/options')
}

export function getCreateAccountOptions() {
  return api.get('/user-data/create-options', { timeout: 30000 })
}

export function getSchoolPermissionTemplate(school) {
  return api.get('/user-data/school-permission-template', {
    params: { school },
    timeout: 30000
  })
}

export function addCollege(name) {
  return api.post('/user-data/colleges', { name }, { timeout: 30000 })
}

export function createAccount(data) {
  return api.post('/user-data', data, { timeout: 30000 })
}

// 某邮箱在 register 表里的全部申请材料（使用协议 / 注册资料）。
// 列表页每行只带最新一份，这里用于展开同邮箱的历史申请记录。
export function getRegisterFiles(params) {
  return api.get('/user-data/register-files', { params })
}

export function getRegisterPage(params) {
  return api.get('/user-data/register/page', { params })
}

// Delivery 审核申请记录
export function getDeliveryAuditPage(params) {
  return api.get('/apply/delivery/page', { params })
}

// 异常情况：被封禁账号
export function getDisabledUsers(params) {
  return api.get('/user-data/disabled', { params })
}

export function unbanUser(userName) {
  return api.post('/user-data/disabled/unban', { userName })
}

// 加绑 IP 登记列表（读 139 的 IpBindRecord，学校联查同库 User_Data）
export function getIpBindRecords(params) {
  return api.get('/ip-bind/page', { params })
}

// 异常操作排行（按总操作次数聚合统计）
// 路径仍是 login-ranking —— 统计口径从登录改成全部操作时没动接口地址。
// 未命中服务端缓存时要全量扫 NewLog 再分组，「历史全部」档位实测约 50s，
// 故超时放到 120s；原先 60s 只剩 10s 余量，库上稍有负载就会被掐断
export function getLoginRanking(params) {
  return api.get('/user-data/login-ranking', { params, timeout: 120000 })
}

export function exportAccount(params) {
  return api.get('/export/account', {
    params,
    responseType: 'blob',
    timeout: 120000
  })
}

export function exportRanking(params) {
  return api.get('/export/ranking', {
    params,
    responseType: 'blob',
    timeout: 120000
  })
}

export function exportUserLogs(params) {
  return api.get('/export/user-logs', {
    params,
    responseType: 'blob',
    timeout: 300000
  })
}

/**
 * 按机构导出日志（实用工具 → 学校日志导出）。真实进度：
 * 先 start 拿 taskId + 总数，轮询 progress，done 后 download 取文件。
 * 单文件 ≤50 万给 .xlsx；超量后端滚成多个独立 .xlsx 再打成一个 .zip。
 */
export function startSchoolExport(params) {
  return api.post('/export/school-logs/start', null, { params, timeout: 120000 })
}

export function getSchoolExportProgress(taskId) {
  return api.get('/export/school-logs/progress', { params: { taskId } })
}

export function cancelSchoolExport(taskId) {
  return api.post('/export/school-logs/cancel', null, { params: { taskId } })
}

export function downloadSchoolExport(taskId) {
  return api.get('/export/school-logs/download', {
    params: { taskId },
    responseType: 'blob',
    timeout: 600000
  })
}

// ===== 数据同步 =====
// 后端实现的不是当初约定的五个 REST 接口，而是一条 WebSocket：
//   @ServerEndpoint("/websocket/{type}")  —— {type} 决定同步哪张表
//     User_Data  账号表，全量 upsert，进度是 JSON {"done":N,"count":M}
//     Log        旧日志表，增量，源 dbo.NewLog    -> Mongo NewLog 集合
//     Log_Login  旧登录日志表，增量，源 dbo.Log_Login -> Mongo Log_Login 集合
//     En_Log     中英文版日志，增量，源 213/DataHub.En_Log -> 181/DataHub_Log.En_Log
//     Ip_Adr     学校列表，全量对齐，源 139/DataHub.Ip_Adr -> 181/DataHub_Log.Ip_Adr
//     register   注册信息，增量，源 DataHub_SYS.dbo.register -> Mongo register 集合
//                进度 JSON {"done":N,"count":M,"reused":R,"uploaded":U}，带文件计数
// 日志三个 type 都发 JSON 进度。当前后端会先统计各自待同步集合的总数；前端仍兼容旧后端可能发出的 count:0，
// 以及 logProgress 可能掺发的裸数字，避免历史服务端版本的进度帧漏到界面上。
// 连上后客户端要先 send 一条任意消息才会开始跑（onMessage 才干活）。
// 终态消息：含「所有同步任务执行完毕」= 成功；含「同步任务执行失败」= 失败。
// 建连与消息解析都在 DataSyncPanel.vue 里，这里不做封装（原生 WebSocket，
// 不走 axios / baseURL）。
//
// 状态所需的辅助数据仍走 REST：
//   账号表最近同步时间 -> getAccountLastSync()
//   现有条数          -> 账号/旧日志/注册复用分页 total；En_Log 用 getEnLogTotal() 独立显示目标集合条数

// 账号表最近一次同步完成时间（同步完成会往 User_Data_UpdateTime 插一条）。
export function getAccountLastSync() {
  return api.get('/user-data/lastUpdateTime')
}

export function getLastSyncByType(type) {
  return api.get('/user-data/lastUpdateTimeByType', { params: { type } })
}

/** 登录日志表（Log_Login）现有条数 */
export function getLogLoginTotal() {
  return api.get('/user-data/log-login-total')
}

/** 智能体目标集合（GptReplyHistory）现有条数；返回 { exists, total } */
export function getAgentTotal() {
  return api.get('/user-data/agent-total')
}

/** 中英文版日志目标集合（En_Log）现有条数；返回 { exists, total } */
export function getEnLogTotal() {
  return api.get('/user-data/en-log-total')
}

export default api
