import { useEffect, useState } from 'react';
import { Toaster } from 'sonner';
import { token } from '@/lib/api';
import LoginPage from '@/features/auth/LoginPage';
import Layout from '@/components/Layout';
import DashboardPage from '@/features/dashboard/DashboardPage';
import ProjectsPage from '@/features/projects/ProjectsPage';
import ScanDetailPage from '@/features/scans/ScanDetailPage';
import ScansPage from '@/features/scans/ScansPage';
import VulnDbPage from '@/features/vulndb/VulnDbPage';
import SettingsPage from '@/features/settings/SettingsPage';

function useHashRoute() {
  const [hash, setHash] = useState(window.location.hash || '#/dashboard');
  useEffect(() => {
    const onHash = () => {
      // 处理 #/auth/callback?token=xxx
      const h = window.location.hash;
      if (h.startsWith('#/auth/callback')) {
        const params = new URLSearchParams(h.split('?')[1] ?? '');
        const t = params.get('token');
        if (t) localStorage.setItem('cg_token', t);
        window.location.hash = '#/dashboard';
        return;
      }
      setHash(h);
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  return hash;
}

export default function App() {
  const hash = useHashRoute();
  const [authed, setAuthed] = useState(() => !!token());
  useEffect(() => {
    const onAuth = () => setAuthed(!!token());
    window.addEventListener('cg_auth', onAuth);
    return () => window.removeEventListener('cg_auth', onAuth);
  }, []);

  if (!authed) {
    return (
      <>
        <LoginPage />
        <Toaster theme="dark" position="top-center" richColors />
      </>
    );
  }

  // 剥离 query（如 #/scans?project=xxx -> scans），避免污染路由匹配
  const segs = hash.replace(/^#\//, '').split('?')[0].split('/').filter(Boolean);
  const page = segs[0] || 'dashboard';

  return (
    <>
      <Layout current={page}>
        {page === 'dashboard' && <DashboardPage />}
        {page === 'projects' && segs.length === 1 && <ProjectsPage />}
        {page === 'projects' && segs[1] === 'scans' && segs[2] && <ScanDetailPage scanId={segs[2]} />}
        {page === 'scans' && !segs[1] && <ScansPage />}
        {page === 'scans' && segs[1] && <ScanDetailPage scanId={segs[1]} />}
        {page === 'vulndb' && <VulnDbPage />}
        {page === 'settings' && <SettingsPage />}
      </Layout>
      <Toaster theme="dark" position="top-center" richColors />
    </>
  );
}
