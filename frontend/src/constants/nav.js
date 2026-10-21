/**
 * 侧边导航结构：一级菜单 + 二级菜单。
 *
 * 一级的 key 沿用原来的页签名（activeTab 那套），二级 key 是把原来「一个页签里
 * 硬堆多块面板」拆开后的新标识 —— 审核记录原来一个页签里堆着审核记录 + 数据交付记录，
 * 异常情况堆着三段，学校列表里嵌着负责人列表，现在各占一个二级项。
 *
 * 「实用工具」「数据同步」不做二级：它们各自有独立的落地页（前者是卡片墙，
 * 后者是任务卡片），再套一层菜单等于同一个东西出现两次。
 *
 * 用法：Home.vue 按 key 渲染内容区，AppAside.vue 按同一份数据渲染菜单，
 * 两边共用，避免菜单和内容对不上。
 */
export const NAV = [
  // 图标是 👤 加了变体选择符 U+FE0E（文字呈现）。单独一个 👤 在 Windows 上是彩色 emoji，
  // 渲染成深色剪影，压在深蓝侧栏上会糊成一块；加上 U+FE0E 后它变成单色字形、跟着 CSS 的
  // color 走，于是能用侧栏的浅色文字色把它点亮 —— 形状还是原来那个剪影，没换。
  // 代价：这个变体只在有单色字形的字体下生效（Windows 的 Segoe UI Symbol 有），
  // 换到没有的机器上会退回彩色剪影（也就是糊回去），届时换回普通 emoji 即可。
  { key: 'account', name: '账号数据', icon: '👤︎' },
  { key: 'log', name: '日志数据', icon: '📄' },
  {
    key: 'school',
    name: '学校列表',
    icon: '🏫',
    children: [
      { key: 'school-list', name: '学校列表' },
      { key: 'school-owner', name: '负责人列表' }
    ]
  },
  {
    key: 'audit',
    name: '审核记录',
    icon: '✅',
    children: [
      { key: 'audit-register', name: '审核记录' },
      { key: 'audit-delivery', name: '数据交付记录' }
    ]
  },
  {
    key: 'abnormal',
    name: '异常情况',
    icon: '🚫',
    children: [
      { key: 'abnormal-rank', name: '异常操作' },
      { key: 'abnormal-disabled', name: '禁用列表' },
      { key: 'abnormal-ipbind', name: '加绑 IP 登记' }
    ]
  },
  { key: 'tools', name: '实用工具', icon: '🧰' },
  { key: 'sync', name: '数据同步', icon: '🔄' }
]

/** 默认落在第一个叶子节点，与改动前的默认页一致 */
export const DEFAULT_LEAF = 'account'

/**
 * 叶子 key → { parent, name, top, isChild }。
 * 面包屑和内容区标题都从这里取，不用在组件里各写一份映射。
 *
 * isChild 用来判断面包屑要不要带父级前缀。**不能用「父级名 ≠ 页面名」代替** ——
 * 「学校列表」和「审核记录」两个分组里各有一个子项跟分组同名，
 * 按名字比会把这两个漏掉，它们的面包屑就只剩自己那一级了。
 */
export function findLeaf(leafKey) {
  for (const item of NAV) {
    if (!item.children) {
      if (item.key === leafKey) {
        // 一级单页（账号数据、实用工具…）：上面没有父级，面包屑只显示自己
        return { parent: item.name, name: item.name, top: item.key, isChild: false }
      }
      continue
    }
    const child = item.children.find((c) => c.key === leafKey)
    if (child) {
      return { parent: item.name, name: child.name, top: item.key, isChild: true }
    }
  }
  return { parent: '', name: '', top: '', isChild: false }
}

/**
 * 叶子 key 属于哪个一级项。侧栏「展开哪一组」和内容区都靠它判断，
 * 所以「进入某个二级时自动展开它的父级」不用另外记状态。
 */
export function topKeyOf(leafKey) {
  return findLeaf(leafKey).top
}

/**
 * 实用工具的卡片，同时也是它的三个子页面。
 *
 * 它们**不进侧边栏**（上面说了，卡片墙本身就是入口），但要进**顶栏面包屑** ——
 * 进到某个工具里时顶栏得显示「实用工具 / 用户日志导出」，和其他二级项一个形式，
 * 否则站在工具里看不出自己在哪一层。
 *
 * 卡片墙的三行文案和面包屑的名字共用这一份：改工具名只改这里，
 * 不会出现「卡片改了、面包屑还是旧名」。
 */
export const TOOLS = [
  { key: 'user-log-export', icon: '📤', name: '用户日志导出', desc: '精确用户名' },
  { key: 'school-log-export', icon: '🏫', name: '学校日志导出', desc: '精确学校/机构名称' },
  { key: 'create-account', icon: '📃', name: '创建正式账号', desc: '录入账号资料、配置数据库权限并同步正式账号库' },
  { key: 'holiday-tip', icon: '🗓️', name: '节假日公告', desc: '老版网站注册流程页提示' }
]

/**
 * 工具 key → 显示名。工具页停在卡片墙（还没有 activeTool）时返回空串，
 * 顶栏就退回只显示「实用工具」。
 */
export function toolName(key) {
  const item = TOOLS.find((tool) => tool.key === key)
  return item ? item.name : ''
}
