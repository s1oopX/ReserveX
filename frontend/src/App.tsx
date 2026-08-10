import { Navigate, Route, Routes } from 'react-router-dom'
import Login from './pages/Login'
import SlotList from './pages/SlotList'
import MyReservations from './pages/MyReservations'
import ReservationQr from './pages/ReservationQr'
import StaffVerify from './pages/staff/StaffVerify'
import AdminTemplates from './pages/admin/AdminTemplates'
import AdminReconcile from './pages/admin/AdminReconcile'

/**
 * 三前端同一份产物、按路由前缀分区(07 §一):
 *   /            访客端
 *   /staff/*     核销端(STAFF)
 *   /admin/*     管理端(ADMIN,继承 STAFF 权限)
 *
 * ⚠️ 前端的路由守卫只是**体验**,不是安全边界 —— 真正的鉴权在后端每个接口上
 *    (Sa-Token 注解 + 归属校验)。把 /admin 藏起来不等于保护它:
 *    攻击者直接打 API 就绕过了整个前端。
 *
 * v1 只落这 7 页(07 §四 的 ~20 页里的 MVP 四链路:抢号 / 放号可见 / 核销 / 对账)。
 * 其余页面按 07 §四 清单逐个补,路由表是唯一登记处。
 */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/" element={<SlotList />} />
      <Route path="/mine" element={<MyReservations />} />
      <Route path="/reservation/:rno/qr" element={<ReservationQr />} />

      <Route path="/staff/verify" element={<StaffVerify />} />

      <Route path="/admin/templates" element={<AdminTemplates />} />
      <Route path="/admin/reconcile" element={<AdminReconcile />} />

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
