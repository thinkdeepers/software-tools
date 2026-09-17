// Git 命令封装：所有仓库操作都通过系统 git 执行。
// 认证：GitHub git 协议只接受 Basic（x-access-token:TOKEN），不接受 API 用的 Bearer。
// Token 经 http.extraHeader 注入，不写入 .git/config；ASKPASS 仅作兜底。
// HTTPS 传输：git 不走 Electron 登录用的系统代理，会误报 Failed to connect；
// 因此网络类命令一律经本进程内的 CONNECT 代理转发。
const { spawn } = require('child_process');
let app;
try { ({ app } = require('electron')); } catch { app = undefined; }
const fs = require('fs');
const path = require('path');
const os = require('os');
const { getLocalProxyUrl } = require('./gitproxy');

let askpassPath = null;

function askpassDir() {
  if (process.platform === 'win32') {
    const windir = process.env.SystemRoot || process.env.WINDIR || 'C:\\Windows';
    const dir = path.join(windir, 'Temp');
    try {
      fs.mkdirSync(dir, { recursive: true });
      fs.accessSync(dir, fs.constants.W_OK);
      return dir;
    } catch { /* 用户目录可能含空格，GIT_ASKPASS 会失效；优先无空格路径 */ }
  }
  const dir = (app && typeof app.getPath === 'function') ? app.getPath('temp') : os.tmpdir();
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function ensureAskpass() {
  if (askpassPath && fs.existsSync(askpassPath)) return askpassPath;
  const dir = askpassDir();
  if (process.platform === 'win32') {
    askpassPath = path.join(dir, 'github-sync-askpass.cmd');
    // delayed expansion 避免 token 含 & | % 时被 cmd 吃掉
    fs.writeFileSync(askpassPath, '@echo off\r\nsetlocal enabledelayedexpansion\r\necho(!GIT_SYNC_TOKEN!\r\n');
  } else {
    askpassPath = path.join(dir, 'github-sync-askpass.sh');
    fs.writeFileSync(askpassPath, '#!/bin/sh\nprintf %s "$GIT_SYNC_TOKEN"\n');
    fs.chmodSync(askpassPath, 0o755);
  }
  return askpassPath;
}

// git 智能 HTTP 只要 Basic，不要把用户名写进 URL（否则会和 extraHeader 叠两份 Authorization）
function authUrl(cloneUrl) {
  try {
    const u = new URL(cloneUrl);
    if (u.protocol === 'https:' || u.protocol === 'http:') {
      u.username = '';
      u.password = '';
      return u.toString();
    }
  } catch { /* 非标准URL原样返回 */ }
  return cloneUrl;
}

function gitAuthHeader(token) {
  const basic = Buffer.from(`x-access-token:${token}`, 'utf8').toString('base64');
  return `Authorization: Basic ${basic}`;
}

function needsNetwork(args = []) {
  const list = Array.isArray(args) ? args : [];
  if (list.some(a => /^https?:\/\//i.test(String(a)))) return true;
  const urls = list.filter(a => /^[a-z][a-z0-9+.-]*:\/\//i.test(String(a)));
  if (urls.length && urls.every(u => /^(file|git):\/\//i.test(String(u)))) return false;
  const cmd = list.filter(a => a && !String(a).startsWith('-')).join(' ');
  if (/\bclone\b/.test(cmd) && !list.some(a => /^https?:\/\//i.test(String(a)))) return false;
  return /\b(fetch|push|pull|ls-remote)\b/.test(cmd);
}

function mergeGitConfigs(env, pairs) {
  let n = Number.parseInt(env.GIT_CONFIG_COUNT, 10);
  if (!Number.isFinite(n) || n < 0) n = 0;
  for (const [key, value] of pairs) {
    if (value == null) continue;
    env[`GIT_CONFIG_KEY_${n}`] = key;
    env[`GIT_CONFIG_VALUE_${n}`] = String(value);
    n += 1;
  }
  env.GIT_CONFIG_COUNT = String(n);
  return env;
}

function isTransportError(err, out = '') {
  const s = `${err || ''} ${out || ''}`;
  return /failed to connect|could not connect|could not resolve host|name or service not known|timed out|timeout after|git 超时|无进度|传输中断|connection refused|connection reset|recv failure|ssl[_\s-]*connect|empty reply from server|proxy connect|network is unreachable|no route to host|gnutls_handshake|openssl ssl_connect|failed to send request|could not handshake|error setting certificate|rpc failed|early eof|index-pack failed/i.test(s);
}

const GIT_PROGRESS_RE = /^(remote: )?(Enumerating objects|Counting objects|Compressing objects|Receiving objects|Resolving deltas|Filtering content)/i;

function stripGitProgress(text) {
  const raw = String(text || '').replace(/\r/g, '\n');
  const lines = raw.split('\n').map((l) => l.trim()).filter(Boolean);
  const kept = lines.filter((l) => !GIT_PROGRESS_RE.test(l) && !/^Cloning into /.test(l));
  return kept.join('\n').trim();
}

function looksLikeInterruptedTransfer(text) {
  const raw = String(text || '');
  return /Enumerating objects|Counting objects|Receiving objects/.test(raw) && !stripGitProgress(raw);
}

function isAuthError(err, out = '') {
  const s = `${err || ''} ${out || ''}`;
  return /invalid credentials|authentication failed|access denied|401\b|403 forbidden|repository not found/i.test(s);
}

function interpretLsRemote(r) {
  const err = (r && r.err) || '';
  const out = (r && r.out) || '';
  const code = r && r.code;
  if (code === 0) return { exists: true };
  if (isTransportError(err, out) || isAuthError(err, out)) return { error: err || out || `exit ${code}` };
  if (/couldn't find remote ref|remote ref does not exist|unresolvable reference/i.test(`${err} ${out}`)) {
    return { exists: false };
  }
  if (code === 1 || code === 2) return { exists: false };
  return { error: err || out || `exit ${code}` };
}

function formatGitFailure(what, r) {
  const raw = (r && (r.err || r.out)) || `exit ${r && r.code}`;
  const detail = stripGitProgress(raw) || raw;
  if (looksLikeInterruptedTransfer(raw)) {
    return `${what} 失败: 已连上 GitHub 并开始传输对象，但中途中断（不是认证失败）。将自动重试。`;
  }
  if (isAuthError(detail)) {
    return `${what} 失败: ${detail}\nGitHub 已连通，但 git 认证被拒绝。登录成功只说明 API Token 有效；克隆必须用 Basic 认证（x-access-token + Token），且 Token 需要该仓库的 repo / Contents 读写权限。请重新登录后再同步。`;
  }
  if (isTransportError(detail) || isTransportError(raw)) {
    return `${what} 失败: ${stripGitProgress(raw) || detail}\n这不是整机断网（否则也登录不了 GitHub）。系统 git 默认直连 github.com:443，而登录走的是软件内置网络（系统代理 / IPv4）。同一账号在多台电脑同步时，只要其中一台开了代理或 IPv6 不通，就会出现「能登录、不能克隆」。软件已让 git 改走与登录相同的网络通道；若仍失败，请确认代理软件允许访问 GitHub。`;
  }
  return `${what} 失败: ${detail}`;
}

async function buildGitEnv({ token } = {}, args = []) {
  const env = { ...process.env };
  env.GIT_TERMINAL_PROMPT = '0';
  env.GIT_ASKPASS = ensureAskpass();
  env.GIT_SYNC_TOKEN = token || '';

  const pairs = [
    ['credential.helper', ''],
    ['credential.https://github.com.helper', ''],
    ['http.version', 'HTTP/1.1'],
  ];
  if (token) {
    // 只用 Basic：Bearer 是 API 的写法，github.com 的 git 服务会回 invalid credentials
    pairs.push(['http.extraHeader', gitAuthHeader(token)]);
  }
  env.GCM_INTERACTIVE = 'never';
  if (needsNetwork(args)) {
    try {
      const proxy = await getLocalProxyUrl();
      pairs.push(['http.proxy', proxy]);
      pairs.push(['https.proxy', proxy]);
      env.HTTP_PROXY = proxy;
      env.HTTPS_PROXY = proxy;
      env.http_proxy = proxy;
      env.https_proxy = proxy;
      env.ALL_PROXY = proxy;
      env.all_proxy = proxy;
      env.NO_PROXY = '';
      env.no_proxy = '';
    } catch {
      /* 本地代理没起来就让 git 直连，错误信息仍会指出原因 */
    }
  }
  mergeGitConfigs(env, pairs);
  return env;
}

function killGit(child) {
  if (!child || child.killed || child.pid == null) return;
  try {
    if (process.platform === 'win32') {
      spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
    } else {
      child.kill('SIGTERM');
      setTimeout(() => { try { child.kill('SIGKILL'); } catch { /* ignore */ } }, 1500);
    }
  } catch {
    try { child.kill(); } catch { /* ignore */ }
  }
}

function networkTimeoutMs(args) {
  const cmd = (args || []).join(' ');
  if (/\bclone\b/.test(cmd)) return 15 * 60 * 1000;
  if (/\b(fetch|push|pull|ls-remote)\b/.test(cmd)) return 3 * 60 * 1000;
  return 60000;
}

function run(args, { cwd, token, identity, timeoutMs, stallMs, onStderr } = {}) {
  return (async () => {
    const env = await buildGitEnv({ token }, args);
    const idArgs = identity
      ? ['-c', `user.name=${identity.name}`, '-c', `user.email=${identity.email}`]
      : [];
    const net = needsNetwork(args);
    const absLimit = timeoutMs || (net ? networkTimeoutMs(args) : 60000);
    const idleLimit = stallMs != null ? stallMs : (net && /\bclone\b/.test(args.join(' ')) ? 120000 : absLimit);
    return await new Promise((resolve) => {
      const child = spawn('git', [...idArgs, ...args], { cwd, env, windowsHide: true });
      let out = '', err = '';
      let settled = false;
      let lastActivity = Date.now();
      const started = Date.now();
      const finish = (result) => {
        if (settled) return;
        settled = true;
        clearInterval(timer);
        resolve(result);
      };
      const timer = setInterval(() => {
        const now = Date.now();
        if (now - started >= absLimit) {
          killGit(child);
          finish({
            code: -1,
            out: out.trim(),
            err: (err.trim() ? err.trim() + '\n' : '') + `git 超时（${Math.round(absLimit / 1000)}s），已中止，避免一直卡在初始化`,
          });
        } else if (now - lastActivity >= idleLimit) {
          killGit(child);
          finish({
            code: -1,
            out: out.trim(),
            err: (err.trim() ? err.trim() + '\n' : '') + `git 超过 ${Math.round(idleLimit / 1000)}s 无进度，已中止（传输中断）`,
          });
        }
      }, 1000);
      const bump = () => { lastActivity = Date.now(); };
      child.stdout.on('data', d => { out += d; bump(); });
      child.stderr.on('data', d => {
        err += d;
        bump();
        if (onStderr) {
          try { onStderr(String(d)); } catch { /* ignore */ }
        }
      });
      child.on('error', e => finish({ code: -1, out, err: String(e) }));
      child.on('close', code => finish({ code, out: out.trim(), err: err.trim() }));
    });
  })();
}

async function must(args, opts, what) {
  const r = await run(args, opts);
  if (r.code !== 0) {
    throw new Error(formatGitFailure(what || 'git ' + args.join(' '), r));
  }
  return r;
}

module.exports = {
  run,
  must,
  authUrl,
  gitAuthHeader,
  ensureAskpass,
  isAuthError,
  needsNetwork,
  mergeGitConfigs,
  isTransportError,
  interpretLsRemote,
  formatGitFailure,
  stripGitProgress,
  looksLikeInterruptedTransfer,
  buildGitEnv,
};
