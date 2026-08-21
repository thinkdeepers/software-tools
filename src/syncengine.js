// 双向同步引擎：
// 本地方向：chokidar 监听文件变动 → 防抖 → 自动 commit + push
// 云端方向：定时 fetch → 有新提交则 merge 到本地
// 冲突：中止合并，任务进入"冲突"状态等用户选择（以本地为准 / 以云端为准）
const chokidar = require('chokidar');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { run, must, authUrl } = require('./gitops');

const GIT_DIR_RE = /(^|[/\\])\.git([/\\]|$)/;

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
    if (!this.enabled || this.status === 'conflict' || this.status === 'init') return;
    this._chain = this._chain.then(() => this._cycle(reason)).catch(() => {});
    return this._chain;
  }

  async _cycle(reason) {
    if (!this.enabled || this.status === 'conflict') return;
    this.setStatus('syncing');
    try {
      // 1. 本地变动 → 提交
      await run(['add', '-A'], this.opts());
      const staged = await run(['diff', '--cached', '--quiet'], this.opts());
      if (staged.code === 1) {
        const msg = `自动同步: ${new Date().toLocaleString('zh-CN')}`;
        await must(['commit', '-m', msg], this.opts(), '提交本地变动');
        this.log(`已提交本地变动（${reason}）`);
      }

      // 2. 拉取远程
      const f = await run(['fetch', 'origin', `+refs/heads/${this.branch}:refs/remotes/origin/${this.branch}`], this.opts());
      const remoteExists = f.code === 0;

      if (remoteExists) {
        const cnt = await must(['rev-list', '--left-right', '--count', `HEAD...origin/${this.branch}`], this.opts(), '比较进度');
        let [ahead, behind] = cnt.out.split(/\s+/).map(Number);

        // 3. 云端有新提交 → 合并到本地
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

        // 4. 本地领先 → 推送
        if (ahead > 0) {
          const p = await run(['push', 'origin', `HEAD:${this.branch}`], this.opts());
          if (p.code !== 0) throw new Error(`推送失败: ${p.err}`);
          this.log(`已推送 ${ahead} 个提交到云端`);
        }
      } else {
        // 远程分支不存在（可能被删）：重新推送创建
        const p = await run(['push', '-u', 'origin', this.branch], this.opts());
        if (p.code !== 0) throw new Error(`推送失败: ${p.err}`);
        this.log('远程分支不存在，已重新创建并推送');
      }

      this.lastSync = new Date().toISOString();
      this.setStatus('ok');
    } catch (e) {
      this.log(`同步出错: ${e.message}`);
      this.setStatus('error', e.message);
    }
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
  start() {
    if (!this.enabled) return;
    if (!fs.existsSync(this.folder)) {
      this.log('本地文件夹不存在，已停止');
      this.setStatus('error', '本地文件夹不存在');
      return;
    }
    this.stop();
    this._watcher = chokidar.watch(this.folder, {
      ignored: GIT_DIR_RE,
      ignoreInitial: true,
      awaitWriteFinish: { stabilityThreshold: 800, pollInterval: 200 },
    });
    this._watcher.on('all', () => {
      clearTimeout(this._debounce);
      this._debounce = setTimeout(() => this.requestSync('本地文件变动'), 2500);
    });
    const interval = Math.max(5, this.ctx.getPollInterval()) * 1000;
    this._timer = setInterval(() => this.requestSync('定时检查云端'), interval);
    this.log('同步已启动（监听本地变动 + 定时检查云端）');
    this.requestSync('启动检查');
  }

  stop() {
    if (this._watcher) { this._watcher.close(); this._watcher = null; }
    if (this._timer) { clearInterval(this._timer); this._timer = null; }
    clearTimeout(this._debounce);
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
    this.tasks.set(data.id, task);
    return task;
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
    if (t) { t.stop(); this.tasks.delete(id); return; }
    const h = this.hubs.get(id);
    if (h) { h.stop(); this.hubs.delete(id); }
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

  stopAll() {
    for (const h of this.hubs.values()) h.stop();
    for (const t of this.tasks.values()) t.stop();
  }

  restartAll() {
    for (const h of this.hubs.values()) {
      if (h.enabled) Promise.resolve(h.start()).catch(e => this.ctx.log(`整仓启动失败: ${e.message}`));
    }
    for (const t of this.tasks.values()) if (t.enabled) t.start();
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
    this._ignore = new Set();
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

  async ensureBranch(branch, { initialize = false } = {}) {
    if (this.children.has(branch)) {
      const t = this.children.get(branch);
      if (!fs.existsSync(path.join(t.folder, '.git'))) {
        fs.mkdirSync(t.folder, { recursive: true });
        await t.initialize();
      }
      if (this.enabled && this._rootWatcher) t.start();
      return t;
    }
    const folderName = folderNameForBranch(branch, this.takenFolderNames());
    const folder = path.join(this.folder, folderName);
    this._ignore.add(path.resolve(folder));
    const task = this._attachChild({
      id: crypto.randomUUID(),
      branch,
      folderName,
    });
    try {
      if (initialize || !fs.existsSync(folder) || !fs.existsSync(path.join(folder, '.git'))) {
        fs.mkdirSync(folder, { recursive: true });
        await task.initialize();
      }
      if (this.enabled && this._rootWatcher) task.start();
      this.log(`已加入分支 ${branch} → 文件夹 ${folderName}`);
      this.ctx.onPersist && this.ctx.onPersist();
      this.ctx.onUpdate();
      return task;
    } catch (e) {
      task.stop();
      this.children.delete(branch);
      throw e;
    } finally {
      setTimeout(() => this._ignore.delete(path.resolve(folder)), 8000);
    }
  }

  async _deleteRemoteBranch(branch) {
    if (this.defaultBranch && branch === this.defaultBranch) {
      throw new Error(`「${branch}」是默认分支，GitHub 不允许删除`);
    }
    if (this.ctx.deleteBranch) {
      await this.ctx.deleteBranch(this.repoFullName, branch);
    } else {
      throw new Error('当前环境无法删除远程分支');
    }
  }

  async removeBranch(branch, { removeFolder = true } = {}) {
    const task = this.children.get(branch);
    if (!task) return;
    const folder = task.folder;
    this._ignore.add(path.resolve(folder));
    task.stop();
    try {
      await this._deleteRemoteBranch(branch);
      this.log(`已删除远程分支 ${branch}`);
    } catch (e) {
      this.log(`删除远程分支 ${branch} 失败: ${e.message}`);
      // 默认分支等删不掉：把本地文件夹再拉回来
      if (!fs.existsSync(folder)) {
        try {
          fs.mkdirSync(folder, { recursive: true });
          await task.initialize();
          if (this.enabled) task.start();
        } catch (e2) {
          this.log(`恢复默认分支文件夹失败: ${e2.message}`);
        }
      } else if (this.enabled) {
        task.start();
      }
      this.ctx.onUpdate();
      setTimeout(() => this._ignore.delete(path.resolve(folder)), 8000);
      throw e;
    }
    this.children.delete(branch);
    if (removeFolder && fs.existsSync(folder)) {
      fs.rmSync(folder, { recursive: true, force: true });
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
      for (const branch of remote) {
        if (!this.children.has(branch)) {
          try { await this.ensureBranch(branch, { initialize: true }); }
          catch (e) { this.log(`同步新分支 ${branch} 失败: ${e.message}`); }
        }
      }
      // 本地文件夹还在、但启动时已处理过「文件夹被删」；这里不因远程少了分支而删本地
      void remoteSet;
    } catch (e) {
      this.log(`检查远程分支失败: ${e.message}`);
    }
  }

  async start() {
    if (!this.enabled) return;
    this.stop();
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
        task.start();
      }
    }
    this._watchRoot();
    const interval = Math.max(5, this.ctx.getPollInterval()) * 1000;
    this._timer = setInterval(() => {
      this.reconcile();
      this.requestSync('定时检查云端');
    }, interval);
    this._starting = false;
    this.log('整仓同步已启动：一层文件夹 = 一个分支；删除文件夹即删除该分支');
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
    });
  }

  stop() {
    if (this._rootWatcher) { this._rootWatcher.close(); this._rootWatcher = null; }
    if (this._timer) { clearInterval(this._timer); this._timer = null; }
    for (const t of this._pendingDeletes.values()) clearTimeout(t);
    this._pendingDeletes.clear();
    for (const t of this.children.values()) t.stop();
  }
}

module.exports = { SyncEngine, folderNameForBranch };

