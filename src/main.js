const { app, BrowserWindow, ipcMain, dialog, shell, Tray, Menu, nativeImage } = require('electron');
const path = require('path');
const crypto = require('crypto');
const store = require('./store');
const github = require('./github');
const { SyncEngine } = require('./syncengine');

let win = null;
let tray = null;
let isQuitting = false;
let trayTipShown = false;
let cfg = null;
let user = null; // {login, name, email}
let engine = null;
const logs = [];

function log(msg) {
  const line = `${new Date().toLocaleTimeString('zh-CN')} ${msg}`;
  logs.push(line);
  if (logs.length > 300) logs.shift();
  broadcast();
}

function state() {
  return {
    user,
    mappings: engine ? engine.views() : [],
    logs: logs.slice(-100),
    settings: cfg.settings,
  };
}

function broadcast() {
  if (win && !win.isDestroyed()) win.webContents.send('state', state());
}

function makeEngine() {
  engine = new SyncEngine({
    getToken: () => store.getToken(cfg),
    getIdentity: () => ({
      name: (user && (user.name || user.login)) || 'GitHub同步助手',
      email: (user && (user.email || `${user.login}@users.noreply.github.com`)) || 'sync@local',
    }),
    getPollInterval: () => Number(process.env.SYNC_POLL_SEC) || cfg.settings.pollIntervalSec || 30,
    onUpdate: broadcast,
    onPersist: persistMappings,
    log,
    listBranches: (fullName) => github.listBranches(store.getToken(cfg), fullName),
    deleteBranch: (fullName, branch) => github.deleteBranch(store.getToken(cfg), fullName, branch),
    getDefaultBranch: async (fullName) => {
      const r = await github.getRepo(store.getToken(cfg), fullName);
      return r.defaultBranch;
    },
  });
  for (const m of cfg.mappings) engine.addFromConfig(m);
}

function persistMappings() {
  if (!engine) return;
  cfg.mappings = engine.serialize();
  store.save(cfg);
}

function foldersOverlap(a, b) {
  const A = path.resolve(a);
  const B = path.resolve(b);
  const sep = path.sep;
  const eq = process.platform === 'win32'
    ? (x, y) => x.toLowerCase() === y.toLowerCase()
    : (x, y) => x === y;
  const starts = process.platform === 'win32'
    ? (x, y) => x.toLowerCase().startsWith(y.toLowerCase())
    : (x, y) => x.startsWith(y);
  return eq(A, B) || starts(A, B + sep) || starts(B, A + sep);
}

async function tryAutoLogin() {
  const token = store.getToken(cfg);
  if (!token) return;
  try {
    user = await github.getUser(token);
    log(`已登录: ${user.login}`);
    engine.restartAll();
  } catch {
    log('保存的登录凭据已失效，请重新登录');
  }
}

const trayIcon = () => nativeImage.createFromPath(path.join(__dirname, '..', 'assets', 'tray.png'));

function showWindow() {
  if (!win || win.isDestroyed()) { createWindow(); return; }
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function quitApp() {
  isQuitting = true;
  if (engine) engine.stopAll();
  app.quit();
}

function createTray() {
  tray = new Tray(trayIcon());
  tray.setToolTip('GitHub同步助手（后台自动同步中）');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '显示主界面', click: showWindow },
    { label: '全部立即同步', click: () => { if (engine) for (const t of engine.tasks.values()) t.requestSync('托盘手动同步'); } },
    { type: 'separator' },
    {
      label: '开机自动启动',
      type: 'checkbox',
      checked: app.getLoginItemSettings().openAtLogin,
      click: (item) => app.setLoginItemSettings({ openAtLogin: item.checked }),
    },
    { type: 'separator' },
    { label: '退出', click: quitApp },
  ]));
  tray.on('click', showWindow);
  tray.on('double-click', showWindow);
}

function createWindow() {
  win = new BrowserWindow({
    width: 1120,
    height: 740,
    minWidth: 900,
    minHeight: 600,
    title: 'GitHub同步助手',
    icon: trayIcon(),
    backgroundColor: '#0d1117',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  win.removeMenu();
  win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  win.webContents.on('did-finish-load', broadcast);

  // 点关闭按钮 → 隐藏到系统托盘继续后台同步，不退出
  win.on('close', (e) => {
    if (isQuitting) return;
    e.preventDefault();
    win.hide();
    if (!trayTipShown && tray && process.platform === 'win32') {
      trayTipShown = true;
      try {
        tray.displayBalloon({
          title: 'GitHub同步助手仍在运行',
          content: '已最小化到右下角托盘，同步继续进行。右键托盘图标可退出。',
          iconType: 'info',
        });
      } catch { /* 部分系统不支持气泡通知 */ }
    }
  });
}

// ---------------- IPC ----------------
ipcMain.handle('get-state', () => state());

ipcMain.handle('login', async (_e, token) => {
  token = String(token || '').trim();
  if (!token) throw new Error('请输入 Token');
  user = await github.getUser(token); // 无效会抛错
  store.setToken(cfg, token);
  log(`登录成功: ${user.login}`);
  engine.restartAll();
  broadcast();
  return user;
});

ipcMain.handle('logout', () => {
  engine.stopAll();
  user = null;
  store.setToken(cfg, null);
  log('已退出登录');
  broadcast();
});

ipcMain.handle('list-repos', () => github.listRepos(store.getToken(cfg)));
ipcMain.handle('list-branches', (_e, fullName) => github.listBranches(store.getToken(cfg), fullName));

ipcMain.handle('pick-folder', async () => {
  const r = await dialog.showOpenDialog(win, {
    title: '选择本地文件夹',
    properties: ['openDirectory', 'createDirectory'],
  });
  return r.canceled ? null : r.filePaths[0];
});

ipcMain.handle('add-mapping', async (_e, payload) => {
  const { repoFullName, cloneUrl, branch, folder, createBranch, baseBranch, mode, defaultBranch } = payload;
  const isRepo = mode === 'repo';
  for (const m of cfg.mappings) {
    if (foldersOverlap(m.folder, folder)) throw new Error('该文件夹已绑定其他同步任务，或与现有任务目录重叠');
    if (isRepo) {
      if (m.repoFullName === repoFullName) throw new Error('该仓库已有同步任务，请先删除后再整仓同步');
    } else {
      if (m.mode === 'repo' && m.repoFullName === repoFullName) throw new Error('该仓库已在整仓同步中，无需再单独绑定分支');
      if (m.repoFullName === repoFullName && m.branch === branch) throw new Error('该分支已绑定其他文件夹');
    }
  }
  const data = isRepo
    ? {
      id: crypto.randomUUID(),
      mode: 'repo',
      repoFullName, cloneUrl, folder,
      defaultBranch: defaultBranch || baseBranch || null,
      enabled: true,
      children: [],
    }
    : {
      id: crypto.randomUUID(),
      mode: 'branch',
      repoFullName, cloneUrl, branch, folder,
      enabled: true,
    };
  const task = engine.addFromConfig(data);
  broadcast();
  try {
    await task.initialize(isRepo ? undefined : { createBranch, baseBranch });
  } catch (e) {
    engine.removeTask(data.id);
    broadcast();
    throw e;
  }
  persistMappings();
  await task.start();
  broadcast();
  return task.view();
});

ipcMain.handle('remove-mapping', (_e, id) => {
  engine.removeTask(id);
  persistMappings();
  log('已删除同步任务（本地文件不会被删除）');
  broadcast();
});

ipcMain.handle('delete-repo-branch', async (_e, childId) => {
  for (const h of engine.hubs.values()) {
    const child = h.childById(childId);
    if (!child) continue;
    await h.removeBranch(child.branch, { removeFolder: true });
    persistMappings();
    broadcast();
    return true;
  }
  throw new Error('未找到该分支同步');
});

ipcMain.handle('toggle-mapping', (_e, id, enabled) => {
  const t = engine.get(id);
  if (!t) return;
  t.enabled = enabled;
  const saved = cfg.mappings.find(m => m.id === id);
  if (saved) { saved.enabled = enabled; }
  persistMappings();
  if (enabled) {
    if (typeof t.setStatus === 'function') t.setStatus('idle');
    t.start();
  } else {
    t.stop();
    if (typeof t.setStatus === 'function') t.setStatus('paused');
  }
  broadcast();
});

ipcMain.handle('sync-now', (_e, id) => {
  const t = engine.get(id);
  if (t) t.requestSync('手动同步');
});

ipcMain.handle('resolve-conflict', (_e, id, strategy) => {
  const t = engine.get(id);
  if (t) return t.resolveConflict(strategy);
});

ipcMain.handle('open-folder', (_e, id) => {
  const t = engine.get(id);
  if (t) shell.openPath(t.folder);
});

ipcMain.handle('set-settings', (_e, settings) => {
  cfg.settings = { ...cfg.settings, ...settings };
  store.save(cfg);
  engine.stopAll();
  engine.restartAll();
  broadcast();
});

ipcMain.handle('open-token-page', () => {
  shell.openExternal('https://github.com/settings/tokens/new?scopes=repo&description=GitHub%E5%90%8C%E6%AD%A5%E5%8A%A9%E6%89%8B');
});

// ---------------- 生命周期 ----------------
// 单实例锁：重复启动时聚焦已有窗口
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', showWindow);
}

app.whenReady().then(async () => {
  cfg = store.load();
  makeEngine();
  createTray();
  createWindow();
  await tryAutoLogin();
});

app.on('before-quit', () => { isQuitting = true; });

// 窗口全部关闭时不退出：保持托盘常驻，后台继续同步
app.on('window-all-closed', () => {});
