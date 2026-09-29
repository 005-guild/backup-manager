import { useEffect, useMemo, useState } from 'react'
import { api } from './api'

const today = () => new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' })
const value = item => item || '—'
const shortDate = item => item ? String(item).slice(0, 10) : '—'
const dateTime = item => item ? new Date(item).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—'
const kindLabels = { daily: '日备', monthly: '月备', yearly: '年备' }
const stateLabels = { ok: '已备份', missing: '缺失', failed: '失败', running: '进行中', pending: '待完成', unknown: '日期待核', unverified: '未核验' }
const metadataFields = [
  ['dbid', 'DBID'], ['tag', '标签'], ['lifecycleStatus', '有效标识'], ['securityTier', '数据库等级'],
  ['framework', '应用框架'], ['dbVersion', '数据库版本'], ['subsystem', '子系统'],
  ['developer', '开发人员'], ['dba', 'DBA'], ['serviceUnit', '服务单元'], ['createdOn', '建库日期'],
]

function useRemote(load, dependencies) {
  const [data, setData] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    setLoading(true)
    setError('')
    load().then(result => { if (active) setData(result) }).catch(problem => { if (active) setError(problem.message) }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [...dependencies, revision])
  return { data, error, loading, reload: () => setRevision(number => number + 1) }
}

function CatalogList({ onOpen, admin }) {
  const catalog = useRemote(() => api('/api/databases'), [])
  const [tab, setTab] = useState('all')
  const [query, setQuery] = useState('')
  const [monitorState, setMonitorState] = useState('')
  const [framework, setFramework] = useState('')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)
  const [showAdd, setShowAdd] = useState(false)
  const [name, setName] = useState('')
  const [monitorFrom, setMonitorFrom] = useState(today())
  const [actionError, setActionError] = useState('')
  const rows = catalog.data || []
  const frameworks = [...new Set(rows.map(row => row.framework).filter(Boolean))].sort()
  const tabs = [
    ['all', '全部', rows.length],
    ['with', '已有备份', rows.filter(row => row.backupCount > 0).length],
    ['empty', '无备份', rows.filter(row => row.backupCount === 0).length],
  ]
  const visible = useMemo(() => rows.filter(row => {
    if (tab === 'with' && row.backupCount === 0) return false
    if (tab === 'empty' && row.backupCount > 0) return false
    if (monitorState && row.active !== (monitorState === 'active')) return false
    if (framework && row.framework !== framework) return false
    const term = query.trim().toLowerCase()
    return !term || [row.name, row.dbid, row.tag, row.subsystem, row.developer, row.dba, row.serviceUnit].some(field => String(field || '').toLowerCase().includes(term))
  }), [rows, tab, query, monitorState, framework])
  const pageCount = Math.max(1, Math.ceil(visible.length / pageSize))
  const currentPage = Math.min(page, pageCount - 1)
  const pageRows = visible.slice(currentPage * pageSize, (currentPage + 1) * pageSize)
  useEffect(() => setPage(0), [tab, query, monitorState, framework, pageSize])

  async function addDatabase(event) {
    event.preventDefault()
    setActionError('')
    try {
      const created = await api('/api/admin/databases', { method: 'POST', body: { name, monitorFrom } })
      setName('')
      setShowAdd(false)
      catalog.reload()
      onOpen(created.id)
    } catch (problem) { setActionError(problem.message) }
  }

  return <div className="catalog-page">
    <div className="catalog-titlebar"><div><div className="catalog-crumb">备份管理 / 数据库资产</div><h1>逻辑数据库</h1><p>查看数据库资产信息与各库的备份覆盖情况</p></div><div className="catalog-title-actions"><span className="data-source-tag">{rows.some(row => row.name.startsWith('DEMO_')) ? '含演示数据' : '数据库目录'}</span><button className="button" onClick={catalog.reload}>刷新数据</button></div></div>
    <section className="catalog-surface">
      <div className="catalog-tabs" role="tablist" aria-label="数据库分类">{tabs.map(([key, label, count]) => <button role="tab" aria-selected={tab === key} className={tab === key ? 'active' : ''} key={key} onClick={() => setTab(key)}>{label}<span>（{count}）</span></button>)}</div>
      <div className="catalog-toolbar"><div className="catalog-toolbar-left"><select aria-label="监控状态" value={monitorState} onChange={event => setMonitorState(event.target.value)}><option value="">全部监控状态</option><option value="active">监控已启用</option><option value="paused">监控已暂停</option></select><select aria-label="应用框架筛选" value={framework} onChange={event => setFramework(event.target.value)}><option value="">全部应用框架</option>{frameworks.map(item => <option key={item}>{item}</option>)}</select><span className="catalog-view-label">逻辑数据库</span></div><div className="catalog-toolbar-right"><label className="catalog-search-label">关键字：<input value={query} onChange={event => setQuery(event.target.value)} placeholder="数据库名 / DBID / DBA" aria-label="搜索数据库" /></label>{admin && <button className="button primary" onClick={() => setShowAdd(open => !open)}>{showAdd ? '收起' : '+ 登记数据库'}</button>}</div></div>
      {(actionError || catalog.error) && <div className="catalog-error">{actionError || catalog.error}</div>}
      {showAdd && <form className="catalog-add-form" onSubmit={addDatabase}><label>数据库名称<input required maxLength={255} value={name} onChange={event => setName(event.target.value)} placeholder="输入逻辑数据库名" /></label><label>开始监控日期<input required type="date" value={monitorFrom} onChange={event => setMonitorFrom(event.target.value)} /></label><button className="button primary">保存并查看详情</button></form>}
      <div className="catalog-table-scroll asset-scroll"><table className="asset-table"><thead><tr><th>逻辑数据库名</th><th>数据库 DBID</th><th>标签</th><th>有效标识</th><th>数据库等级</th><th>应用框架</th><th>数据库版本</th><th>子系统</th><th>开发人员</th><th>DBA</th><th>服务单元</th><th>备份记录</th><th>最近更新</th></tr></thead><tbody>{pageRows.map(row => <tr key={row.id}><td><button className="asset-name" onClick={() => onOpen(row.id)}>{row.name}</button></td><td className="mono" title={row.dbid || ''}>{value(row.dbid)}</td><td>{row.tag ? <span className="asset-tag">{row.tag}</span> : '—'}</td><td><span className={`asset-online ${row.active ? '' : 'offline'}`}>{row.lifecycleStatus && <i />}{value(row.lifecycleStatus)}</span></td><td>{value(row.securityTier)}</td><td>{value(row.framework)}</td><td>{value(row.dbVersion)}</td><td>{value(row.subsystem)}</td><td title={row.developer || ''}>{value(row.developer)}</td><td title={row.dba || ''}>{value(row.dba)}</td><td title={row.serviceUnit || ''}>{value(row.serviceUnit)}</td><td><span className={`record-count ${row.backupCount === 0 ? 'none' : ''}`}>{row.backupCount}</span></td><td>{dateTime(row.latestEvent)}</td></tr>)}</tbody></table>{catalog.loading && <div className="catalog-loading">正在加载数据库资产…</div>}{!catalog.loading && visible.length === 0 && <div className="catalog-loading">没有符合条件的数据库</div>}</div>
      <div className="catalog-foot"><div>共 {visible.length} 个数据库 · 点击蓝色数据库名查看备份详情</div><div className="catalog-page-controls"><select aria-label="每页数据库数" value={pageSize} onChange={event => setPageSize(Number(event.target.value))}><option value="20">20 条 / 页</option><option value="50">50 条 / 页</option><option value="100">100 条 / 页</option></select><button className="button" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>上一页</button><span>{currentPage + 1} / {pageCount}</span><button className="button" disabled={currentPage === pageCount - 1} onClick={() => setPage(currentPage + 1)}>下一页</button></div></div>
    </section>
  </div>
}

function CoverageBand({ group, demo }) {
  const title = kindLabels[group.kind]
  const frequency = group.kind === 'daily' ? '每天 1 份' : group.kind === 'monthly' ? '每月 1 日' : '每年 12 月 31 日'
  const retention = group.retentionDays == null ? '永久保留' : group.retentionDays === 365 ? '保留 1 年' : `保留 ${group.retentionDays} 天`
  const policy = `${frequency} · ${retention} · 允许延迟 ${group.graceDays ?? 2} 天`
  const [selected, setSelected] = useState(null)
  return <section className="coverage-band"><div className="coverage-band-head"><div><h3>{title}<span>{policy}</span></h3><p>{group.sourceConnected ? `应有 ${group.expectedCount} 份 · 已备份 ${group.presentCount} 份 · 缺失或失败 ${group.missingCount} 份${group.pendingCount ? ` · 待完成 ${group.pendingCount} 份` : ''}${group.unknownCount ? ` · 待核验 ${group.unknownCount} 份` : ''}` : `应有 ${group.expectedCount} 份 · 尚未启用核验`}</p></div><span className="coverage-count">{group.presentCount} / {group.expectedCount}</span></div>{group.slots.length ? <div className="coverage-slots">{group.slots.map((slot, index) => <button type="button" className={`coverage-slot state-${slot.state} ${selected?.due === slot.due ? 'selected' : ''}`} key={`${group.kind}-${slot.due}`} title={`${slot.due} · ${slot.reason}`} aria-label={`${title}${index + 1} ${slot.due} ${stateLabels[slot.state]}`} aria-pressed={selected?.due === slot.due} onClick={() => setSelected(previous => previous?.due === slot.due ? null : slot)}><span className="coverage-slot-name">{title}{index + 1}</span><strong>{stateLabels[slot.state]}</strong><time>{shortDate(slot.due)}</time></button>)}</div> : <div className="coverage-no-slots">监控开始后尚无到期的{title}计划</div>}{selected && <div className="coverage-selection"><strong>{selected.due} · {selected.reason}</strong>{selected.record && <span>备份日期：{shortDate(selected.record.backupDate)} · 更新时间：{dateTime(selected.record.eventTime)} · 状态：{selected.record.status}</span>}<button aria-label="收起备份说明" onClick={() => setSelected(null)}>×</button></div>}{!group.sourceConnected && <p className="coverage-band-note">该规则尚未启用，灰色格子表示未核验。</p>}{demo && group.kind !== 'daily' && <p className="coverage-band-note">演示库使用模拟备份记录展示覆盖情况。</p>}</section>
}

function MetadataEditor({ db, onSaved, onCancel }) {
  const [form, setForm] = useState(Object.fromEntries(metadataFields.map(([key]) => [key, db[key] || ''])))
  const [error, setError] = useState('')
  async function save(event) {
    event.preventDefault()
    setError('')
    try { await api(`/api/admin/databases/${db.id}/metadata`, { method: 'PUT', body: { ...form, createdOn: form.createdOn || null } }); onSaved() }
    catch (problem) { setError(problem.message) }
  }
  return <form className="metadata-editor" onSubmit={save}>{error && <div className="catalog-error">{error}</div>}<div className="metadata-fields">{metadataFields.map(([key, label]) => <label key={key}>{label}<input type={key === 'createdOn' ? 'date' : 'text'} value={form[key]} onChange={event => setForm(previous => ({ ...previous, [key]: event.target.value }))} /></label>)}</div><div className="metadata-actions"><button type="button" className="button" onClick={onCancel}>取消</button><button className="button primary">保存资产信息</button></div></form>
}

function CatalogDetail({ id, onBack, admin }) {
  const db = useRemote(() => api(`/api/databases/${id}`), [id])
  const coverage = useRemote(() => api(`/api/databases/${id}/coverage`), [id])
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState({ from: '', to: '', kind: '', status: '' })
  const [filters, setFilters] = useState({ from: '', to: '', kind: '', status: '' })
  const [page, setPage] = useState(0)
  const records = useRemote(() => {
    const params = new URLSearchParams({ databaseId: String(id), page: String(page), size: '50' })
    for (const [key, item] of Object.entries(filters)) if (item) params.set(key, item)
    return api(`/api/backups?${params}`)
  }, [id, filters, page])
  const database = db.data
  const demo = database?.name?.startsWith('DEMO_')
  return <div className="catalog-page"><div className="catalog-detail-title"><button className="catalog-back" onClick={onBack}>← 返回数据库列表</button><div className="catalog-crumb">备份管理 / 逻辑数据库 / 备份详情</div><div className="detail-title-line"><div><h1>{database?.name || '数据库详情'}</h1><p>按规则查看应有备份与实际备份的对应关系</p></div>{demo && <span className="data-source-tag">演示数据</span>}</div></div>
    {(db.error || coverage.error || records.error) && <div className="catalog-error">{db.error || coverage.error || records.error}</div>}
    <section className="detail-asset-card"><div className="detail-card-title"><div><span className="detail-card-kicker">数据库资产信息</span><h2>{database?.name || '加载中…'}</h2></div><div className="detail-card-actions"><span className="asset-online"><i />{value(database?.lifecycleStatus)}</span>{admin && database && <button className="button" onClick={() => setEditing(open => !open)}>{editing ? '收起编辑' : '编辑信息'}</button>}</div></div>{database && <><div className="detail-meta-grid"><div><span>DBID</span><strong>{value(database.dbid)}</strong></div><div><span>建库日期</span><strong>{shortDate(database.createdOn)}</strong></div><div><span>开始监控</span><strong>{shortDate(database.monitorFrom)}</strong></div><div><span>数据库等级</span><strong>{value(database.securityTier)}</strong></div><div><span>应用框架 / 版本</span><strong>{value(database.framework)} / {value(database.dbVersion)}</strong></div><div><span>子系统</span><strong>{value(database.subsystem)}</strong></div><div><span>开发人员</span><strong>{value(database.developer)}</strong></div><div><span>DBA</span><strong>{value(database.dba)}</strong></div><div><span>服务单元</span><strong>{value(database.serviceUnit)}</strong></div><div><span>历史备份记录</span><strong>{database.backupCount}</strong></div></div>{editing && <MetadataEditor db={database} onSaved={() => { db.reload(); coverage.reload(); setEditing(false) }} onCancel={() => setEditing(false)} />}</>}</section>
    <section className="coverage-panel"><div className="coverage-panel-head"><div><span className="detail-card-kicker">备份策略覆盖</span><h2>计划备份矩阵</h2><p>每个格子对应一份计划备份；成功为黄色，缺失或失败为红色。</p></div><div className="coverage-legend"><span><i className="legend-ok" />已有备份</span><span><i className="legend-missing" />缺失 / 失败</span><span><i className="legend-running" />进行中</span><span><i className="legend-unknown" />待完成 / 未核验</span></div></div>{coverage.loading ? <div className="catalog-loading">正在核对备份计划…</div> : coverage.data?.map(group => <CoverageBand key={group.kind} group={group} demo={demo} />)}</section>
    <section className="detail-records"><div className="detail-records-head"><div><span className="detail-card-kicker">备份明细</span><h2>备份记录</h2></div></div><form className="record-filters" onSubmit={event => { event.preventDefault(); setPage(0); setFilters(draft) }}><label>开始日期<input type="date" value={draft.from} onChange={event => setDraft(previous => ({ ...previous, from: event.target.value }))} /></label><label>结束日期<input type="date" value={draft.to} onChange={event => setDraft(previous => ({ ...previous, to: event.target.value }))} /></label><label>备份类型<select aria-label="备份类型" value={draft.kind} onChange={event => setDraft(previous => ({ ...previous, kind: event.target.value }))}><option value="">全部类型</option><option value="daily">日备</option><option value="monthly">月备</option><option value="yearly">年备</option></select></label><label>状态<select aria-label="备份状态" value={draft.status} onChange={event => setDraft(previous => ({ ...previous, status: event.target.value }))}><option value="">全部状态</option><option value="successed">成功</option><option value="cancel">取消 / 失败</option><option value="DISPATCHING">进行中</option></select></label><button className="button primary">查询</button></form><div className="catalog-table-scroll"><table className="record-table"><thead><tr><th>备份日期</th><th>类型</th><th>状态</th><th>记录更新时间</th><th>平台 ID</th></tr></thead><tbody>{records.data?.items.map(record => <tr key={record.id}><td>{shortDate(record.backupDate)} {record.dateInferred && <span className="muted">（推定）</span>}</td><td>{kindLabels[record.kind]}</td><td><span className={`record-status ${record.status.toLowerCase() === 'successed' ? 'success' : record.status.toLowerCase() === 'cancel' ? 'failed' : 'running'}`}>{record.status}</span></td><td>{dateTime(record.eventTime)}</td><td className="mono">{record.externalId}</td></tr>)}</tbody></table>{!records.loading && records.data?.items.length === 0 && <div className="catalog-loading">该条件下没有备份记录</div>}</div><div className="catalog-pagination"><button className="button" disabled={page === 0} onClick={() => setPage(number => number - 1)}>上一页</button><span>第 {page + 1} 页</span><button className="button" disabled={!records.data?.hasMore} onClick={() => setPage(number => number + 1)}>下一页</button></div></section>
  </div>
}

export default function Catalog({ selectedId, setSelectedId, admin }) {
  return selectedId ? <CatalogDetail id={selectedId} onBack={() => setSelectedId(null)} admin={admin} /> : <CatalogList onOpen={setSelectedId} admin={admin} />
}
