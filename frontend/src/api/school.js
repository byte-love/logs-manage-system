import api from './http'

export function getSchoolPage(params) {
  return api.get('/school/page', { params })
}

/**
 * 全部学校名称（不分页，实测 285 条）。给下拉联想用，取回后在前端本地过滤。
 * 返回的是单位名 Adress，不是 mangerName（那是客户经理姓名）。
 */
export function getAllSchoolNames() {
  return api.get('/school/all-names')
}

export function getResponsiblePage(params) {
  return api.get('/school/responsible/page', { params })
}

/**
 * 协议 PDF 的接口地址。
 *
 * 不能直接用库里的 OSS 地址：那些对象带 `Content-Disposition: attachment`，
 * 点开只会下载、iframe 里也不预览，且响应没有 CORS 头、前端 fetch 不到字节。
 * 统一走后端代理，由它换成 application/pdf + inline。
 *
 * @param kind 'agree' 使用协议 | 'student' 学生协议
 * @param mode 'inline' 预览 | 'download' 另存为
 */
export function agreementUrl({ id, kind = 'agree', mode = 'inline' }) {
  const qs = new URLSearchParams({ id: String(id), kind, mode })
  return `/api/school/agreement?${qs.toString()}`
}

/** 取协议字节。拿 blob 而不是把地址塞进 iframe，是为了能感知加载中/失败态。 */
export function getAgreementBlob({ id, kind = 'agree' }) {
  return api.get('/school/agreement', {
    params: { id, kind, mode: 'inline' },
    responseType: 'blob',
    // PDF 比分页接口大，单独放宽超时
    timeout: 30000
  })
}

export default api
