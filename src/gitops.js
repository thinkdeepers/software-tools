// Git 命令封装：所有仓库操作都通过系统 git 执行。
// 认证方式：远程 URL 内置用户名 x-access-token（无密码），
// 密码通过 GIT_ASKPASS 脚本从环境变量 GIT_SYNC_TOKEN 读取，token 不落盘到 .git/config。
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

function ensureAskpass() {
  if (askpassPath && fs.existsSync(askpassPath)) return askpassPath;
  const dir = app ? app.getPath('userData') : os.tmpdir();
  fs.mkdirSync(dir, { recursive: true });
  if (process.platform === 'win32') {
    askpassPath = path.join(dir, 'askpass.cmd');
    // delayed expansion 避免 token 含 & | % 时被 cmd 吃掉
    fs.writeFileSync(askpassPath, '@echo off\r\nsetlocal enabledelayedexpansion\r\necho(!GIT_SYNC_TOKEN!\r\n');
  } else {
    askpassPath = path.join(dir, 'askpass.sh');
    fs.writeFileSync(askpassPath, '#!/bin/sh\nprintf %s "$GIT_SYNC_TOKEN"\n');
    fs.chmodSync(askpassPath, 0o755);
  }
  return askpassPath;
}

// 把 https URL 注入用户名（token 经 askpass 提供）；file:// 等本地 URL 原样返回
function authUrl(cloneUrl) {
  try {
    const u = new URL(cloneUrl);
    if (u.protocol === 'https:') {
      u.username = 'x-access-token';
      return u.toString();
    }
  } catch { /* 非标准URL原样返回 */ }
  return cloneUrl;
}

function needsNetwork(args = []) {
  const list = Array.isArray(args) ? args : [];
  if (list.some(a => /^https?:\/\//i.test(String(a)))) return true;
  const cmd = list.filter(a => a && !String(a).startsWith('-')).join(' ');
  return /\b(clone|fetch|push|pull|ls-remote)\b/.test(cmd);
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
  return /failed to connect|could not connect|could not resolve host|name or service not known|timed out|timeout after|connection refused|connection reset|recv failure|ssl[_\s-]*connect|empty reply from server|proxy connect|network is unreachable|no route to host|gnutls_handshake|openssl ssl_connect|failed to send request|could not handshake|error setting certificate/i.test(s);
}

function interpretLsRemote(r) {
  const err = (r && r.err) || '';
  const out = (r && r.out) || '';
  const code = r && r.code;
  if (code === 0) return { exists: true };
  if (isTransportError(err, out)) return { error: err || out || `exit ${code}` };
  if (/couldn't find remote ref|remote ref does not exist|unresolvable reference/i.test(`${err} ${out}`)) {
    return { exists: false };
  }
  if (code === 1 || code === 2) return { exists: false };
  return { error: err || out || `exit ${code}` };
}

function formatGitFailure(what, r) {
  const detail = (r && (r.err || r.out)) || `exit ${r && r.code}`;
  if (isTransportError(detail)) {
    return `${what} 失败: ${detail}\n这不是整机断网（否则也登录不了 GitHub）。系统 git 默认直连 github.com:443，而登录走的是软件内置网络（系统代理 / IPv4）。同一账号在多台电脑同步时，只要其中一台开了代理或 IPv6 不通，就会出现「能登录、不能克隆」。软件已让 git 改走与登录相同的网络通道；若仍失败，请确认代理软件允许访问 GitHub。`;
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
    ['http.version', 'HTTP/1.1'],
  ];
  if (token) {
    pairs.push(['http.extraHeader', `Authorization: Bearer ${token}`]);
  }
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

function run(args, { cwd, token, identity } = {}) {
  return (async () => {
    const env = await buildGitEnv({ token }, args);
    const idArgs = identity
      ? ['-c', `user.name=${identity.name}`, '-c', `user.email=${identity.email}`]
      : [];
    return await new Promise((resolve) => {
      const child = spawn('git', [...idArgs, ...args], { cwd, env, windowsHide: true });
      let out = '', err = '';
      child.stdout.on('data', d => { out += d; });
      child.stderr.on('data', d => { err += d; });
      child.on('error', e => resolve({ code: -1, out, err: String(e) }));
      child.on('close', code => resolve({ code, out: out.trim(), err: err.trim() }));
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
  ensureAskpass,
  needsNetwork,
  mergeGitConfigs,
  isTransportError,
  interpretLsRemote,
  formatGitFailure,
  buildGitEnv,
};
