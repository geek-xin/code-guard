const BASE = '/api';

export class ApiError extends Error {
  code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

export function token(): string | null {
  return localStorage.getItem('cg_token');
}

export function setToken(t: string | null) {
  if (t) localStorage.setItem('cg_token', t);
  else localStorage.removeItem('cg_token');
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>),
  };
  const t = token();
  if (t) headers['Authorization'] = `Bearer ${t}`;
  const resp = await fetch(`${BASE}${path}`, { ...options, headers });
  if (resp.status === 401) {
    setToken(null);
    window.dispatchEvent(new Event('cg_auth'));
    window.location.hash = '#/login';
    throw new ApiError('401', '登录已过期');
  }
  const text = await resp.text();
  let body: any = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = { success: false, message: text };
  }
  if (!resp.ok || body?.success === false) {
    throw new ApiError(body?.code ?? String(resp.status), body?.message ?? `请求失败 (${resp.status})`);
  }
  return body?.data as T;
}

export const api = {
  // auth
  login: (username: string, password: string, remember = false) =>
    request<{ token: string; user: SessionUser }>('/auth/login', { method: 'POST', body: JSON.stringify({ username, password, remember }) }),
  register: (username: string, password: string, displayName?: string, remember = false) =>
    request<{ token: string; user: SessionUser }>('/auth/register', { method: 'POST', body: JSON.stringify({ username, password, displayName, remember }) }),
  me: () => request<SessionUser>('/auth/me'),
  githubAuthorize: (remember = false) => request<{ url: string }>(`/auth/github/authorize?remember=${remember}`),
  gitlabAuthorize: (remember = false) => request<{ url: string }>(`/auth/gitlab/authorize?remember=${remember}`),
  logout: () => request<void>('/auth/logout', { method: 'POST' }),

  // dashboard
  dashboardStats: () => request<DashboardStats>('/dashboard/stats'),
  dashboardTrend: (days = 14) => request<TrendPoint[]>('/dashboard/trend?days=' + days),

  // projects
  listProjects: () => request<Project[]>('/projects'),
  getProject: (id: string) => request<Project>(`/projects/${id}`),
  createProject: (data: Partial<Project>) => request<Project>('/projects', { method: 'POST', body: JSON.stringify(data) }),
  updateProject: (id: string, data: Partial<Project>) => request<Project>(`/projects/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  deleteProject: (id: string) => request<void>(`/projects/${id}`, { method: 'DELETE' }),
  syncProject: (id: string) => request<{ status: string; message: string }>(`/projects/${id}/sync`, { method: 'POST' }),

  // scans
  startScan: (projectId: string, scope = 'ALL') =>
    request<ScanRecord>('/scans', { method: 'POST', body: JSON.stringify({ projectId, scope, trigger: 'MANUAL' }) }),
  listScans: (projectId?: string) => request<ScanRecord[]>('/scans' + (projectId ? `?projectId=${projectId}` : '')),
  getScan: (id: string) => request<ScanRecord>(`/scans/${id}`),
  stopScan: (id: string) => request<void>(`/scans/${id}/stop`, { method: 'POST' }),
  startAgentReview: (id: string) => request<AgentReviewStatus>(`/scans/${id}/agent-review`, { method: 'POST' }),
  agentReviewStatus: (id: string) => request<AgentReviewStatus>(`/scans/${id}/agent-review/status`),
  getFindings: (id: string, params: { severity?: string; engine?: string; category?: string; limit?: number } = {}) => {
    const q = new URLSearchParams();
    if (params.severity) q.set('severity', params.severity);
    if (params.engine) q.set('engine', params.engine);
    if (params.category) q.set('category', params.category);
    if (params.limit) q.set('limit', String(params.limit));
    const qs = q.toString();
    return request<ScanFinding[]>(`/scans/${id}/findings${qs ? '?' + qs : ''}`);
  },

  // vulndb
  vulndbStatus: () => request<VulnDbStatus>('/vulndb/status'),
  vulndbUpdate: () => request<{ started: boolean; message: string }>('/vulndb/update', { method: 'POST' }),

  // groups
  listGroups: () => request<ProjectGroup[]>('/groups'),
  createGroup: (name: string) => request<ProjectGroup>('/groups', { method: 'POST', body: JSON.stringify({ name }) }),
  renameGroup: (id: string, name: string) => request<ProjectGroup>(`/groups/${id}`, { method: 'PUT', body: JSON.stringify({ name }) }),
  deleteGroup: (id: string) => request<void>(`/groups/${id}`, { method: 'DELETE' }),

  // directory browser
  browseDirectory: (path = '') => request<BrowseResult>(`/projects/browse?path=${encodeURIComponent(path)}`),

  // settings
  getSettings: () => request<SettingsView>('/settings'),
  updateSettings: (data: SettingsPayload) => request<SettingsView>('/settings', { method: 'PUT', body: JSON.stringify(data) }),
  testAgent: (data?: SettingsPayload['agent']) =>
    request<{ ok: boolean; latencyMs?: number; model?: string; reply?: string; error?: string }>(
      '/settings/agent/test', { method: 'POST', body: JSON.stringify(data ?? {}) }),
};

// ============ 类型 ============

export interface SessionUser {
  id: string;
  username: string;
  displayName: string;
  avatarUrl?: string;
  email?: string;
  provider: string;
  providerTokenConfigured: boolean;
  roles: string[];
}

export interface Project {
  id: string;
  name: string;
  alias?: string;
  tags?: string[];
  group?: string;
  description?: string;
  source: 'GITHUB' | 'GITLAB' | 'LOCAL';
  repoUrl?: string;
  branch?: string;
  localPath?: string;
  tokenConfigured?: boolean;
  scheduleCron?: string;
  scheduleEnabled: boolean;
  emailNotify?: boolean;
  emails?: string[];
  autoSyncEnabled?: boolean;
  syncIntervalMinutes?: number;
  lastSyncAt?: string;
  autoScanEnabled?: boolean;
  scanIntervalMinutes?: number;
  agentReviewEnabled?: boolean;
  enabled: boolean;
  syncStatus: string;
  syncMessage?: string;
  lastScanId?: string;
  lastScanAt?: string;
  lastScanStatus?: string;
  lastScanStats?: Record<string, number | Record<string, number>>;
  createdAt: string;
  updatedAt: string;
}

export interface ScanStage {
  status: string;
  current?: number;
  total?: number;
  message?: string;
}

export interface ScanRecord {
  id: string;
  projectId: string;
  projectName: string;
  trigger: string;
  scope: string;
  status: string;
  message?: string;
  startedAt: string;
  finishedAt?: string;
  durationMs?: number;
  stages: Record<string, ScanStage>;
  summary?: Record<string, any>;
  agentReview?: string;
}

export interface ScanFinding {
  id: string;
  scanId: string;
  projectId: string;
  engine: string;
  category: string;
  severity: string;
  title: string;
  description?: string;
  file?: string;
  line?: number;
  codeSnippet?: string;
  dependencyName?: string;
  dependencyVersion?: string;
  fixedVersion?: string;
  ecosystem?: string;
  vulnId?: string;
  solution?: string;
  references?: string[];
  confidence?: number;
  cwe?: string;
  createdAt?: number;
}

export interface DashboardStats {
  totalProjects: number;
  scannedProjects: number;
  totalScans: number;
  runningScans: number;
  failedScans: number;
  findings: number;
  critical: number;
  high: number;
  medium: number;
  low: number;
  info: number;
  byEngine: Record<string, number>;
  recentScans: ScanRecord[];
}

export interface TrendPoint {
  date: string;
  total: number;
  critical: number;
  high: number;
  medium: number;
  low: number;
}

export interface ProjectGroup {
  id: string;
  name: string;
  color: string;
  createdAt: string;
}

export interface BrowseResult {
  current: string;
  parent?: string;
  name: string;
  dirs: { name: string; path: string }[];
}

export interface AgentReviewStatus {
  status: 'IDLE' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  error?: string;
  thinking?: string;
  thinkingLen?: number;
  content?: string;
  startedAt?: number;
  finishedAt?: number;
}

export interface SettingsView {
  smtp: {
    enabled: boolean;
    host: string;
    port?: number;
    username: string;
    from: string;
    ssl: boolean;
    passwordConfigured: boolean;
    ready: boolean;
    defaultRecipients: string[];
  };
  agent: {
    enabled: boolean;
    baseUrl: string;
    model: string;
    apiKeyConfigured: boolean;
    source: string;
  };
  oauth: {
    githubConfigured: boolean;
    gitlabConfigured: boolean;
    gitlabBaseUrl: string;
  };
}

export interface SettingsPayload {
  agent?: {
    enabled?: boolean;
    baseUrl?: string;
    apiKey?: string;
    model?: string;
  };
  smtp?: {
    enabled?: boolean;
    host?: string;
    port?: number;
    username?: string;
    password?: string;
    from?: string;
    ssl?: boolean;
    defaultRecipients?: string[];
  };
}

export interface VulnDbStatus {
  count: number;
  lastUpdatedAt?: string;
  version?: string;
  nextScheduledUpdate?: string;
  osvEnabled: boolean;
  updating: boolean;
}
