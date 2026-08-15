import { Routes, Route, Navigate } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { PublicLayout } from './layouts/PublicLayout'
import { UserLayout } from './layouts/UserLayout'
import { StaffLayout } from './layouts/StaffLayout'
import { AdminLayout } from './layouts/AdminLayout'
import { RoleGuard } from './components/common/RoleGuard'
import { Toaster } from './components/ui/sonner'

import LandingPage from './pages/LandingPage'
import Login from './pages/Login'
import Register from './pages/Register'
import ChangePassword from './pages/ChangePassword'
import Notice from './pages/Notice'
import Notifications from './pages/Notifications'
import Profile from './pages/Profile'

import SlotList from './pages/SlotList'
import ReservationResult from './pages/ReservationResult'
import MyReservations from './pages/MyReservations'
import ReservationDetail from './pages/ReservationDetail'
import ReservationQr from './pages/ReservationQr'

import StaffToday from './pages/staff/StaffToday'
import StaffVerify from './pages/staff/StaffVerify'
import StaffReservations from './pages/staff/StaffReservations'

import AdminDashboard from './pages/admin/AdminDashboard'
import AdminTemplates from './pages/admin/AdminTemplates'
import AdminSlots from './pages/admin/AdminSlots'
import AdminReleaseMonitor from './pages/admin/AdminReleaseMonitor'
import AdminReservations from './pages/admin/AdminReservations'
import AdminReconcile from './pages/admin/AdminReconcile'
import AdminStaff from './pages/admin/AdminStaff'

import { Error403, Error404, Error500 } from './pages/exceptions/ExceptionPages'

export default function App() {
  const { role } = useAuth()

  const getLanding = () => {
    if (role === 'ADMIN') return '/admin/dashboard'
    if (role === 'STAFF') return '/staff/today'
    return '/'
  }

  return (
    <>
      <Routes>
        {/* Public Landing Page */}
        <Route path="/" element={<LandingPage />} />

        {/* Public Auth & Notice Routes */}
        <Route element={<PublicLayout />}>
          <Route path="/login" element={role ? <Navigate to={getLanding()} replace /> : <Login />} />
          <Route path="/register" element={role ? <Navigate to={getLanding()} replace /> : <Register />} />
          <Route path="/change-password" element={<ChangePassword />} />
          <Route path="/notice" element={<Notice />} />
          <Route path="/403" element={<Error403 />} />
          <Route path="/404" element={<Error404 />} />
          <Route path="/500" element={<Error500 />} />
        </Route>

        {/* Visitor / USER End Layout */}
        <Route
          element={
            <RoleGuard allowedRoles={['USER']}>
              <UserLayout />
            </RoleGuard>
          }
        >
          <Route path="/slots" element={<SlotList />} />
          <Route path="/mine" element={<MyReservations />} />
          <Route path="/profile" element={<Profile />} />
          <Route path="/notifications" element={<Notifications />} />
          <Route path="/reservation/:rno/result" element={<ReservationResult />} />
          <Route path="/reservation/:rno" element={<ReservationDetail />} />
          <Route path="/reservation/:rno/qr" element={<ReservationQr />} />
        </Route>

        {/* Staff / STAFF End Layout */}
        <Route
          element={
            <RoleGuard allowedRoles={['STAFF', 'ADMIN']}>
              <StaffLayout />
            </RoleGuard>
          }
        >
          <Route path="/staff/today" element={<StaffToday />} />
          <Route path="/staff/verify" element={<StaffVerify />} />
          <Route path="/staff/reservations" element={<StaffReservations />} />
        </Route>

        {/* Admin / ADMIN End Layout */}
        <Route
          element={
            <RoleGuard allowedRoles={['ADMIN']}>
              <AdminLayout />
            </RoleGuard>
          }
        >
          <Route path="/admin/dashboard" element={<AdminDashboard />} />
          <Route path="/admin/templates" element={<AdminTemplates />} />
          <Route path="/admin/slots" element={<AdminSlots />} />
          <Route path="/admin/release-monitor" element={<AdminReleaseMonitor />} />
          <Route path="/admin/reservations" element={<AdminReservations />} />
          <Route path="/admin/reconcile" element={<AdminReconcile />} />
          <Route path="/admin/staff" element={<AdminStaff />} />
        </Route>

        <Route path="*" element={<Error404 />} />
      </Routes>

      <Toaster />
    </>
  )
}
