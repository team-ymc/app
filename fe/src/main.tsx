import React from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import './design/global.css';
import { AuthProvider } from './auth/AuthContext';
import { RequireAuth } from './auth/RequireAuth';
import LandingPage from './routes/LandingPage';
import PlansPage from './routes/PlansPage';
import BookshelfPage from './routes/BookshelfPage';
import StudyPage from './routes/StudyPage';
import KnowledgeGraphPage from './routes/KnowledgeGraphPage';
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
