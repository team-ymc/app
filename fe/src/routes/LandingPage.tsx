import { Navigate } from 'react-router';
import { useAuth } from '../auth/AuthContext';

export default function LandingPage() {
  const { status } = useAuth();
  if (status === 'authed') return <Navigate to="/library" replace />;
  return <div>Landing Page</div>;
}
