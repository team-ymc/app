import React from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, Outlet, RouterProvider } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import './design/global.css';
import { AuthProvider } from './auth/AuthContext';
import { RequireAuth } from './auth/RequireAuth';
import LandingPage from './routes/LandingPage';
import PlansPage from './routes/PlansPage';
import BookshelfPage from './routes/BookshelfPage';
import StudyPage from './routes/StudyPage';
import KnowledgeGraphPage from './routes/KnowledgeGraphPage';
import TrialPage from './routes/TrialPage';
import { TrialModeProvider } from './trial/TrialMode';
import { initAnalytics } from './analytics/analytics';

// 첫 페이지 조회와 UTM을 놓치지 않게 렌더 전에 초기화한다.
initAnalytics();

const queryClient = new QueryClient();
const router = createBrowserRouter([
  { path: '/', element: <LandingPage /> },
  { path: '/plans', element: <PlansPage /> },
  {
    path: '/features',
    lazy: async () => {
      const { default: Component } = await import('./routes/FeaturesPage');
      return { Component };
    },
  },
  // 체험(YMC-430): 인증 가드 밖. 뷰어는 같은 컴포넌트를 체험 모드로 쓴다.
  { path: '/try', element: <TrialPage /> },
  {
    element: <TrialModeProvider><Outlet /></TrialModeProvider>,
    children: [
      { path: '/try/papers/:paperId', element: <StudyPage /> },
      { path: '/try/papers/:paperId/graph', element: <KnowledgeGraphPage /> },
    ],
  },
  {
    element: <RequireAuth />,
    children: [
      { path: '/library', element: <BookshelfPage /> },
      { path: '/papers/:paperId', element: <StudyPage /> },
      { path: '/papers/:paperId/graph', element: <KnowledgeGraphPage /> },
    ],
  },
]);

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
