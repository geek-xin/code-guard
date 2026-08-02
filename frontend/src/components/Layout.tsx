import { useState } from 'react';
import { Shield, LayoutDashboard, FolderGit2, ScanSearch, Database, Settings as SettingsIcon, LogOut, Github, PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { api, setToken, SessionUser } from '@/lib/api';
import { cn } from '@/lib/utils';
import { useEffect } from 'react';

const NAV = [
  { key: 'dashboard', label: '总览', icon: LayoutDashboard, href: '#/dashboard' },
  { key: 'projects', label: '项目', icon: FolderGit2, href: '#/projects' },
  { key: 'scans', label: '扫描记录', icon: ScanSearch, href: '#/scans' },
  { key: 'vulndb', label: '漏洞库', icon: Database, href: '#/vulndb' },
  { key: 'settings', label: '设置', icon: SettingsIcon, href: '#/settings' },
];

const COLLAPSE_KEY = 'cg_sidebar_collapsed';

export default function Layout({ children, current }: { children: React.ReactNode; current: string }) {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(COLLAPSE_KEY) !== '0');

  useEffect(() => {
    api.me().then(setUser).catch(() => {});
  }, []);

  const toggle = () => {
    setCollapsed((prev) => {
      localStorage.setItem(COLLAPSE_KEY, prev ? '0' : '1');
      return !prev;
    });
  };

  return (
    <div className="flex min-h-screen">
      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-30 flex flex-col border-r-chunky border-ink bg-white transition-all duration-200',
          collapsed ? 'w-16' : 'w-60',
        )}
      >
        {/* Logo + 折叠按钮 */}
        <div className={cn('flex items-center gap-3 border-b-2 border-ink py-5', collapsed ? 'justify-center px-2' : 'px-5')}>
          {!collapsed && (
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md border-chunky border-ink bg-primary shadow-chunky-sm">
              <Shield className="h-6 w-6 text-white" strokeWidth={2.5} />
            </div>
          )}
          {!collapsed ? (
            <div className="min-w-0 flex-1">
              <div className="truncate text-base font-black leading-tight text-ink">CodeGuard</div>
              <div className="truncate text-[11px] font-semibold text-ink-muted">代码安全分析平台</div>
            </div>
          ) : (
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md border-chunky border-ink bg-primary shadow-chunky-sm">
              <Shield className="h-6 w-6 text-white" strokeWidth={2.5} />
            </div>
          )}
        </div>

        {/* 导航 */}
        <nav className={cn('flex-1 space-y-1.5 py-4', collapsed ? 'px-2' : 'px-3')}>
          {NAV.map((item) => {
            const Icon = item.icon;
            const active = current === item.key;
            return (
              <a
                key={item.key}
                href={item.href}
                title={collapsed ? item.label : undefined}
                className={cn(
                  'flex items-center gap-2.5 rounded-md border-chunky text-sm font-bold transition-all',
                  collapsed ? 'justify-center px-0 py-2.5' : 'px-3 py-2.5',
                  active
                    ? 'border-ink bg-paper-alt text-ink shadow-chunky-sm'
                    : 'border-transparent text-ink-muted hover:bg-paper hover:text-ink',
                )}
              >
                <Icon className="h-4 w-4 shrink-0" />
                {!collapsed && item.label}
              </a>
            );
          })}

          <div className="mt-2 border-t-2 border-ink/10 pt-2">
            <button
              type="button"
              onClick={toggle}
              title={collapsed ? '展开侧边栏' : '收起侧边栏'}
              className={cn(
                'flex w-full items-center gap-2.5 rounded-md border-chunky text-sm font-bold transition-all',
                collapsed ? 'justify-center px-0 py-2.5' : 'px-3 py-2.5',
                'border-transparent text-ink-subtle hover:bg-paper hover:text-ink',
              )}
            >
              {collapsed ? <PanelLeftOpen className="h-4 w-4 shrink-0" /> : <PanelLeftClose className="h-4 w-4 shrink-0" />}
              {!collapsed && '收起侧边栏'}
            </button>
          </div>
        </nav>

        {/* 用户区 */}
        <div className="border-t-2 border-ink p-3">
          {user && (
            <div className={cn('flex items-center rounded-md px-2 py-2', collapsed ? 'justify-center' : 'gap-2.5')}>
              {user.avatarUrl ? (
                <img src={user.avatarUrl} alt="" className="h-9 w-9 shrink-0 rounded-full border-chunky border-ink" />
              ) : (
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border-chunky border-ink bg-secondary text-sm font-black text-ink">
                  {user.displayName?.[0] ?? user.username?.[0]?.toUpperCase()}
                </div>
              )}
              {!collapsed && (
                <>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-bold text-ink">{user.displayName}</div>
                    <div className="flex items-center gap-1 text-[11px] font-semibold text-ink-muted">
                      {user.provider === 'GITHUB' && <Github className="h-3 w-3" />}
                      {user.provider}
                    </div>
                  </div>
                  <button
                    title="退出登录"
                    className="rounded p-1.5 text-ink-muted hover:bg-error hover:text-white"
                    onClick={() => {
                      api.logout().catch(() => {});
                      setToken(null);
                      window.dispatchEvent(new Event('cg_auth'));
                      window.location.hash = '#/login';
                    }}
                  >
                    <LogOut className="h-4 w-4" />
                  </button>
                </>
              )}
            </div>
          )}
        </div>
      </aside>
      <main className={cn('flex-1 px-8 py-6 transition-all duration-200', collapsed ? 'ml-16' : 'ml-60')}>
        {children}
      </main>
    </div>
  );
}
