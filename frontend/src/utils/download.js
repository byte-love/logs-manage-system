/**
 * 文件保存工具。
 *
 * 关键点：showSaveFilePicker 必须在用户点击手势仍有效时调用。
 * 若先 await 网络请求再调它，手势已过期，Chrome 会抛 SecurityError。
 * 所以调用方必须「先弹选择框拿句柄，再请求数据，最后写入」。
 */

export function supportsFilePicker() {
  return typeof window !== 'undefined' && typeof window.showSaveFilePicker === 'function'
}

/**
 * 弹出保存位置选择框，返回文件句柄。
 * 必须在点击处理函数里、任何网络 await 之前同步调用。
 * 用户取消会抛 AbortError，调用方据此中止。
 */
export function pickSaveFile(filename) {
  return window.showSaveFilePicker({
    suggestedName: filename,
    types: [
      {
        description: 'Excel 工作簿',
        accept: {
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': ['.xlsx']
        }
      }
    ]
  })
}

export async function writeBlobToHandle(handle, blob) {
  const writable = await handle.createWritable()
  await writable.write(blob)
  await writable.close()
}

/** 降级：不支持 File System Access API 时走浏览器默认下载 */
export function downloadBlobFallback(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
