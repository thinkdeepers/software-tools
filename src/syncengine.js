// 双向同步引擎：
// 本地方向：chokidar 监听文件变动 → 防抖 → 自动 commit + push
// 云端方向：定时 fetch → 有新提交则 merge 到本地
// 冲突：中止合并，任务进入"冲突"状态等用户选择（以本地为准 / 以云端为准）
const chokidar = require('chokidar');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { run, must, authUrl } = require('./gitops');

const GIT_DIR_RE = /(^|[/\\])\.git([/\\]|$)/;

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// fetch / ls-remote / push --delete 在「分支已经不存在」时的典型报错
function isRemoteRefMissing(err, out = '') {
  const s = `${err || ''} ${out || ''}`;
  return /couldn't find remote ref|remote ref does not exist|unresolvable reference/i.test(s);
}

function isProtectedBranchError(err) {
  const s = String(err || '');
  return /default branch|refusing to delete|cannot delete the default branch|protected branch/i.test(s)
    || err?.status === 403 || err?.status === 422 || err?.code === 'PROTECTED';
}

function chmodTreeWritable(root) {
  if (!fs.existsSync(root)) return;
  try { fs.chmodSync(root, 0o700); } catch { /* ignore */ }
  let entries = [];
  try { entries = fs.readdirSync(root, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    const p = path.join(root, e.name);
    try { fs.chmodSync(p, e.isDirectory() ? 0o700 : 0o600); } catch { /* ignore */ }
    if (e.isDirectory()) chmodTreeWritable(p);
  }
}

// Windows 上 chokidar / git 句柄没释放完时 rm 会 EBUSY/EPERM，多试几次
async function removeDirRetry(dir, attempts = 8) {
  if (!dir || !fs.existsSync(dir)) return;
  let last;
  for (let i = 0; i < attempts; i++) {
    try {
      fs.rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 150 });
      if (!fs.existsSync(dir)) return;
    } catch (e) { last = e; }
    if (i === 2 || i === 5) {
      try { chmodTreeWritable(dir); } catch { /* ignore */ }
    }
    await sleep(150 * (i + 1));
  }
  if (fs.existsSync(dir)) throw last || new Error(`无法删除文件夹 ${dir}`);
}

async function deleteRemoteBranchViaGit(cloneUrl, branch, opts = {}) {
  const url = authUrl(cloneUrl);
  const cwd = opts.cwd && fs.existsSync(opts.cwd) ? opts.cwd : os.tmpdir();
  const r = await run(['push', url, '--delete', branch], { ...opts, cwd });
  if (r.code === 0) return;
  if (isRemoteRefMissing(r.err, r.out)) return;
  if (isProtectedBranchError(r.err) || isProtectedBranchError(r.out)) {
    const e = new Error(r.err || r.out || `GitHub 不允许删除分支 ${branch}`);
    e.code = 'PROTECTED';
    throw e;
  }
  throw new Error(`删除远程分支 ${branch} 失败: ${r.err || r.out}`);
}

class SyncTask {
  constructor(data, ctx) {
    // data: {id, repoFullName, cloneUrl, branch, folder, enabled}
    Object.assign(this, data);
    this.ctx = ctx; // {getToken, getIdentity, onUpdate, log, getPollInterval}
    this.status = data.enabled ? 'idle' : 'paused';
    this.lastSync = null;
    this.error = null;
    this._chain = Promise.resolve();
    this._debounce = null;
    this._watcher = null;
    this._timer = null;
    this.hubId = data.hubId || null;
    // 云端删了分支 → 删本地文件夹；本地文件夹没了 → 删远程分支
    this.onRemoteGone = null;
    this.onLocalGone = null;
    this._dropping = false;
    this._localGoneNotified = false;
  }

  view() {
    const { id, repoFullName, branch, folder, enabled, status, lastSync, error, hubId } = this;
    return {
      id, repoFullName, branch, folder, enabled, status, lastSync, error,
      mode: hubId ? 'repo-branch' : 'branch',
      hubId: hubId || null,
    };
  }

  log(msg) { this.ctx.log(`[${this.repoFullName}#${this.branch}] ${msg}`); }
  setStatus(s, err = null) { this.status = s; this.error = err; this.ctx.onUpdate(); }

  opts() {
    return { cwd: this.folder, token: this.ctx.getToken(), identity: this.ctx.getIdentity() };
  }

  // 直接问远程仓库：存在 true，确定不存在 false，网络/认证异常抛错。
  // 用 clone URL 而不是 cwd 里的 origin，文件夹正在被删时也能问。
  async remoteBranchExists() {
    const url = authUrl(this.cloneUrl);
    const cwd = this.folder && fs.existsSync(this.folder) ? this.folder : os.tmpdir();
    const r = await run(['ls-remote', '--exit-code', '--heads', url, this.branch], { ...this.opts(), cwd });
    if (r.code === 0) return true;
    if (r.code === 1 || r.code === 2) return false;
    if (isRemoteRefMissing(r.err, r.out)) return false;
    throw new Error(`无法访问远程仓库: ${r.err || r.out}`);
  }

  // ---------- 初始化：把本地文件夹和远程分支关联起来 ----------
  async initialize({ createBranch = false, baseBranch = null } = {}) {
    this.setStatus('init');
    const token = this.ctx.getToken();
    const url = authUrl(this.cloneUrl);
    const exists = fs.existsSync(this.folder);
    const entries = exists ? fs.readdirSync(this.folder) : [];
    const isRepo = exists && fs.existsSync(path.join(this.folder, '.git'));

    try {
      if (isRepo) {
        // 已是 git 仓库：校验远程并切到目标分支
        this.log('检测到已有 Git 仓库，直接关联');
        await must(['remote', 'set-url', 'origin', url], this.opts(), '设置远程地址');
        const f = await run(['fetch', 'origin', `+refs/heads/${this.branch}:refs/remotes/origin/${this.branch}`], this.opts());
        if (f.code === 0) {
          const co = await run(['checkout', this.branch], this.opts());
          if (co.code !== 0) {
            await must(['checkout', '-b', this.branch, `origin/${this.branch}`], this.opts(), '切换分支');
          }
        } else if (createBranch) {
          await must(['checkout', '-b', this.branch], this.opts(), '创建分支');
          await must(['push', '-u', 'origin', this.branch], this.opts(), '推送新分支');
        } else {
          throw new Error(`远程分支 ${this.branch} 不存在`);
        }
      } else if (!exists || entries.length === 0) {
        // 空文件夹：直接克隆
        fs.mkdirSync(this.folder, { recursive: true });
        if (createBranch) {
          this.log(`克隆 ${baseBranch} 并创建新分支 ${this.branch}`);
          await must(['clone', '--branch', baseBranch, url, '.'], this.opts(), '克隆仓库');
          await must(['checkout', '-b', this.branch], this.opts(), '创建分支');
          await must(['push', '-u', 'origin', this.branch], this.opts(), '推送新分支');
        } else {
          this.log(`克隆分支 ${this.branch}`);
          await must(['clone', '--branch', this.branch, '--single-branch', url, '.'], this.opts(), '克隆仓库');
        }
      } else {
        // 非空且不是仓库：初始化并把本地文件合并进分支（冲突以本地为准）
        this.log('非空文件夹：初始化仓库并合并远程内容（冲突以本地文件为准）');
        await must(['init', '-b', this.branch], this.opts(), '初始化仓库');
        await must(['remote', 'add', 'origin', url], this.opts(), '添加远程');
        await must(['add', '-A'], this.opts(), '暂存本地文件');
        await must(['commit', '-m', '本地初始文件'], this.opts(), '提交本地文件');
        const f = await run(['fetch', 'origin', `+refs/heads/${this.branch}:refs/remotes/origin/${this.branch}`], this.opts());
        if (f.code === 0) {
          await must(['merge', `origin/${this.branch}`, '--allow-unrelated-histories', '-X', 'ours', '--no-edit'], this.opts(), '合并远程内容');
        } else if (!createBranch) {
          throw new Error(`远程分支 ${this.branch} 不存在`);
        }
        await must(['push', '-u', 'origin', this.branch], this.opts(), '推送');
      }
      this.log('初始化完成');
      this.lastSync = new Date().toISOString();
      this.setStatus('ok');
      return true;
    } catch (e) {
      this.log(`初始化失败: ${e.message}`);
      this.setStatus('error', e.message);
      throw e;
    }
  }

  // ---------- 同步循环（串行化，双向） ----------
  requestSync(reason) {
    if (!this.enabled || this._dropping || this.status === 'conflict' || this.status === 'init') return;
    this._chain = this._chain.then(() => this._cycle(reason)).catch(() => {});
    return this._chain;
  }

  async _cycle(reason) {
    if (!this.enabled || this._dropping || this.status === 'conflict') return;
    if (this._bailIfFolderGone()) return;
    this.setStatus('syncing');
    try {
      // 先问云端还在不在。分支没了就删本地，不要先 commit，否则未推送提交会挡住删除。
      const f = await run(['fetch', 'origin', `+refs/heads/${this.branch}:refs/remotes/origin/${this.branch}`], this.opts());
      let remoteExists = f.code === 0;
      if (!remoteExists) {
        if (isRemoteRefMissing(f.err, f.out)) {
          remoteExists = false;
        } else {
          remoteExists = await this.remoteBranchExists();
          if (remoteExists) throw new Error(`拉取失败: ${f.err || f.out}`);
        }
      }

      if (!remoteExists) {
        await this._handleRemoteGone();
        return;
      }

      // 本地变动 → 提交
      await run(['add', '-A'], this.opts());
      const staged = await run(['diff', '--cached', '--quiet'], this.opts());
      if (staged.code === 1) {
        const msg = `自动同步: ${new Date().toLocaleString('zh-CN')}`;
        await must(['commit', '-m', msg], this.opts(), '提交本地变动');
        this.log(`已提交本地变动（${reason}）`);
      }

      const cnt = await must(['rev-list', '--left-right', '--count', `HEAD...origin/${this.branch}`], this.opts(), '比较进度');
      let [ahead, behind] = cnt.out.split(/\s+/).map(Number);

      if (behind > 0) {
        const m = await run(['merge', `origin/${this.branch}`, '--no-edit'], this.opts());
        if (m.code !== 0) {
          await run(['merge', '--abort'], this.opts());
          this.log('本地与云端修改了同一文件，产生冲突，请在界面上选择保留哪边');
          this.setStatus('conflict', '本地与云端修改冲突');
          return;
        }
        this.log(`已拉取云端 ${behind} 个新提交到本地`);
        const cnt2 = await must(['rev-list', '--left-right', '--count', `HEAD...origin/${this.branch}`], this.opts(), '比较进度');
        [ahead] = cnt2.out.split(/\s+/).map(Number);
      }

      if (ahead > 0) {
        const p = await run(['push', 'origin', `HEAD:${this.branch}`], this.opts());
        if (p.code !== 0) {
          if (isRemoteRefMissing(p.err, p.out)) {
            await this._handleRemoteGone();
            return;
          }
          throw new Error(`推送失败: ${p.err}`);
        }
        this.log(`已推送 ${ahead} 个提交到云端`);
      }

      this.lastSync = new Date().toISOString();
      this.setStatus('ok');
    } catch (e) {
      if (this._dropping || this._bailIfFolderGone()) return;
      if (isRemoteRefMissing(e.message)) {
        await this._handleRemoteGone();
        return;
      }
      this.log(`同步出错: ${e.message}`);
      this.setStatus('error', e.message);
    }
  }

  async _handleRemoteGone() {
    if (this._dropping) return;
    this.log('云端已找不到该分支，正在删除本地对应文件夹');
    this.setStatus('idle');
    if (this.onRemoteGone) {
      await Promise.resolve(this.onRemoteGone());
      return;
    }
    await this.dropLocal();
  }

  async dropLocal() {
    this._dropping = true;
    this.enabled = false;
    await this.stop();
    await removeDirRetry(this.folder);
  }

  _bailIfFolderGone() {
    if (this._dropping) return true;
    if (fs.existsSync(this.folder)) return false;
    this.stop();
    this.log('本地文件夹已不存在');
    this.setStatus('idle');
    if (!this._localGoneNotified && this.onLocalGone) {
      this._localGoneNotified = true;
      Promise.resolve(this.onLocalGone()).catch((e) => {
        this.log(`删除远程分支失败: ${e.message}`);
        this.setStatus('error', e.message);
      });
    }
    return true;
  }

  // ---------- 冲突处理 ----------
  async resolveConflict(strategy) {
    // strategy: 'local' 以本地为准 | 'remote' 以云端为准
    this.setStatus('syncing');
    this._chain = this._chain.then(async () => {
      try {
        const x = strategy === 'local' ? 'ours' : 'theirs';
        await must(['merge', `origin/${this.branch}`, '-X', x, '--no-edit'], this.opts(), '合并');
        await must(['push', 'origin', `HEAD:${this.branch}`], this.opts(), '推送');
        this.log(`冲突已解决（以${strategy === 'local' ? '本地' : '云端'}为准）`);
        this.lastSync = new Date().toISOString();
        this.setStatus('ok');
      } catch (e) {
        this.log(`冲突解决失败: ${e.message}`);
        this.setStatus('error', e.message);
      }
    });
    return this._chain;
  }

  // ---------- 监听与定时 ----------
  async start() {
    if (!this.enabled || this._dropping) return;
    if (!fs.existsSync(this.folder)) {
      this.log('本地文件夹不存在');
      if (!this.hubId && this.onLocalGone && !this._localGoneNotified) {
        this._localGoneNotified = true;
        await Promise.resolve(this.onLocalGone());
      } else {
        this.setStatus(this.hubId ? 'idle' : 'error', this.hubId ? null : '本地文件夹不存在');
      }
      return;
    }
    await this.stop();
    this._watcher = chokidar.watch(this.folder, {
      ignored: GIT_DIR_RE,
      ignoreInitial: true,
      awaitWriteFinish: { stabilityThreshold: 800, pollInterval: 200 },
    });
    this._watcher.on('all', () => {
      if (this._dropping) return;
      clearTimeout(this._debounce);
      this._debounce = setTimeout(() => this.requestSync('本地文件变动'), 2500);
    });
    this._watcher.on('unlinkDir', () => { this._bailIfFolderGone(); });
    this._watcher.on('error', () => { this._bailIfFolderGone(); });
    const interval = Math.max(5, this.ctx.getPollInterval()) * 1000;
    this._timer = setInterval(() => this.requestSync('定时检查云端'), interval);
    this.log('同步已启动（监听本地变动 + 定时检查云端）');
    this.requestSync('启动检查');
  }

  async stop() {
    const w = this._watcher;
    this._watcher = null;
    if (this._timer) { clearInterval(this._timer); this._timer = null; }
    clearTimeout(this._debounce);
    if (w) {
      try { await w.close(); } catch { /* ignore */ }
    }
  }
}

class SyncEngine {
  constructor(ctx) {
    this.ctx = ctx;
    this.tasks = new Map(); // 单分支任务
    this.hubs = new Map();  // 整仓任务
  }

  addTask(data) {
    const task = new SyncTask(data, this.ctx);
    task.onRemoteGone = () => this._handleStandaloneRemoteGone(task);
    task.onLocalGone = () => this._handleStandaloneLocalGone(task);
    this.tasks.set(data.id, task);
    return task;
  }

  async _deleteRemote(task) {
    try {
      if (this.ctx.getDefaultBranch) {
        const def = await this.ctx.getDefaultBranch(task.repoFullName);
        if (def && def === task.branch) {
          const e = new Error(`「${task.branch}」是默认分支，GitHub 不允许删除`);
          e.code = 'PROTECTED';
          throw e;
        }
      }
    } catch (e) {
      if (e.code === 'PROTECTED') throw e;
      /* 拿不到默认分支名就继续试删 */
    }
    try {
      if (this.ctx.deleteBranch) {
        await this.ctx.deleteBranch(task.repoFullName, task.branch);
        return;
      }
    } catch (e) {
      if (e.status === 404) return;
      if (isProtectedBranchError(e)) throw e;
      this.ctx.log(`[${task.repoFullName}#${task.branch}] GitHub API 删除失败，改用 git push --delete: ${e.message}`);
    }
    await deleteRemoteBranchViaGit(task.cloneUrl, task.branch, {
      token: this.ctx.getToken(),
      identity: this.ctx.getIdentity(),
      cwd: task.folder,
    });
  }

  async _handleStandaloneRemoteGone(task) {
    if (!this.tasks.has(task.id)) return;
    this.ctx.log(`[${task.repoFullName}#${task.branch}] 云端分支已删除，正在删除本地文件夹`);
    try {
      await task.dropLocal();
      this.tasks.delete(task.id);
      this.ctx.onPersist && this.ctx.onPersist();
      this.ctx.onUpdate();
      this.ctx.log(`[${task.repoFullName}#${task.branch}] 已删除本地文件夹`);
    } catch (e) {
      this.ctx.log(`[${task.repoFullName}#${task.branch}] 删除本地文件夹失败: ${e.message}`);
      task.setStatus('error', e.message);
    }
  }

  async _handleStandaloneLocalGone(task) {
    if (!this.tasks.has(task.id)) return;
    try {
      await this._deleteRemote(task);
      this.ctx.log(`[${task.repoFullName}#${task.branch}] 本地文件夹已删除，已删除远程分支`);
    } catch (e) {
      this.ctx.log(`[${task.repoFullName}#${task.branch}] 删除远程分支失败: ${e.message}`);
      if (isProtectedBranchError(e)) {
        try {
          fs.mkdirSync(task.folder, { recursive: true });
          task._dropping = false;
          task._localGoneNotified = false;
          task.enabled = true;
          await task.initialize();
          await task.start();
        } catch (e2) {
          task.setStatus('error', e2.message);
        }
        this.ctx.onUpdate();
        return;
      }
      task.setStatus('error', e.message);
      this.ctx.onUpdate();
      return;
    }
    this.tasks.delete(task.id);
    this.ctx.onPersist && this.ctx.onPersist();
    this.ctx.onUpdate();
  }

  addHub(data) {
    const hub = new RepoHub(data, this.ctx);
    this.hubs.set(data.id, hub);
    return hub;
  }

  addFromConfig(data) {
    return data.mode === 'repo' ? this.addHub(data) : this.addTask(data);
  }

  removeTask(id) {
    const t = this.tasks.get(id);
    if (t) { Promise.resolve(t.stop()); this.tasks.delete(id); return; }
    const h = this.hubs.get(id);
    if (h) { Promise.resolve(h.stop()); this.hubs.delete(id); }
  }

  get(id) {
    if (this.tasks.has(id)) return this.tasks.get(id);
    if (this.hubs.has(id)) return this.hubs.get(id);
    for (const h of this.hubs.values()) {
      const c = h.childById(id);
      if (c) return c;
    }
    return undefined;
  }

  getHub(id) { return this.hubs.get(id); }

  views() {
    return [
      ...[...this.hubs.values()].map(h => h.view()),
      ...[...this.tasks.values()].map(t => t.view()),
    ];
  }

  serialize() {
    return [
      ...[...this.hubs.values()].map(h => h.serialize()),
      ...[...this.tasks.values()].map(t => ({
        id: t.id,
        mode: 'branch',
        repoFullName: t.repoFullName,
        cloneUrl: t.cloneUrl,
        branch: t.branch,
        folder: t.folder,
        enabled: t.enabled,
      })),
    ];
  }

  persist() { if (this.ctx.onPersist) this.ctx.onPersist(); }

  syncAll(reason) {
    for (const h of this.hubs.values()) if (h.enabled) h.requestSync(reason);
    for (const t of this.tasks.values()) if (t.enabled) t.requestSync(reason);
  }

  stopAll() {
    for (const h of this.hubs.values()) Promise.resolve(h.stop());
    for (const t of this.tasks.values()) Promise.resolve(t.stop());
  }

  restartAll() {
    for (const h of this.hubs.values()) {
      if (h.enabled) Promise.resolve(h.start()).catch(e => this.ctx.log(`整仓启动失败: ${e.message}`));
    }
    for (const t of this.tasks.values()) if (t.enabled) Promise.resolve(t.start()).catch(e => this.ctx.log(`启动失败: ${e.message}`));
  }
}

const WIN_BAD = /[<>:"/\\|?*\x00-\x1f]/g;

function folderNameForBranch(branch, taken) {
  let base = String(branch).replace(WIN_BAD, '_').replace(/^[. ]+/, '_').replace(/[. ]+$/g, '_');
  if (!base || base === '.' || base === '..') base = 'branch';
  if (/^(con|prn|aux|nul|com[1-9]|lpt[1-9])$/i.test(base)) base = `_${base}`;
  const lower = new Set([...taken].map(s => s.toLowerCase()));
  let name = base;
  let n = 2;
  while (lower.has(name.toLowerCase())) name = `${base}_${n++}`;
  return name;
}

// 云端分支被删、但本地还有没推上去的内容时，文件夹保留成这个后缀，避免误删用户数据
const ARCHIVE_SUFFIX = '__云端已删除';

// 文件夹名要变成分支名，得先满足 git 的分支命名规则
function isValidBranchName(name) {
  if (!name || name === '@') return false;
  if (/[\s~^:?*[\\]/.test(name)) return false;
  if (name.includes('..') || name.includes('@{')) return false;
  if (name.startsWith('/') || name.startsWith('-') || name.startsWith('.')) return false;
  if (name.endsWith('/') || name.endsWith('.') || name.endsWith('.lock')) return false;
  return true;
}

function samePath(a, b) {
  const A = path.resolve(a);
  const B = path.resolve(b);
  return process.platform === 'win32' ? A.toLowerCase() === B.toLowerCase() : A === B;
}

function isDirectChildDir(root, p) {
  return samePath(path.dirname(p), root);
}

// 整仓同步：根目录下每个一层文件夹对应一个远程分支。
// 删除该文件夹 → 删除 GitHub 上对应分支；远程新增分支 → 自动建本地文件夹。
class RepoHub {
  constructor(data, ctx) {
    this.id = data.id;
    this.mode = 'repo';
    this.repoFullName = data.repoFullName;
    this.cloneUrl = data.cloneUrl;
    this.folder = data.folder;
    this.enabled = data.enabled !== false;
    this.defaultBranch = data.defaultBranch || null;
    this.ctx = ctx;
    this.status = this.enabled ? 'idle' : 'paused';
    this.lastSync = null;
    this.error = null;
    this.children = new Map(); // branch -> SyncTask
    this._rootWatcher = null;
    this._timer = null;
    this._pendingDeletes = new Map();
    this._pendingAdds = new Map();
    this._ignore = new Set();
    this._creating = new Set();
    this._goneHandling = new Set();
    this._reconciling = false;
    this._starting = false;
    if (Array.isArray(data.children)) {
      for (const c of data.children) this._attachChild(c);
    }
  }

  childById(id) {
    for (const t of this.children.values()) if (t.id === id) return t;
    return null;
  }

  takenFolderNames() {
    return new Set([...this.children.values()].map(t => path.basename(t.folder)));
  }

  _attachChild(c) {
    const folder = path.join(this.folder, c.folderName);
    const task = new SyncTask({
      id: c.id,
      repoFullName: this.repoFullName,
      cloneUrl: this.cloneUrl,
      branch: c.branch,
      folder,
      enabled: this.enabled,
      hubId: this.id,
    }, this.ctx);
    task.onRemoteGone = () => this.handleRemoteBranchGone(c.branch);
    task.onLocalGone = () => this._onFolderRemoved(path.basename(folder));
    this.children.set(c.branch, task);
    return task;
  }

  view() {
    const branches = [...this.children.values()].map(t => t.view());
    let status = this.status;
    if (this.enabled) {
      if (branches.some(b => b.status === 'conflict')) status = 'conflict';
      else if (branches.some(b => b.status === 'error')) status = 'error';
      else if (branches.some(b => b.status === 'init' || b.status === 'syncing')) status = 'syncing';
      else if (branches.length && branches.every(b => b.status === 'ok')) status = 'ok';
    }
    const lastSync = branches.reduce((acc, b) => {
      if (!b.lastSync) return acc;
      return !acc || b.lastSync > acc ? b.lastSync : acc;
    }, this.lastSync);
    const error = branches.find(b => b.error)?.error || this.error;
    return {
      id: this.id,
      mode: 'repo',
      repoFullName: this.repoFullName,
      folder: this.folder,
      enabled: this.enabled,
      status,
      lastSync,
      error,
      defaultBranch: this.defaultBranch,
      branches,
    };
  }

  serialize() {
    return {
      id: this.id,
      mode: 'repo',
      repoFullName: this.repoFullName,
      cloneUrl: this.cloneUrl,
      folder: this.folder,
      enabled: this.enabled,
      defaultBranch: this.defaultBranch,
      children: [...this.children.values()].map(t => ({
        id: t.id,
        branch: t.branch,
        folderName: path.basename(t.folder),
      })),
    };
  }

  log(msg) { this.ctx.log(`[${this.repoFullName} 整仓] ${msg}`); }
  setStatus(s, err = null) { this.status = s; this.error = err; this.ctx.onUpdate(); }

  requestSync(reason) {
    for (const t of this.children.values()) t.requestSync(reason);
    // 整仓同步不止同步文件，还要同步「有哪些分支」
    if (this.enabled && !this._reconciling) {
      this._reconciling = true;
      Promise.resolve(this.reconcile())
        .catch(e => this.log(`检查远程分支失败: ${e.message}`))
        .finally(() => { this._reconciling = false; });
    }
  }

  resolveConflict() { return Promise.resolve(); }

  async initialize() {
    this.setStatus('init');
    fs.mkdirSync(this.folder, { recursive: true });
    try {
      if (this.ctx.getDefaultBranch) {
        this.defaultBranch = await this.ctx.getDefaultBranch(this.repoFullName);
      }
      const branches = await this.ctx.listBranches(this.repoFullName);
      if (!branches.length) throw new Error('该仓库没有任何分支');
      this.log(`开始同步整个仓库，共 ${branches.length} 个分支`);
      for (const branch of branches) {
        await this.ensureBranch(branch, { initialize: true });
      }
      this.lastSync = new Date().toISOString();
      this.setStatus('ok');
      this.log('整仓初始化完成（每个分支对应一层文件夹）');
      return true;
    } catch (e) {
      this.log(`初始化失败: ${e.message}`);
      this.setStatus('error', e.message);
      throw e;
    }
  }

  async ensureBranch(branch, { initialize = false, folderName = null, createBranch = false } = {}) {
    if (this.children.has(branch)) {
      const t = this.children.get(branch);
      if (!fs.existsSync(path.join(t.folder, '.git'))) {
        fs.mkdirSync(t.folder, { recursive: true });
        await t.initialize();
      }
      if (this.enabled && this._rootWatcher) await t.start();
      return t;
    }
    const name = folderName || folderNameForBranch(branch, this.takenFolderNames());
    const folder = path.join(this.folder, name);
    this._ignore.add(path.resolve(folder));
    const task = this._attachChild({
      id: crypto.randomUUID(),
      branch,
      folderName: name,
    });
    try {
      if (initialize || !fs.existsSync(folder) || !fs.existsSync(path.join(folder, '.git'))) {
        fs.mkdirSync(folder, { recursive: true });
        if (createBranch) {
          if (!this.defaultBranch) throw new Error('仓库还没有任何分支，无法基于默认分支创建');
          await task.initialize({ createBranch: true, baseBranch: this.defaultBranch });
        } else {
          await task.initialize();
        }
      }
      if (this.enabled && this._rootWatcher) await task.start();
      this.log(`已加入分支 ${branch} → 文件夹 ${name}`);
      this.ctx.onPersist && this.ctx.onPersist();
      this.ctx.onUpdate();
      return task;
    } catch (e) {
      task.stop();
      this.children.delete(branch);
      throw e;
    } finally {
      setTimeout(() => this._ignore.delete(path.resolve(folder)), 15000);
    }
  }

  // 云端删了分支 → 本地对应文件夹也要消失，保持「一个分支 = 一层文件夹」
  async handleRemoteBranchGone(branch, { confirmedGone = false } = {}) {
    const task = this.children.get(branch);
    if (!task || this._goneHandling.has(branch) || task._dropping) return;
    if (task.status === 'init' || this._creating.has(path.basename(task.folder))) return;
    this._goneHandling.add(branch);
    const folder = task.folder;
    const name = path.basename(folder);
    try {
      // GitHub 列表说没了，再问一次 git：明确还在就等下一轮（接口延迟）；
      // 问不出来或确定没了，就删本地，不再因为 ls-remote 报错而卡住。
      try {
        const exists = await task.remoteBranchExists();
        if (exists) {
          if (confirmedGone) this.log(`GitHub 列表里没有 ${branch}，但 git 还能看到，等下一轮再确认`);
          return;
        }
      } catch (e) {
        this.log(`确认分支 ${branch} 时出错（${e.message}），按云端已删除处理`);
      }

      this._ignore.add(path.resolve(folder));
      try {
        await task.dropLocal();
      } catch (e) {
        this.log(`删除本地文件夹 ${name} 失败: ${e.message}，将重试`);
        this.setStatus('error', e.message);
        task._dropping = false;
        task.enabled = this.enabled;
        if (this.enabled && fs.existsSync(folder)) await task.start();
        return;
      }
      this.children.delete(branch);
      this.log(`云端分支 ${branch} 已被删除，已同步移除本地文件夹 ${name}`);
      this.ctx.onPersist && this.ctx.onPersist();
      this.ctx.onUpdate();
      setTimeout(() => this._ignore.delete(path.resolve(folder)), 15000);
    } finally {
      this._goneHandling.delete(branch);
    }
  }

  // 用户在根目录新建了一层文件夹 → 在 GitHub 上开一个同名分支并接管同步
  async _onFolderAdded(name) {
    if (!this.enabled) return;
    const folder = path.join(this.folder, name);
    if (!fs.existsSync(folder)) return;
    if (this._ignore.has(path.resolve(folder))) return;
    if (name.includes(ARCHIVE_SUFFIX)) return;
    if (this._creating.has(name)) return;
    if ([...this.children.values()].some(t => samePath(t.folder, folder))) return;
    if (!isValidBranchName(name)) {
      this.log(`文件夹「${name}」不能作为分支名（不能含空格和 ~ ^ : ? * [ \\ 等字符），已忽略`);
      return;
    }
    this._creating.add(name);
    this._ignore.add(path.resolve(folder));
    try {
      let remote = [];
      try { remote = await this.ctx.listBranches(this.repoFullName); } catch { /* 拿不到就当没有 */ }
      const existsRemote = remote.includes(name);
      this.log(existsRemote
        ? `检测到新文件夹 ${name}，云端已有同名分支，直接关联`
        : `检测到新文件夹 ${name}，将在 GitHub 新建分支 ${name}`);
      await this.ensureBranch(name, { initialize: true, folderName: name, createBranch: !existsRemote });
      this.setStatus('ok');
    } catch (e) {
      this.log(`根据文件夹 ${name} 创建分支失败: ${e.message}`);
      this.setStatus('error', e.message);
    } finally {
      this._creating.delete(name);
      setTimeout(() => this._ignore.delete(path.resolve(folder)), 15000);
    }
  }

  async _deleteRemoteBranch(branch, task) {
    if (this.defaultBranch && branch === this.defaultBranch) {
      const e = new Error(`「${branch}」是默认分支，GitHub 不允许删除`);
      e.code = 'PROTECTED';
      throw e;
    }
    try {
      if (this.ctx.deleteBranch) {
        await this.ctx.deleteBranch(this.repoFullName, branch);
        return;
      }
    } catch (e) {
      if (e.status === 404) return;
      if (isProtectedBranchError(e)) {
        e.code = 'PROTECTED';
        throw e;
      }
      this.log(`GitHub API 删除分支 ${branch} 失败，改用 git push --delete: ${e.message}`);
    }
    await deleteRemoteBranchViaGit(this.cloneUrl, branch, {
      token: this.ctx.getToken(),
      identity: this.ctx.getIdentity(),
      cwd: task && fs.existsSync(task.folder) ? task.folder : os.tmpdir(),
    });
  }

  async removeBranch(branch, { removeFolder = true } = {}) {
    const task = this.children.get(branch);
    if (!task) return;
    const folder = task.folder;
    this._ignore.add(path.resolve(folder));
    await task.stop();
    task.enabled = false;
    try {
      await this._deleteRemoteBranch(branch, task);
      this.log(`已删除远程分支 ${branch}`);
    } catch (e) {
      this.log(`删除远程分支 ${branch} 失败: ${e.message}`);
      // 默认分支 / 受保护分支删不掉：把本地文件夹再拉回来
      if (!fs.existsSync(folder)) {
        try {
          fs.mkdirSync(folder, { recursive: true });
          task._dropping = false;
          task._localGoneNotified = false;
          task.enabled = this.enabled;
          await task.initialize();
          if (this.enabled) await task.start();
        } catch (e2) {
          this.log(`恢复默认分支文件夹失败: ${e2.message}`);
        }
      } else if (this.enabled) {
        task._dropping = false;
        task.enabled = true;
        await task.start();
      }
      this.ctx.onUpdate();
      setTimeout(() => this._ignore.delete(path.resolve(folder)), 8000);
      throw e;
    }
    this.children.delete(branch);
    if (removeFolder) {
      try { await removeDirRetry(folder); }
      catch (e) { this.log(`删除本地文件夹 ${path.basename(folder)} 失败: ${e.message}`); }
    }
    this.ctx.onPersist && this.ctx.onPersist();
    this.ctx.onUpdate();
    setTimeout(() => this._ignore.delete(path.resolve(folder)), 8000);
  }

  async _onFolderRemoved(folderName) {
    const task = [...this.children.values()].find(t => path.basename(t.folder) === folderName);
    if (!task) return;
    const folder = task.folder;
    if (fs.existsSync(folder)) return;
    this.log(`检测到文件夹 ${folderName} 已删除，将删除远程分支 ${task.branch}`);
    try {
      await this.removeBranch(task.branch, { removeFolder: false });
    } catch (e) {
      this.setStatus('error', e.message);
    }
  }

  async reconcile() {
    if (!this.enabled) return;
    try {
      if (this.ctx.getDefaultBranch) {
        this.defaultBranch = await this.ctx.getDefaultBranch(this.repoFullName);
      }
      const remote = await this.ctx.listBranches(this.repoFullName);
      const remoteSet = new Set(remote);
      // 本地文件夹没了（watcher 可能漏事件）→ 删对应远程分支
      for (const [branch, task] of [...this.children]) {
        if (!fs.existsSync(task.folder) && !task._dropping) {
          this.log(`检查时发现 ${path.basename(task.folder)} 已不存在，删除远程分支 ${branch}`);
          try { await this.removeBranch(branch, { removeFolder: false }); }
          catch { /* removeBranch 会恢复默认分支 */ }
        }
      }
      // 云端多出来的分支 → 本地补一层文件夹
      for (const branch of remote) {
        if (!this.children.has(branch)) {
          try { await this.ensureBranch(branch, { initialize: true }); }
          catch (e) { this.log(`同步新分支 ${branch} 失败: ${e.message}`); }
        }
      }
      // 云端已经没有的分支 → 本地那层文件夹也去掉
      for (const branch of [...this.children.keys()]) {
        if (!remoteSet.has(branch)) await this.handleRemoteBranchGone(branch, { confirmedGone: true });
      }
    } catch (e) {
      this.log(`检查远程分支失败: ${e.message}`);
    }
  }

  async start() {
    if (!this.enabled) return;
    await this.stop();
    this._starting = true;
    fs.mkdirSync(this.folder, { recursive: true });

    // 应用关闭期间被删掉的分支文件夹：视为要删除对应远程分支
    for (const [branch, task] of [...this.children]) {
      if (!fs.existsSync(task.folder)) {
        this.log(`启动时发现 ${path.basename(task.folder)} 已不存在，删除远程分支 ${branch}`);
        try {
          await this.removeBranch(branch, { removeFolder: false });
        } catch {
          /* removeBranch 内部会尝试恢复默认分支 */
        }
      }
    }

    await this.reconcile();
    for (const task of this.children.values()) {
      if (fs.existsSync(task.folder)) {
        task.enabled = true;
        await task.start();
      }
    }
    this._watchRoot();
    const interval = Math.max(5, this.ctx.getPollInterval()) * 1000;
    this._timer = setInterval(() => this.requestSync('定时检查云端'), interval);
    this._starting = false;
    this.log('整仓同步已启动：一层文件夹 = 一个分支，直接在文件夹里改就行；新建文件夹＝新建分支，删除文件夹＝删除分支');
    this.setStatus('ok');
  }

  _watchRoot() {
    this._rootWatcher = chokidar.watch(this.folder, {
      ignored: GIT_DIR_RE,
      ignoreInitial: true,
      depth: 1,
      awaitWriteFinish: { stabilityThreshold: 800, pollInterval: 200 },
    });
    this._rootWatcher.on('unlinkDir', (p) => {
      if (!isDirectChildDir(this.folder, p)) return;
      if (this._ignore.has(path.resolve(p))) return;
      const name = path.basename(p);
      clearTimeout(this._pendingDeletes.get(name));
      this._pendingDeletes.set(name, setTimeout(() => this._onFolderRemoved(name), 2500));
    });
    this._rootWatcher.on('addDir', (p) => {
      if (!isDirectChildDir(this.folder, p)) return;
      const name = path.basename(p);
      clearTimeout(this._pendingDeletes.get(name));
      this._pendingDeletes.delete(name);
      if (this._ignore.has(path.resolve(p))) return;
      if ([...this.children.values()].some(t => samePath(t.folder, p))) return;
      // 等文件夹稳定下来（比如用户还在往里拖文件、或刚重命名完）再建分支
      clearTimeout(this._pendingAdds.get(name));
      this._pendingAdds.set(name, setTimeout(() => this._onFolderAdded(name), 4000));
    });
  }

  async stop() {
    const w = this._rootWatcher;
    this._rootWatcher = null;
    if (w) {
      try { await w.close(); } catch { /* ignore */ }
    }
    if (this._timer) { clearInterval(this._timer); this._timer = null; }
    for (const t of this._pendingDeletes.values()) clearTimeout(t);
    this._pendingDeletes.clear();
    for (const t of this._pendingAdds.values()) clearTimeout(t);
    this._pendingAdds.clear();
    await Promise.all([...this.children.values()].map(t => Promise.resolve(t.stop())));
  }
}

module.exports = {
  SyncEngine,
  folderNameForBranch,
  isValidBranchName,
  isRemoteRefMissing,
  isProtectedBranchError,
  removeDirRetry,
};

