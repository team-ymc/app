import { Navigate, Outlet } from 'react-router';
import { useAuth } from './AuthContext';

export function RequireAuth() {
  const { status } = useAuth();
  if (status === 'loading') return <div style={{ padding: 48, textAlign: 'center' }}>불러오는 중…</div>;
  if (status === 'guest') return <Navigate to="/" replace />;
  return <Outlet />;
}
