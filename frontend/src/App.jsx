import { useEffect, useState } from 'react'
import { api, login, logout } from './api'
import Catalog from './Catalog'

const today = () => new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' })
const daysAgo = n => { const d = new Date(`${today()}T12:00:00`); d.setDate(d.getDate() - n); return d.toISOString().slice(0, 10) }
const dateText = value => value ? String(value).slice(0, 10) : '—'
const dateTime = value => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—'
const kindText = kind => ({ daily: '日备', monthly: '月备', yearly: '年备' }[kind] || kind)
const stateText = state => ({ ok: '符合', missing: '缺失', pending: '待完成', unknown: '待核对日期', running: '进行中', unverified: '未核验' }[state] || state)
const syncStatusText = status => ({ success: '成功', failed: '失败', started: '进行中' }[status] || status)
const badge = (text, tone = '') => <span className={`badge ${tone}`}>{text}</span>

function useData(loader, deps = []) {
  const [data, setData] = useState(null)
  const [error, setError] = useState('')
  const [version, setVersion] = useState(0)
  useEffect(() => { let active = true; setError(''); loader().then(value => { if (active) setData(value) }).catch(e => { if (active) setError(e.message) }); return () => { active = false } }, [...deps, version])
  return { data, error, reload: () => setVersion(v => v + 1) }
}
function Alert({ message }) { return message ? <div className="alert">{message}</div> : null }
function Empty({ children }) { return <div className="empty">{children}</div> }

function Login({ onSuccess }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function submit(event) {
    event.preventDefault(); setBusy(true); setError('')
    try { await login(username, password); onSuccess(await api('/api/auth/me')) }
    catch (e) { setError(e.message) }
    finally { setBusy(false) }
  }
  return <div className="login-page"><form className="login-card" onSubmit={submit}><div className="brand-mark large">▣</div><h1>数据库备份管理</h1><p>登录后查看备份与规则检查结果</p><Alert message={error} /><label>用户名<input value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" required autoFocus /></label><label>密码<input type="password" value={password} onChange={e => setPassword(e.target.value)} autoComplete="current-password" required /></label><button className="button primary full" disabled={busy}>{busy ? '登录中…' : '登录'}</button></form></div>
}

function Dashboard({ openDatabase }) {
  const { data, error, reload } = useData(() => api('/api/dashboard'))
  return <><header className="page-head"><div><div className="eyebrow">备份运行状况</div><h1>总览</h1><p>最近 7 天备份规则检查结果</p></div><button className="button" onClick={reload}>刷新</button></header><Alert message={error} />{!data ? <Empty>正在加载…</Empty> : <><div className="stats"><Stat label="监控数据库" value={data.databaseCount} /><Stat label="历史备份记录" value={data.backupCount} /><Stat label="已超时缺失" value={data.missing} tone="warn" /><Stat label="待核对日期" value={data.unknown} /><Stat label="仍在完成窗口" value={data.pending} /></div><section className="panel"><div className="panel-head"><div><h2>数据库检查</h2><p>优先显示存在缺口的数据库</p></div></div><div className="table-wrap"><table><thead><tr><th>数据库</th><th>最新记录</th><th>状态</th><th>缺失</th><th>待核对</th><th>待完成</th></tr></thead><tbody>{data.databases.map(row => <tr key={row.database.id}><td><button className="text-button strong" onClick={() => openDatabase(row.database.id)}>{row.database.name}</button></td><td>{dateTime(row.latest.eventTime)}</td><td>{row.latest.status ? badge(row.latest.status, row.latest.status.toLowerCase() === 'successed' ? 'success' : row.latest.status.toLowerCase() === 'dispatching' ? 'pending' : 'danger') : badge('无备份', 'danger')}</td><td className={row.missing ? 'danger-text' : ''}>{row.missing}</td><td>{row.unknown}</td><td>{row.pending}</td></tr>)}</tbody></table>{data.databases.length === 0 && <Empty>还没有监控的数据库</Empty>}</div></section><div className="notice">最近同步：{data.lastSync.startedAt ? `${kindText(data.lastSync.kind)} · ${dateTime(data.lastSync.startedAt)} · ${syncStatusText(data.lastSync.status)} · ${data.lastSync.fetchedCount} 条` : '尚未同步'}</div></>}</>
}
function Stat({ label, value, tone = '' }) { return <div className={`stat ${tone}`}><span>{label}</span><strong>{value ?? 0}</strong></div> }

function CheckTable({ rows }) { return <div className="table-wrap check-table"><table><thead><tr><th>计划日期</th><th>类型</th><th>结果</th><th>对应备份</th></tr></thead><tbody>{rows.map(row => <tr key={`${row.kind}-${row.due}`}><td>{row.due}</td><td>{kindText(row.kind)}</td><td>{badge(stateText(row.state), row.state === 'ok' ? 'success' : row.state === 'missing' ? 'danger' : 'pending')}</td><td>{row.record?.backupDate || '—'}</td></tr>)}</tbody></table>{rows.length === 0 && <Empty>所选范围内没有检查项</Empty>}</div> }
function Pager({ page, hasMore, setPage }) { return <div className="pager"><button className="button" disabled={page === 0} onClick={() => setPage(page - 1)}>上一页</button><span>第 {page + 1} 页</span><button className="button" disabled={!hasMore} onClick={() => setPage(page + 1)}>下一页</button></div> }

function CalendarView({ openDatabase }) {
  const current = new Date(`${today()}T12:00:00`)
  const [year, setYear] = useState(current.getFullYear())
  const [month, setMonth] = useState(current.getMonth() + 1)
  const [selected, setSelected] = useState(today())
  const [page, setPage] = useState(0)
  const calendar = useData(() => api(`/api/calendar?year=${year}&month=${month}`), [year, month])
  const backups = useData(() => api(`/api/backups?date=${selected}&page=${page}`), [selected, page])
  const firstWeekday = (new Date(year, month - 1, 1).getDay() + 6) % 7
  const count = new Date(year, month, 0).getDate()
  const cells = [...Array(firstWeekday).fill(null), ...Array.from({ length: count }, (_, i) => i + 1)]
  while (cells.length % 7) cells.push(null)
  const counts = Object.fromEntries((calendar.data?.days || []).map(row => [row.day, row.count]))
  function shift(delta) { const next = new Date(year, month - 1 + delta, 1); setYear(next.getFullYear()); setMonth(next.getMonth() + 1); setSelected(next.getFullYear() + '-' + String(next.getMonth() + 1).padStart(2, '0') + '-01'); setPage(0) }
  return <><header className="page-head"><div><div className="eyebrow">按日期查询</div><h1>备份日历</h1><p>查看哪天有备份，以及当天有哪些库</p></div></header><Alert message={calendar.error || backups.error} /><section className="panel calendar-panel"><div className="calendar-heading"><button className="circle-button" onClick={() => shift(-1)} aria-label="上个月">‹</button><h2>{year} 年 {month} 月</h2><button className="circle-button" onClick={() => shift(1)} aria-label="下个月">›</button></div><div className="calendar-grid weekday">{['周一','周二','周三','周四','周五','周六','周日'].map(day => <span key={day}>{day}</span>)}</div><div className="calendar-grid">{cells.map((day, index) => { const key = day ? `${year}-${String(month).padStart(2,'0')}-${String(day).padStart(2,'0')}` : `blank-${index}`; return day ? <button key={key} className={`calendar-day ${key === selected ? 'selected' : ''}`} onClick={() => { setSelected(key); setPage(0) }}><strong>{day}</strong><span>{counts[key] ? `${counts[key]} 条备份` : '无记录'}</span></button> : <span className="calendar-day outside" key={key} /> })}</div></section><section className="panel"><div className="panel-head"><div><h2>{selected} 的备份</h2></div></div><div className="table-wrap"><table><thead><tr><th>数据库</th><th>类型</th><th>状态</th><th>更新时间</th></tr></thead><tbody>{backups.data?.items.map(row => <tr key={row.id}><td><button className="text-button strong" onClick={() => openDatabase(row.databaseId)}>{row.databaseName}</button></td><td>{kindText(row.kind)}</td><td>{badge(row.status, row.status.toLowerCase() === 'successed' ? 'success' : 'pending')}</td><td>{dateTime(row.eventTime)}</td></tr>)}</tbody></table>{backups.data?.items.length === 0 && <Empty>这一天没有备份记录</Empty>}</div><Pager page={page} hasMore={backups.data?.hasMore} setPage={setPage} /></section></>
}

function Rules({ admin }) {
  const list = useData(() => api('/api/rules'))
  const databases = useData(() => api('/api/databases'))
  const [databaseId, setDatabaseId] = useState('')
  const [from, setFrom] = useState(daysAgo(30))
  const [to, setTo] = useState(today())
  const [selection, setSelection] = useState(null)
  const [error, setError] = useState('')
  const checks = useData(() => selection ? api(`/api/checks?databaseId=${selection.databaseId}&from=${selection.from}&to=${selection.to}`) : Promise.resolve([]), [selection])
  return <><header className="page-head"><div><div className="eyebrow">备份策略</div><h1>规则检查</h1><p>按库核对日备、月备、年备计划</p></div></header><Alert message={error || list.error || checks.error} /><div className="rule-cards">{list.data?.map(rule => <RuleCard key={rule.id} rule={rule} admin={admin} saved={list.reload} failed={setError} />)}</div><section className="panel"><div className="panel-head"><div><h2>按库检查</h2><p>宽限期内显示待完成；没有备份记录的库也会显示缺口</p></div></div><form className="filters" onSubmit={e => { e.preventDefault(); setSelection({ databaseId, from, to }) }}><label>数据库<select required value={databaseId} onChange={e => setDatabaseId(e.target.value)}><option value="">选择数据库</option>{databases.data?.map(db => <option value={db.id} key={db.id}>{db.name}</option>)}</select></label><label>开始日期<input type="date" value={from} onChange={e => setFrom(e.target.value)} required /></label><label>结束日期<input type="date" value={to} onChange={e => setTo(e.target.value)} required /></label><button className="button primary">检查</button></form>{selection ? <CheckTable rows={checks.data || []} /> : <Empty>选择数据库和日期范围后查看结果</Empty>}</section></>
}
function RuleCard({ rule, admin, saved, failed }) {
  const [enabled, setEnabled] = useState(rule.enabled)
  const [graceDays, setGraceDays] = useState(rule.graceDays)
  const [retentionDays, setRetentionDays] = useState(rule.retentionDays ?? '')
  async function save(event) { event.preventDefault(); failed(''); try { await api(`/api/admin/rules/${rule.id}`, { method: 'PUT', body: { enabled, graceDays: Number(graceDays), retentionDays: retentionDays === '' ? null : Number(retentionDays) } }); saved() } catch (e) { failed(e.message) } }
  return <article className="rule-card"><div className="rule-top"><div className="rule-icon">{kindText(rule.kind).slice(0,1)}</div>{badge(rule.enabled ? '已启用' : '未启用', rule.enabled ? 'success' : 'pending')}</div><h2>{kindText(rule.kind)}</h2><p>{rule.kind === 'daily' ? '每天备份' : rule.kind === 'monthly' ? '每月 1 日备份' : '每年 12 月 31 日备份'}</p><div className="rule-detail"><span>保留期限</span><strong>{rule.retentionDays == null ? '永久' : `${rule.retentionDays} 天`}</strong></div><div className="rule-detail"><span>允许延迟</span><strong>{rule.graceDays} 天</strong></div>{admin && <details><summary>编辑规则</summary><form className="rule-form" onSubmit={save}><label>允许延迟天数<input type="number" min="0" max="30" value={graceDays} onChange={e => setGraceDays(e.target.value)} required /></label><label>保留天数（留空为永久）<input type="number" min="1" max="36500" value={retentionDays} onChange={e => setRetentionDays(e.target.value)} /></label><label className="checkline"><input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} />启用检查</label>{rule.kind !== 'daily' && <p className="form-hint">启用后按现有备份数据检查；真实月备/年备同步仍需配置 OceanProtect。</p>}<button className="button primary">保存规则</button></form></details>}</article>
}

function SyncHistory({ admin }) {
  const runs = useData(() => api('/api/sync-runs'))
  const [error, setError] = useState('')
  const [busy, setBusy] = useState('')
  async function syncNow(source) { setBusy(source); setError(''); try { await api(`/api/admin/sync/${source}`, { method: 'POST' }); runs.reload() } catch (e) { setError(e.message); runs.reload() } finally { setBusy('') } }
  return <><header className="page-head"><div><div className="eyebrow">数据采集</div><h1>同步记录</h1><p>从备份平台保存日备、月备和年备元数据</p></div>{admin && <div className="sync-actions"><button className="button" disabled={Boolean(busy)} onClick={() => syncNow('daily')}>{busy === 'daily' ? '同步中…' : '同步日备'}</button><button className="button primary" disabled={Boolean(busy)} onClick={() => syncNow('oceanprotect')}>{busy === 'oceanprotect' ? '同步中…' : '同步月备 / 年备'}</button></div>}</header><Alert message={error || runs.error} /><section className="panel"><div className="table-wrap"><table><thead><tr><th>开始时间</th><th>类型</th><th>状态</th><th>获取</th><th>新增</th><th>备注</th></tr></thead><tbody>{runs.data?.map(run => <tr key={run.id}><td>{dateTime(run.startedAt)}</td><td>{kindText(run.kind)}</td><td>{badge(syncStatusText(run.status), run.status === 'success' ? 'success' : run.status === 'failed' ? 'danger' : 'pending')}</td><td>{run.fetchedCount}</td><td>{run.savedCount}</td><td className="error-cell">{run.error || '—'}</td></tr>)}</tbody></table>{runs.data?.length === 0 && <Empty>尚未同步</Empty>}</div></section></>
}

function Users() {
  const list = useData(() => api('/api/admin/users'))
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState('VIEWER')
  const [error, setError] = useState('')
  async function add(event) { event.preventDefault(); setError(''); try { await api('/api/admin/users', { method: 'POST', body: { username, password, role } }); setUsername(''); setPassword(''); list.reload() } catch (e) { setError(e.message) } }
  return <><header className="page-head"><div><div className="eyebrow">访问管理</div><h1>用户</h1><p>管理员可创建查看者或其他管理员账号</p></div></header><Alert message={error || list.error} /><section className="panel"><div className="table-wrap"><table><thead><tr><th>用户名</th><th>角色</th><th>状态</th><th>创建时间</th></tr></thead><tbody>{list.data?.map(user => <tr key={user.id}><td>{user.username}</td><td>{user.role === 'ADMIN' ? '管理员' : '查看者'}</td><td>{badge(user.enabled ? '启用' : '停用', user.enabled ? 'success' : '')}</td><td>{dateTime(user.created_at)}</td></tr>)}</tbody></table></div></section><section className="panel padded"><h2>添加用户</h2><form className="filters" onSubmit={add}><label>用户名<input value={username} onChange={e => setUsername(e.target.value)} minLength={3} maxLength={100} required /></label><label>密码（至少 12 位）<input type="password" value={password} onChange={e => setPassword(e.target.value)} minLength={12} required /></label><label>角色<select value={role} onChange={e => setRole(e.target.value)}><option value="VIEWER">查看者</option><option value="ADMIN">管理员</option></select></label><button className="button primary">创建</button></form></section></>
}

export default function App() {
  const [user, setUser] = useState(undefined)
  const [section, setSection] = useState('databases')
  const [selectedId, setSelectedId] = useState(null)
  useEffect(() => { api('/api/auth/me').then(setUser).catch(() => setUser(null)) }, [])
  if (user === undefined) return <div className="loading">正在加载…</div>
  if (!user) return <Login onSuccess={setUser} />
  const nav = [['databases','数据库资产'],['dashboard','总览'],['calendar','备份日历'],['rules','规则检查'],['sync','同步记录'], ...(user.admin ? [['users','用户']] : [])]
  function openDatabase(id) { setSelectedId(id); setSection('databases') }
  function navigate(target) { setSection(target); setSelectedId(null) }
  async function signOut() { try { await logout(); setUser(null) } catch (error) { alert(error.message) } }
  return <div className="shell"><aside className="sidebar"><div className="brand"><span className="brand-mark">▣</span><span>备份管理</span></div><nav aria-label="主导航">{nav.map(([id, label]) => <button key={id} className={section === id ? 'active' : ''} onClick={() => navigate(id)}>{label}</button>)}</nav><div className="sidebar-bottom"><span>{user.username}</span><button onClick={signOut}>退出</button></div></aside><main className="main">{section === 'dashboard' && <Dashboard openDatabase={openDatabase} />}{section === 'databases' && <Catalog selectedId={selectedId} setSelectedId={setSelectedId} admin={user.admin} />}{section === 'calendar' && <CalendarView openDatabase={openDatabase} />}{section === 'rules' && <Rules admin={user.admin} />}{section === 'sync' && <SyncHistory admin={user.admin} />}{section === 'users' && user.admin && <Users />}</main></div>
}
