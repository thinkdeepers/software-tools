const $ = (id) => document.getElementById(id);

let state = { user: null, mappings: [], logs: [], settings: {} };
let repos = [];
let wizard = { repo: null, branch: null, createBranch: false, folder: null, mode: 'branch' };
const collapsed = new Set(); // 折叠起来的整仓任务 id

// ---------------- 渲染 ----------------
const STATUS_TEXT = {
  ok: '✓ 已同步', syncing: '同步中...', init: '初始化中...',
  idle: '等待中', paused: '已暂停', error: '出错', conflict: '⚠ 冲突待处理',
};

function render() {
  const loggedIn = !!state.user;
  $('view-login').classList.toggle('hidden', loggedIn);
  $('view-main').classList.toggle('hidden', !loggedIn);
  if (!loggedIn) return;

  $('user-label').textContent = state.user.name || state.user.login;
  $('poll-label').textContent = `云端检查间隔 ${state.settings.pollIntervalSec || 30}s`;

  const list = $('task-list');
  list.innerHTML = '';
  $('empty-hint').classList.toggle('hidden', state.mappings.length > 0);

  for (const m of state.mappings) {
    if (m.mode === 'repo') {
      list.appendChild(renderRepoTask(m));
      if (!collapsed.has(m.id)) {
        for (const b of m.branches || []) list.appendChild(renderBranchTask(b, true));
      }
    } else {
      list.appendChild(renderBranchTask(m, false));
    }
  }

  const logBox = $('log-box');
  logBox.innerHTML = state.logs.map(l => `<div>${esc(l)}</div>`).join('');
  logBox.scrollTop = logBox.scrollHeight;
}

function lastLine(m) {
  const last = m.lastSync ? `上次同步 ${new Date(m.lastSync).toLocaleString('zh-CN')}` : '尚未同步';
  return last + (m.error ? ' · ' + esc(m.error) : '');
}

function conflictBar(m) {
  if (m.status !== 'conflict') return '';
  return `<div class="conflict-bar">
    <span>本地与云端修改了同一文件，保留哪边的版本？</span>
    <button class="ghost small" data-act="res-local" data-id="${m.id}">以本地为准</button>
    <button class="ghost small" data-act="res-remote" data-id="${m.id}">以云端为准</button>
  </div>`;
}

function renderRepoTask(m) {
  const el = document.createElement('div');
  el.className = 'task repo-hub';
  const n = (m.branches || []).length;
  const isCollapsed = collapsed.has(m.id);
  el.innerHTML = `
    <div class="info">
      <div class="title">
        <button class="fold" data-act="fold" data-id="${m.id}" title="${isCollapsed ? '展开分支' : '折叠分支'}">${isCollapsed ? '▸' : '▾'}</button>
        ${esc(m.repoFullName)}<span class="branch-tag">整仓同步 · ${n} 个分支</span>
      </div>
      <div class="path">📁 ${esc(m.folder)}</div>
      <div class="last">${lastLine(m)}</div>
      <div class="hint">一层文件夹 = 一个分支，直接在文件夹里改文件即可，不用另建分支。<br />
      新建一层文件夹 → GitHub 上自动新建同名分支；删除文件夹 → 删除对应分支（默认分支除外）。</div>
    </div>
    <span class="badge ${m.status}">${STATUS_TEXT[m.status] || m.status}</span>
    <div class="ops">
      <button class="ghost small" data-act="sync" data-id="${m.id}">全部同步</button>
      <button class="ghost small" data-act="open" data-id="${m.id}">打开文件夹</button>
      <button class="ghost small" data-act="toggle" data-id="${m.id}">${m.enabled ? '暂停' : '启用'}</button>
      <button class="ghost small" data-act="del" data-id="${m.id}">删除任务</button>
    </div>`;
  return el;
}

function renderBranchTask(m, nested) {
  const el = document.createElement('div');
  el.className = nested ? 'task child-task' : 'task';
  el.innerHTML = `
    <div class="info">
      <div class="title">${nested ? '' : esc(m.repoFullName)}<span class="branch-tag">⎇ ${esc(m.branch)}</span></div>
      <div class="path">📁 ${esc(m.folder)}</div>
      <div class="last">${lastLine(m)}</div>
      ${conflictBar(m)}
    </div>
    <span class="badge ${m.status}">${STATUS_TEXT[m.status] || m.status}</span>
    <div class="ops">
      <button class="ghost small" data-act="sync" data-id="${m.id}">立即同步</button>
      <button class="ghost small" data-act="open" data-id="${m.id}">打开文件夹</button>
      ${nested ? `<button class="ghost small" data-act="del-branch" data-id="${m.id}">删除分支</button>` : `
      <button class="ghost small" data-act="toggle" data-id="${m.id}">${m.enabled ? '暂停' : '启用'}</button>
      <button class="ghost small" data-act="del" data-id="${m.id}">删除</button>`}
    </div>`;
  return el;
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// ---------------- 登录 ----------------
$('btn-login').onclick = async () => {
  $('login-err').textContent = '';
  $('btn-login').disabled = true;
  try {
    await window.api.login($('token-input').value);
    $('token-input').value = '';
  } catch (e) {
    $('login-err').textContent = /401/.test(e.message) ? 'Token 无效，请检查后重试' : '登录失败: ' + e.message;
  } finally {
    $('btn-login').disabled = false;
  }
};
$('token-input').addEventListener('keydown', e => { if (e.key === 'Enter') $('btn-login').click(); });
$('btn-token-help').onclick = () => window.api.openTokenPage();
$('btn-logout').onclick = () => window.api.logout();

// ---------------- 任务操作 ----------------
$('task-list').addEventListener('click', async (e) => {
  const btn = e.target.closest('button[data-act]');
  if (!btn) return;
  const { act, id } = btn.dataset;
  if (act === 'fold') {
    if (collapsed.has(id)) collapsed.delete(id); else collapsed.add(id);
    render();
    return;
  }
  if (act === 'sync') window.api.syncNow(id);
  if (act === 'open') window.api.openFolder(id);
  if (act === 'toggle') {
    const m = state.mappings.find(x => x.id === id);
    if (m) window.api.toggleMapping(id, !m.enabled);
  }
  if (act === 'del' && confirm('删除该同步任务？（本地文件与远程分支都不会被删除）')) window.api.removeMapping(id);
  if (act === 'del-branch' && confirm('将删除本地该分支文件夹，并删除 GitHub 上对应分支。默认分支无法删除。确定？')) {
    try { await window.api.deleteRepoBranch(id); }
    catch (e) { alert(e.message.replace(/^Error invoking remote method '.*?': (Error: )?/, '')); }
  }
  if (act === 'res-local') window.api.resolveConflict(id, 'local');
  if (act === 'res-remote') window.api.resolveConflict(id, 'remote');
});

// ---------------- 新建向导 ----------------
function showStep(n) {
  $('wz-step1').classList.toggle('hidden', n !== 1);
  $('wz-step2').classList.toggle('hidden', n !== 2);
  $('wz-step3').classList.toggle('hidden', n !== 3);
  $('wizard-title').textContent =
    n === 1 ? '新建同步 · 第 1 步：选择仓库' :
    n === 2 ? '新建同步 · 第 2 步：整仓或单个分支' :
              '新建同步 · 第 3 步：选择本地文件夹';
}

$('btn-add').onclick = async () => {
  wizard = { repo: null, branch: null, createBranch: false, folder: null, mode: 'branch' };
  $('wizard').classList.remove('hidden');
  $('wizard-err').textContent = '';
  $('folder-display').value = '';
  $('repo-filter').value = '';
  showStep(1);
  $('repo-list').innerHTML = '<div class="muted pad">加载中...</div>';
  try {
    repos = await window.api.listRepos();
    renderRepoList('');
  } catch (e) {
    $('repo-list').innerHTML = `<div class="err pad">加载仓库失败: ${esc(e.message)}</div>`;
  }
};
$('wizard-close').onclick = () => $('wizard').classList.add('hidden');

function renderRepoList(filter) {
  const box = $('repo-list');
  const items = repos.filter(r => r.fullName.toLowerCase().includes(filter.toLowerCase()));
  box.innerHTML = items.length ? '' : '<div class="muted pad">没有匹配的仓库</div>';
  for (const r of items) {
    const div = document.createElement('div');
    div.className = 'pick-item';
    div.innerHTML = `<div class="name">${esc(r.fullName)} ${r.private ? '🔒' : ''}</div>
                     <div class="desc">${esc(r.description || '无描述')}</div>`;
    div.onclick = () => pickRepo(r);
    box.appendChild(div);
  }
}
$('repo-filter').addEventListener('input', e => renderRepoList(e.target.value));

async function pickRepo(repo) {
  wizard.repo = repo;
  showStep(2);
  $('chosen-repo').textContent = `已选仓库：${repo.fullName}（默认分支 ${repo.defaultBranch}）`;
  $('new-branch-name').value = '';
  $('branch-list').innerHTML = '<div class="muted pad">加载中...</div>';
  try {
    const branches = await window.api.listBranches(repo.fullName);
    const box = $('branch-list');
    box.innerHTML = '';
    for (const b of branches) {
      const div = document.createElement('div');
      div.className = 'pick-item';
      div.innerHTML = `<div class="name">⎇ ${esc(b)}</div>`;
      div.onclick = () => pickBranch(b, false);
      box.appendChild(div);
    }
  } catch (e) {
    $('branch-list').innerHTML = `<div class="err pad">加载分支失败: ${esc(e.message)}</div>`;
  }
}

$('btn-new-branch').onclick = () => {
  const name = $('new-branch-name').value.trim();
  if (!name) return;
  pickBranch(name, true);
};
$('repo-mode-pick').onclick = () => { if (wizard.repo) pickRepoMode(); };

function pickRepoMode() {
  wizard.mode = 'repo';
  wizard.branch = null;
  wizard.createBranch = false;
  showStep(3);
  $('chosen-summary').textContent = `${wizard.repo.fullName} · 整仓同步（每个分支一层文件夹）`;
  $('folder-tip').innerHTML =
    '· 所选目录下，每个远程分支会成为一层文件夹（分支名中的 / 等特殊字符会换成 _）<br />' +
    '· 直接在某个分支文件夹里改文件即可，改动自动提交推送到该分支，不需要另外新建分支<br />' +
    '· 云端新增分支 → 本地自动多一层文件夹；云端删除分支 → 本地对应文件夹自动移除<br />' +
    '· 本地新建一层文件夹 → GitHub 上自动新建同名分支；删除文件夹 → 删除对应分支（默认分支除外）';
}

function pickBranch(branch, isNew) {
  wizard.mode = 'branch';
  wizard.branch = branch;
  wizard.createBranch = isNew;
  showStep(3);
  $('chosen-summary').textContent =
    `${wizard.repo.fullName} 的分支「${branch}」${isNew ? '（新建）' : ''} ⇄ 本地文件夹`;
  $('folder-tip').innerHTML =
    '· 空文件夹：自动下载分支全部内容<br />' +
    '· 非空文件夹：自动初始化并与分支内容合并（同名冲突以本地文件为准）<br />' +
    '· 每个文件夹只能绑定一个分支';
}

$('btn-pick-folder').onclick = async () => {
  const folder = await window.api.pickFolder();
  if (folder) { wizard.folder = folder; $('folder-display').value = folder; }
};

$('btn-create').onclick = async () => {
  $('wizard-err').textContent = '';
  if (!wizard.folder) { $('wizard-err').textContent = '请先选择本地文件夹'; return; }
  if (wizard.mode !== 'repo' && !wizard.branch) { $('wizard-err').textContent = '请先选择分支'; return; }
  $('btn-create').disabled = true;
  $('btn-create').textContent = '初始化中，请稍候...';
  try {
    await window.api.addMapping({
      mode: wizard.mode === 'repo' ? 'repo' : 'branch',
      repoFullName: wizard.repo.fullName,
      cloneUrl: wizard.repo.cloneUrl,
      branch: wizard.branch,
      folder: wizard.folder,
      createBranch: wizard.createBranch,
      baseBranch: wizard.repo.defaultBranch,
      defaultBranch: wizard.repo.defaultBranch,
    });
    $('wizard').classList.add('hidden');
  } catch (e) {
    $('wizard-err').textContent = e.message.replace(/^Error invoking remote method '.*?': (Error: )?/, '');
  } finally {
    $('btn-create').disabled = false;
    $('btn-create').textContent = '开始同步';
  }
};

// ---------------- 启动 ----------------
window.api.onState(s => { state = s; render(); });
window.api.getState().then(s => { state = s; render(); });
