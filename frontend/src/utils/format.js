/**
 * 日志操作时间(O_date)显示口径：一律北京时间。
 *
 * Mongo 里 O_date 存的是时刻(epoch),后端 Jackson 序列化成 ISO 带 +00:00,
 * 例如 "2025-10-10T11:57:21.000+00:00"。它表示的是 UTC 时刻,真实本地时间是
 * 北京 +8 → 19:57。旧代码直接截原始串显示成 11:57,和直连源 SQL Server 看到的
 * 时间差 8 小时;导出侧走 Java Date 本地化一直是 19:57。这里统一转北京时间。
 *
 * 中国固定 UTC+8、无夏令时,所以把 epoch + 8h 再读 UTC 分量即是北京墙钟。
 * 只对带时区标记(Z 或 ±hh:mm)的串做转换;无时区的裸日期/时间(老值、日期区间等)
 * 原样截到分,不硬转 —— 避免把本来就是本地墙钟的值再挪 8 小时。
 */

const pad = (n) => String(n).padStart(2, '0')

/**
 * ISO 时间 → 北京时间 "YYYY-MM-DD HH:mm"。转换不了就退回「截原始串到分」。
 * @returns {string} 格式化结果(不会是 null)
 */
export function toBeijingMinute(value) {
  if (value === null || value === undefined || value === '') return '—'
  const raw = String(value)

  // 带时区标记 → 按时刻转北京时间
  if (/(Z|[+-]\d{2}:?\d{2})\s*$/.test(raw)) {
    const d = new Date(raw)
    if (!Number.isNaN(d.getTime())) {
      const b = new Date(d.getTime() + 8 * 3600 * 1000)
      return `${b.getUTCFullYear()}-${pad(b.getUTCMonth() + 1)}-${pad(b.getUTCDate())} ${pad(b.getUTCHours())}:${pad(b.getUTCMinutes())}`
    }
  }

  // 无时区：原样截到分，不挪时区
  const m = raw.match(/^(\d{4})[-/](\d{2})[-/](\d{2})[T\s](\d{2}):(\d{2})/)
  if (m) return `${m[1]}-${m[2]}-${m[3]} ${m[4]}:${m[5]}`
  const dateOnly = raw.match(/^(\d{4})[-/](\d{2})[-/](\d{2})/)
  if (dateOnly) return `${dateOnly[1]}-${dateOnly[2]}-${dateOnly[3]}`
  return raw
}
