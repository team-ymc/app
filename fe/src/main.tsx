import React from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import './design/global.css';
import { AuthProvider } from './auth/AuthContext';
import { RequireAuth } from './auth/RequireAuth';
import LandingPage from './routes/LandingPage';
import BookshelfPage from './routes/BookshelfPage';
import StudyPage from './routes/StudyPage';

const queryClient = new QueryClient();
const router = createBrowserRouter([
  { path: '/', element: <LandingPage /> },
  {
    element: <RequireAuth />,
    children: [
      { path: '/library', element: <BookshelfPage /> },
      { path: '/papers/:paperId', element: <StudyPage /> },
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
