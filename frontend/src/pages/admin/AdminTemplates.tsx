import { useCallback, useEffect, useState } from 'react'
import { adminApi, type SlotTemplate } from '@/api/admin'
import { isApiError } from '@/api/http'

/**
 * 场次模板管理(07 §3.1 / 03 §4.0、§9.1)。
 *
 * ⚠️ 页面必须把两件事写在界面上,否则运营会误解:
 *   ① 改模板**只影响之后生成的场次**,今天已放出的场次不变(copy-not-reference);
 *   ② releaseOffsetMin 是**相对场次开始时刻的偏移**(负数=提前),
 *      -840 = 提前 14 小时。写成绝对时刻就会在跨天场次上算错日期。
 *
 * ⚠️ 停用用 enabled=0,不做物理删除 —— 已生成的 slot.template_id 会悬空。
 */
export default function AdminTemplates() {
  const [list, setList] = useState<SlotTemplate[]>([])
  const [msg, setMsg] = useState('')

  const load = useCallback(() => {
    adminApi
      .listTemplates()
      .then(setList)
      .catch((e) => setMsg(isApiError(e) ? e.message : '加载失败'))
  }, [])

  useEffect(load, [load])

  async function toggle(t: SlotTemplate) {
    try {
      // 带 version 走乐观锁:两个管理员同时改同一模板时,后提交的会拿到 VERSION_CONFLICT
      await adminApi.updateTemplate(t.templateId, { enabled: !t.enabled, version: t.version })
      load()
    } catch (e) {
      setMsg(isApiError(e) ? e.message : '更新失败')
      load()
    }
  }

  return (
    <main className="mx-auto max-w-4xl p-6">
      <h1 className="text-xl font-semibold">场次模板</h1>
      <p className="mt-2 text-sm text-gray-500">
        修改模板只影响此后生成的场次,已发布场次不受影响。放号偏移为负数表示提前,
        例如 -840 即场次开始前 14 小时放号。
      </p>
      {msg && (
        <p role="alert" className="mt-3 rounded bg-red-50 px-3 py-2 text-sm text-red-700">
          {msg}
        </p>
      )}
      <table className="mt-4 w-full text-sm">
        <thead>
          <tr className="border-b text-left text-gray-500">
            <th className="py-2">时段</th>
            <th className="py-2">时长(分)</th>
            <th className="py-2">容量</th>
            <th className="py-2">桶数</th>
            <th className="py-2">放号偏移(分)</th>
            <th className="py-2">状态</th>
            <th className="py-2" />
          </tr>
        </thead>
        <tbody>
          {list.map((t) => (
            <tr key={t.templateId} className="border-b">
              <td className="py-2">{String(t.slotHour).padStart(2, '0')}:00</td>
              <td className="py-2">{t.durationMin}</td>
              <td className="py-2">{t.capacity}</td>
              <td className="py-2">{t.bucketCount}</td>
              <td className="py-2">{t.releaseOffsetMin}</td>
              <td className="py-2">{t.enabled ? '启用' : '停用'}</td>
              <td className="py-2 text-right">
                <button type="button" onClick={() => toggle(t)} className="rounded border px-3 py-1">
                  {t.enabled ? '停用' : '启用'}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </main>
  )
}
