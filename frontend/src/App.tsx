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
  const authed = !!token();

  if (!authed) {
    return (
      <>
        <LoginPage />
        <Toaster theme="dark" position="top-center" richColors />
      </>
    );
  }

  const segs = hash.replace(/^#\//, '').split('/').filter(Boolean);
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
      </Layout>
      <Toaster theme="dark" position="top-center" richColors />
    </>
  );
}
