/**
 * 日志表格的列定义。日志数据页（Home.vue）与实用工具里的学校日志导出
 * （UtilityToolsPanel.vue）共用 —— 两处都渲染 NewLog 的行，列名/列宽必须一致。
 *
 * 抽成模块而不是各抄一份：源表加字段时只改这里，否则两个页面会各显示一套列名。
 */

// 不展示的字段：SessionId 无业务含义，_id/ID 是主键；
// tableFieldCount(表字段数)默认列表不展示（主日志页列太多），
// 学校日志导出面板用 buildLogColumns 的 extraShow 显式放开这一列。
// College(学院)只出现在学校日志的行上（后端按 UserName 从 User_Data 补），
// 别的日志页面拿不到这个字段，所以不用进隐藏名单。
export const HIDDEN_LOG_FIELDS = ['O_SessionId', '_id', 'ID', 'tableFieldCount']

export const LOG_LABEL_MAP = {
  UserName: '用户名', NickName: '昵称', Institution: '机构', College: '学院', Mail: '邮箱',
  O_srcIP: '来源IP', O_city: '城市', O_system: '系统', O_database: '数据库',
  O_table: '数据表', O_Operation_type: '操作类型', O_date: '操作时间',
  // 漏了这条时表头会直接显示原始字段名 O_language（Excel 里的标题是「语言版本」）
  O_language: '语言版本',
  tableFieldCount: '表字段数'
}

// 各字段按内容实际长度给宽度，避免一律 150 导致总宽虚高、逼出横向滚动
export const LOG_COL_WIDTH = {
  UserName: 130,
  NickName: 85,
  Institution: 130,
  College: 150,
  Mail: 150,
  O_srcIP: 120,
  O_city: 85,
  O_system: 75,
  O_database: 100,
  O_table: 165,
  O_Operation_type: 85,
  O_date: 130,
  tableFieldCount: 90
}

/**
 * 按首行实际字段生成列。日志两个集合（NewLog / Log_Login）字段不完全一样，
 * 所以列是从数据推出来的，不写死。
 *
 * @param rows        当前页数据，空数组返回空列（表格显示 empty-text）
 * @param extraHidden 额外要隐藏的字段
 * @param extraShow   即使进了默认隐藏名单也要放出来的字段（如学校面板的表字段数）
 */
export function buildLogColumns(rows, extraHidden = [], extraShow = []) {
  if (!rows.length) return []
  const hidden = [...HIDDEN_LOG_FIELDS, ...extraHidden].filter((h) => !extraShow.includes(h))
  const keys = Object.keys(rows[0]).filter((k) => !hidden.includes(k))
  return keys.map((k) => ({
    prop: k,
    label: LOG_LABEL_MAP[k] || k,
    minWidth: LOG_COL_WIDTH[k] || 120
  }))
}
