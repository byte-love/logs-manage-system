import api from './http'

/**
 * 节假日公告（实用工具）。
 *
 * 改的是老版 DataHub 网站注册页顶部那句「X月X日至X月X日的用户注册，于X月X日统一审核处理」。
 * 后端写两个生产 SQL Server：cas 的 HolidayAnonTip（公告真身）+ 145 DataHub_SYS 的 ConfigFilesUpdate
 * （通知老站重载配置）。老站每 2 分钟轮询一次，所以保存后不是立刻可见，界面上要如实说。
 * 另外每次改动都会把公告整体覆盖一份到DemoText那台生产 Mongo（demotext 的 holiday_tip 单行文档）作为备份，
 * 那份失败不影响公告已经生效，页面上单独提示、单独重试。
 *
 * 超时给 30s：这几台都在公网，正常不到 1s，但偶发慢的时候别让默认 15s 把界面打断。
 */

/** 当前公告 + 状态 + 最近改动历史 */
export function getHolidayTip() {
  return api.get('/holiday-tip', { timeout: 30000 })
}

/** 实时预览：与落库同一段渲染代码 */
export function previewHolidayTip(data) {
  return api.post('/holiday-tip/preview', data, { timeout: 30000 })
}

export function saveHolidayTip(data) {
  return api.post('/holiday-tip', data, { timeout: 30000 })
}

/** 停用：把结束日期改成昨天，老站立刻不再显示（公告正文留着） */
export function disableHolidayTip(data) {
  return api.post('/holiday-tip/disable', data, { timeout: 30000 })
}

/** 只补发热更新通知，用于「保存成功但通知失败」时重试 */
export function notifyHolidayTip(data) {
  return api.post('/holiday-tip/notify', data, { timeout: 30000 })
}

/** 只补发 demotext 备份，用于「保存成功但备份失败」时重试（不通知网站） */
export function backupHolidayTip(data) {
  return api.post('/holiday-tip/backup', data, { timeout: 30000 })
}
