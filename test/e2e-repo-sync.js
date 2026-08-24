// 整仓同步端到端测试：用本地裸仓库 + test/mock-github.js 模拟 GitHub，
// 跑通「一个分支 = 一层文件夹」的全部双向场景。
//
// 用法: node test/e2e-repo-sync.js
const { execFileSync, spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');

const PORT = 3997;
const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'sync-e2e-'));
const BARE = path.join(ROOT, 'fake-remote.git');
const SEED = path.join(ROOT, 'seed');
const WORK = path.join(ROOT, 'work');   // 整仓同步的本地根目录
const PROBE = path.join(ROOT, 'probe'); // 用来模拟「别人在云端推了提交」

process.env.GITHUB_API_BASE = `http://127.0.0.1:${PORT}`;

let failures = 0;
let mock = null;

function git(cwd, ...args) {
  return execFileSync('git', args, {
    cwd,
    encoding: 'utf8',
    env: { ...process.env, GIT_AUTHOR_NAME: '测试', GIT_AUTHOR_EMAIL: 't@e.st', GIT_COMMITTER_NAME: '测试', GIT_COMMITTER_EMAIL: 't@e.st' },
  }).trim();
}

function remoteBranches() {
  return git(BARE, 'for-each-ref', '--format=%(refname:short)', 'refs/heads')
    .split('\n').filter(Boolean).sort();
}

function localFolders() {
  return fs.readdirSync(WORK).filter(n => fs.statSync(path.join(WORK, n)).isDirectory()).sort();
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// 轮询等待条件成立，比固定 sleep 稳，也让失败信息更好看
async function waitFor(desc, fn, timeoutMs = 45000) {
  const started = Date.now();
  let last = '';
  while (Date.now() - started < timeoutMs) {
    try {
      const r = await fn();
      if (r) { ok(desc); return true; }
    } catch (e) { last = e.message; }
    await sleep(500);
  }
  fail(`${desc}（超时 ${timeoutMs}ms${last ? ': ' + last : ''}）`);
  return false;
}

function ok(msg) { console.log(`  ✓ ${msg}`); }
function fail(msg) { failures++; console.log(`  ✗ ${msg}`); }
function step(msg) { console.log(`\n▶ ${msg}`); }

// ---------------- 准备远程仓库 ----------------
function seedRemote() {
  fs.mkdirSync(BARE, { recursive: true });
  git(ROOT, 'init', '--bare', '-b', 'main', BARE);

  fs.mkdirSync(SEED, { recursive: true });
  git(ROOT, 'init', '-b', 'main', SEED);
  fs.writeFileSync(path.join(SEED, 'README.md'), '# main 分支\n');
  git(SEED, 'add', '-A');
  git(SEED, 'commit', '-m', 'main 初始');
  git(SEED, 'remote', 'add', 'origin', BARE);
  git(SEED, 'push', '-u', 'origin', 'main');

  for (const b of ['dev', 'feature/x']) {
    git(SEED, 'checkout', '-b', b, 'main');
    fs.writeFileSync(path.join(SEED, 'who.txt'), `${b}\n`);
    git(SEED, 'add', '-A');
    git(SEED, 'commit', '-m', `${b} 初始`);
    git(SEED, 'push', '-u', 'origin', b);
  }
  git(SEED, 'checkout', 'main');
}

function startMock() {
  return new Promise((resolve, reject) => {
    mock = spawn(process.execPath, [path.join(__dirname, 'mock-github.js'), String(PORT), BARE], { stdio: ['ignore', 'pipe', 'inherit'] });
    mock.stdout.on('data', d => { if (String(d).includes('mock GitHub API')) resolve(); });
    mock.on('error', reject);
    setTimeout(() => reject(new Error('mock 服务启动超时')), 8000);
  });
}

// ---------------- 主流程 ----------------
async function main() {
  seedRemote();
  await startMock();

  const github = require('../src/github');
  const { SyncEngine } = require('../src/syncengine');
  const TOKEN = 'test-token';

  const engine = new SyncEngine({
    getToken: () => TOKEN,
    getIdentity: () => ({ name: '测试用户', email: 't@e.st' }),
    getPollInterval: () => 5,
    onUpdate: () => {},
    onPersist: () => {},
    log: (m) => console.log(`    · ${m}`),
    listBranches: (f) => github.listBranches(TOKEN, f),
    deleteBranch: (f, b) => github.deleteBranch(TOKEN, f, b),
    getDefaultBranch: async (f) => (await github.getRepo(TOKEN, f)).defaultBranch,
  });

  fs.mkdirSync(WORK, { recursive: true });
  const hub = engine.addFromConfig({
    id: crypto.randomUUID(),
    mode: 'repo',
    repoFullName: 'testuser/AI-pet-demo',
    cloneUrl: `file://${BARE}`,
    folder: WORK,
    defaultBranch: 'main',
    enabled: true,
    children: [],
  });

  step('1. 只选仓库 → 整个仓库同步到本地，每个分支一层文件夹');
  await hub.initialize();
  const folders = localFolders();
  folders.join(',') === 'dev,feature_x,main'
    ? ok(`本地生成 3 层分支文件夹: ${folders.join(', ')}`)
    : fail(`本地文件夹应为 dev, feature_x, main，实际为 ${folders.join(', ')}`);
  fs.readFileSync(path.join(WORK, 'dev', 'who.txt'), 'utf8').trim() === 'dev'
    ? ok('dev 文件夹里就是 dev 分支的内容')
    : fail('dev 文件夹内容不对');
  fs.readFileSync(path.join(WORK, 'feature_x', 'who.txt'), 'utf8').trim() === 'feature/x'
    ? ok('feature/x 分支落到 feature_x 文件夹')
    : fail('feature_x 文件夹内容不对');

  await hub.start();

  step('2. 直接在分支文件夹里改文件 → 自动推送到对应分支（不用新建分支）');
  fs.writeFileSync(path.join(WORK, 'dev', 'note.txt'), '在 dev 文件夹里直接改的\n');
  await waitFor('dev 分支收到了本地改动', () =>
    git(BARE, 'ls-tree', '--name-only', 'dev').split('\n').includes('note.txt'));
  git(BARE, 'ls-tree', '--name-only', 'main').split('\n').includes('note.txt')
    ? fail('改动不应该跑到 main 分支')
    : ok('main 分支没有被误改');

  step('3. 云端某分支有新提交 → 自动拉到对应文件夹');
  git(ROOT, 'clone', '--branch', 'main', BARE, PROBE);
  fs.writeFileSync(path.join(PROBE, 'from-cloud.txt'), '来自云端\n');
  git(PROBE, 'add', '-A');
  git(PROBE, 'commit', '-m', '云端提交');
  git(PROBE, 'push', 'origin', 'main');
  await waitFor('main 文件夹自动拿到了云端新文件', () =>
    fs.existsSync(path.join(WORK, 'main', 'from-cloud.txt')));

  step('4. 云端新增分支 → 本地自动多一层文件夹');
  git(SEED, 'checkout', '-b', 'release', 'main');
  fs.writeFileSync(path.join(SEED, 'who.txt'), 'release\n');
  git(SEED, 'add', '-A');
  git(SEED, 'commit', '-m', 'release 初始');
  git(SEED, 'push', '-u', 'origin', 'release');
  git(SEED, 'checkout', 'main');
  await waitFor('本地出现 release 文件夹且内容正确', () =>
    fs.readFileSync(path.join(WORK, 'release', 'who.txt'), 'utf8').trim() === 'release');

  step('5. 云端删除分支 → 本地对应文件夹自动移除（不会被推回去）');
  git(BARE, 'update-ref', '-d', 'refs/heads/feature/x');
  await waitFor('本地 feature_x 文件夹已移除', () => !fs.existsSync(path.join(WORK, 'feature_x')));
  await sleep(7000);
  remoteBranches().includes('feature/x')
    ? fail('已删除的分支不应该被本地重新推回云端')
    : ok('已删除的分支没有被重新推回云端');

  step('6. 本地新建一层文件夹 → GitHub 上自动新建同名分支');
  const NEW = path.join(WORK, 'hotfix');
  fs.mkdirSync(NEW);
  fs.writeFileSync(path.join(NEW, 'who.txt'), 'hotfix\n');
  await waitFor('云端出现 hotfix 分支', () => remoteBranches().includes('hotfix'));
  await waitFor('hotfix 分支带上了本地文件夹里的文件', () =>
    git(BARE, 'ls-tree', '--name-only', 'hotfix').split('\n').includes('who.txt'));

  step('7. 删除本地分支文件夹 → GitHub 上对应分支被删除');
  fs.rmSync(path.join(WORK, 'release'), { recursive: true, force: true });
  await waitFor('云端 release 分支已删除', () => !remoteBranches().includes('release'));

  step('8. 默认分支保护：删掉 main 文件夹不会删掉默认分支');
  fs.rmSync(path.join(WORK, 'main'), { recursive: true, force: true });
  await sleep(8000);
  remoteBranches().includes('main')
    ? ok('默认分支 main 仍然存在')
    : fail('默认分支被误删了');

  step('9. 只同步单个分支的老用法没被破坏');
  hub.stop();
  const SOLO = path.join(ROOT, 'solo');
  const solo = engine.addFromConfig({
    id: crypto.randomUUID(),
    mode: 'branch',
    repoFullName: 'testuser/AI-pet-demo',
    cloneUrl: `file://${BARE}`,
    branch: 'dev',
    folder: SOLO,
    enabled: true,
  });
  await solo.initialize({ createBranch: false, baseBranch: 'main' });
  fs.existsSync(path.join(SOLO, 'note.txt'))
    ? ok('单分支任务把 dev 分支下载到了指定文件夹')
    : fail('单分支任务没有下载到内容');
  solo.start();
  fs.writeFileSync(path.join(SOLO, 'solo.txt'), '单分支模式\n');
  await waitFor('单分支任务的本地改动推送成功', () =>
    git(BARE, 'ls-tree', '--name-only', 'dev').split('\n').includes('solo.txt'));

  step(`结果：${failures === 0 ? '全部通过' : failures + ' 项失败'}`);
  console.log(`  云端分支: ${remoteBranches().join(', ')}`);
  console.log(`  本地文件夹: ${localFolders().join(', ')}`);

  engine.stopAll();
}

main()
  .catch(e => { failures++; console.error('\n测试异常:', e); })
  .finally(() => {
    if (mock) mock.kill();
    fs.rmSync(ROOT, { recursive: true, force: true });
    process.exit(failures === 0 ? 0 : 1);
  });
