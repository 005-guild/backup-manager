let csrfToken = null

export async function csrf() {
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!response.ok) throw new Error('无法初始化登录')
  csrfToken = (await response.json()).token
  return csrfToken
}

export async function api(path, options = {}) {
  const method = options.method || 'GET'
  if (method !== 'GET' && !csrfToken) await csrf()
  const headers = { ...(options.headers || {}) }
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = csrfToken
  if (options.body && typeof options.body !== 'string') {
    headers['Content-Type'] = 'application/json'
    options = { ...options, body: JSON.stringify(options.body) }
  }
  const response = await fetch(path, { ...options, method, headers, credentials: 'include' })
  const body = response.headers.get('content-type')?.includes('application/json') ? await response.json() : null
  if (!response.ok) {
    const error = new Error(body?.error || body?.message || `请求失败（${response.status}）`)
    error.status = response.status
    throw error
  }
  return body
}

export async function login(username, password) {
  await csrf()
  const body = new URLSearchParams({ username, password })
  const result = await api('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body.toString() })
  csrfToken = null
  await csrf()
  return result
}

export async function logout() {
  const result = await api('/api/auth/logout', { method: 'POST' })
  csrfToken = null
  return result
}
