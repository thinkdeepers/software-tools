// GitHub REST API 客户端（支持环境变量覆盖 API 地址，便于测试）
const API_BASE = process.env.GITHUB_API_BASE || 'https://api.github.com';

async function req(token, url, options = {}) {
  const res = await fetch(`${API_BASE}${url}`, {
    ...options,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      ...(options.headers || {}),
    },
  });
  const body = await res.text().catch(() => '');
  if (!res.ok) {
    const err = new Error(`GitHub API ${res.status}: ${body.slice(0, 200)}`);
    err.status = res.status;
    throw err;
  }
  if (!body) return null;
  try { return JSON.parse(body); } catch { return null; }
}

// 验证 token 并返回用户信息
async function getUser(token) {
  return req(token, '/user');
}

// 列出用户可访问的全部仓库（分页拉全）
async function listRepos(token) {
  const all = [];
  for (let page = 1; page <= 10; page++) {
    const batch = await req(token, `/user/repos?per_page=100&page=${page}&sort=updated`);
    all.push(...batch);
    if (batch.length < 100) break;
  }
  return all.map(r => ({
    fullName: r.full_name,
    cloneUrl: r.clone_url,
    defaultBranch: r.default_branch,
    private: r.private,
    description: r.description || '',
  }));
}

// 列出仓库的全部分支
async function listBranches(token, fullName) {
  const all = [];
  for (let page = 1; page <= 10; page++) {
    const batch = await req(token, `/repos/${fullName}/branches?per_page=100&page=${page}`);
    all.push(...batch);
    if (batch.length < 100) break;
  }
  return all.map(b => b.name);
}

async function getRepo(token, fullName) {
  const r = await req(token, `/repos/${fullName}`);
  return {
    fullName: r.full_name,
    cloneUrl: r.clone_url,
    defaultBranch: r.default_branch,
    private: r.private,
    description: r.description || '',
  };
}

async function deleteBranch(token, fullName, branch) {
  const ref = String(branch).split('/').map(encodeURIComponent).join('/');
  try {
    await req(token, `/repos/${fullName}/git/refs/heads/${ref}`, { method: 'DELETE' });
  } catch (e) {
    if (e.status === 404) return; // 已经不存在
    throw e;
  }
}

module.exports = { getUser, listRepos, listBranches, getRepo, deleteBranch };
