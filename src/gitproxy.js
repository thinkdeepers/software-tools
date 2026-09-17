// 让系统 git 走与 Electron 登录相同的网络通道。
// 登录用 Chromium fetch（自动系统代理 + Happy Eyeballs），git/libcurl 默认直连，
// 于是会出现「能登录、克隆却 Failed to connect to github.com:443」。
const http = require('http');
const net = require('net');
const { execFileSync } = require('child_process');

const WELL_KNOWN_PROXIES = [
  'http://127.0.0.1:7890',
  'http://127.0.0.1:10809',
  'http://127.0.0.1:6152',
  'socks5h://127.0.0.1:7891',
  'socks5h://127.0.0.1:10808',
];

function parseProxySpec(spec) {
  const first = String(spec || '').split(';')[0].trim();
  if (!first || /^DIRECT$/i.test(first)) return null;
  const m = first.match(/^(PROXY|HTTPS|HTTP|SOCKS5|SOCKS4A|SOCKS4|SOCKS)\s+(\S+)/i);
  if (!m) return null;
  const kind = m[1].toUpperCase();
  const addr = m[2].replace(/^https?:\/\//i, '');
  if (kind.startsWith('SOCKS')) {
    const proto = kind === 'SOCKS4' || kind === 'SOCKS4A' ? 'socks4' : 'socks5h';
    return normalizeProxyUrl(addr, proto);
  }
  return normalizeProxyUrl(addr, 'http');
}

function normalizeProxyUrl(addr, fallbackProto = 'http') {
  let a = String(addr || '').trim();
  if (!a) return null;
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(a)) a = `${fallbackProto}://${a}`;
  let u;
  try { u = new URL(a.replace(/^socks5h/i, 'socks5')); } catch { return null; }
  if (!u.hostname) return null;
  const rawProto = a.split(':')[0].toLowerCase();
  let proto = fallbackProto;
  if (rawProto === 'socks4' || rawProto === 'socks4a') proto = 'socks4';
  else if (rawProto.startsWith('socks')) proto = 'socks5h';
  else proto = 'http';
  const port = u.port || (proto === 'http' ? '80' : '1080');
  const auth = u.username
    ? `${encodeURIComponent(decodeURIComponent(u.username))}:${encodeURIComponent(decodeURIComponent(u.password || ''))}@`
    : '';
  return `${proto}://${auth}${u.hostname}:${port}`;
}

function parseWindowsProxyServer(raw) {
  const s = String(raw || '').trim().replace(/^["']|["']$/g, '');
  if (!s) return null;
  if (!/[=;]/.test(s)) return normalizeProxyUrl(s, 'http');
  const map = {};
  for (const part of s.split(';')) {
    const bit = part.trim();
    if (!bit) continue;
    const eq = bit.indexOf('=');
    if (eq === -1) {
      map.http = bit;
      continue;
    }
    map[bit.slice(0, eq).trim().toLowerCase()] = bit.slice(eq + 1).trim();
  }
  if (map.https) return normalizeProxyUrl(map.https, 'http');
  if (map.http) return normalizeProxyUrl(map.http, 'http');
  if (map.socks) return normalizeProxyUrl(map.socks, 'socks5h');
  return null;
}

function envProxy(env = process.env) {
  for (const k of ['HTTPS_PROXY', 'https_proxy', 'HTTP_PROXY', 'http_proxy', 'ALL_PROXY', 'all_proxy']) {
    const v = env[k] && String(env[k]).trim();
    if (v) return normalizeProxyUrl(v, /^socks/i.test(v) ? 'socks5h' : 'http');
  }
  return null;
}

function readWindowsSystemProxy() {
  if (process.platform !== 'win32') return null;
  try {
    const out = execFileSync('reg', [
      'query',
      'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings',
    ], { encoding: 'utf8', windowsHide: true, timeout: 3000 });
    if (!/ProxyEnable\s+REG_DWORD\s+0x1\b/i.test(out)) return null;
    const m = out.match(/ProxyServer\s+REG_SZ\s+(\S+)/i);
    if (!m) return null;
    return parseWindowsProxyServer(m[1]);
  } catch {
    return null;
  }
}

function getElectronSession() {
  try {
    const electron = require('electron');
    if (!electron || typeof electron !== 'object') return null;
    return electron.session || null;
  } catch {
    return null;
  }
}

async function resolveElectronProxy(targetUrl) {
  const session = getElectronSession();
  if (!session || !session.defaultSession || typeof session.defaultSession.resolveProxy !== 'function') {
    return null;
  }
  try {
    const spec = await session.defaultSession.resolveProxy(targetUrl);
    return parseProxySpec(spec);
  } catch {
    return null;
  }
}

function defaultAllowHost(host) {
  const h = String(host || '').toLowerCase().replace(/\.$/, '');
  return /(^|\.)github\.com$/.test(h) || /(^|\.)githubusercontent\.com$/.test(h);
}

function parseConnectTarget(raw, defaultPort = 443) {
  let s = String(raw || '').trim();
  if (!s) return { host: '', port: defaultPort };
  try {
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(s)) {
      const u = new URL(s);
      return { host: u.hostname, port: Number(u.port) || defaultPort };
    }
  } catch { /* fall through */ }
  if (s.startsWith('[')) {
    const m = s.match(/^\[([^\]]+)\](?::(\d+))?$/);
    if (m) return { host: m[1], port: Number(m[2]) || defaultPort };
  }
  const idx = s.lastIndexOf(':');
  if (idx > 0 && s.indexOf(':') === idx) {
    return { host: s.slice(0, idx), port: Number(s.slice(idx + 1)) || defaultPort };
  }
  return { host: s, port: defaultPort };
}

function connectSocket(options) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(options);
    const fail = (e) => { try { sock.destroy(); } catch { /* ignore */ } reject(e || new Error('connect failed')); };
    sock.setTimeout(12000);
    sock.once('connect', () => { sock.setTimeout(0); resolve(sock); });
    sock.once('error', fail);
    sock.once('timeout', () => fail(new Error('connect timeout')));
  });
}

async function connectDirectPreferIPv4(host, port) {
  const opts = { host, port };
  if (typeof net.setDefaultAutoSelectFamily === 'function') {
    opts.autoSelectFamily = true;
    opts.autoSelectFamilyAttemptTimeout = 250;
  } else {
    opts.family = 4;
  }
  try {
    return await connectSocket(opts);
  } catch (e) {
    if (opts.family === 4) return connectSocket({ host, port });
    throw e;
  }
}

function httpProxyConnect(proxyUrl, destHost, destPort) {
  const u = new URL(proxyUrl);
  const port = Number(u.port) || 80;
  return new Promise((resolve, reject) => {
    const sock = net.connect({ host: u.hostname, port, family: 4 }, () => {
      const auth = u.username
        ? `Proxy-Authorization: Basic ${Buffer.from(`${decodeURIComponent(u.username)}:${decodeURIComponent(u.password || '')}`).toString('base64')}\r\n`
        : '';
      sock.write(`CONNECT ${destHost}:${destPort} HTTP/1.1\r\nHost: ${destHost}:${destPort}\r\n${auth}\r\n`);
    });
    let buf = Buffer.alloc(0);
    const fail = (e) => { try { sock.destroy(); } catch { /* ignore */ } reject(e); };
    sock.setTimeout(15000);
    sock.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      const idx = buf.indexOf('\r\n\r\n');
      if (idx === -1) return;
      sock.removeAllListeners('data');
      sock.setTimeout(0);
      const head = buf.slice(0, idx).toString('utf8');
      const rest = buf.slice(idx + 4);
      if (!/^HTTP\/1\.\d 200/i.test(head)) {
        fail(new Error(`proxy CONNECT ${u.hostname}:${port} -> ${head.split('\r\n')[0]}`));
        return;
      }
      if (rest.length) sock.unshift(rest);
      resolve(sock);
    });
    sock.once('error', fail);
    sock.once('timeout', () => fail(new Error('proxy CONNECT timeout')));
  });
}

function socks5Connect(proxyUrl, destHost, destPort) {
  const u = new URL(String(proxyUrl).replace(/^socks5h/i, 'socks5'));
  const port = Number(u.port) || 1080;
  const user = decodeURIComponent(u.username || '');
  const pass = decodeURIComponent(u.password || '');
  return new Promise((resolve, reject) => {
    const sock = net.connect({ host: u.hostname, port, family: 4 });
    let stage = user ? 'greet-auth' : 'greet';
    const fail = (e) => { try { sock.destroy(); } catch { /* ignore */ } reject(e); };
    sock.setTimeout(15000);
    sock.on('connect', () => {
      if (user) sock.write(Buffer.from([0x05, 0x01, 0x02]));
      else sock.write(Buffer.from([0x05, 0x01, 0x00]));
    });
    sock.on('data', (d) => {
      try {
        if (stage === 'greet' || stage === 'greet-auth') {
          if (d.length < 2 || d[0] !== 0x05) throw new Error('bad SOCKS greeting');
          if (d[1] === 0x02 && user) {
            const ub = Buffer.from(user);
            const pb = Buffer.from(pass);
            sock.write(Buffer.concat([Buffer.from([0x01, ub.length]), ub, Buffer.from([pb.length]), pb]));
            stage = 'auth';
            return;
          }
          if (d[1] !== 0x00) throw new Error('SOCKS auth required');
          stage = 'req';
        } else if (stage === 'auth') {
          if (d[1] !== 0x00) throw new Error('SOCKS user/pass failed');
          stage = 'req';
        }
        if (stage === 'req') {
          const hostBuf = Buffer.from(destHost);
          const req = Buffer.concat([
            Buffer.from([0x05, 0x01, 0x00, 0x03, hostBuf.length]),
            hostBuf,
            Buffer.from([(destPort >> 8) & 0xff, destPort & 0xff]),
          ]);
          sock.write(req);
          stage = 'reply';
          return;
        }
        if (stage === 'reply') {
          if (d.length < 2 || d[1] !== 0x00) throw new Error(`SOCKS connect failed (${d[1]})`);
          sock.removeAllListeners('data');
          sock.setTimeout(0);
          resolve(sock);
        }
      } catch (e) {
        fail(e);
      }
    });
    sock.once('error', fail);
    sock.once('timeout', () => fail(new Error('SOCKS connect timeout')));
  });
}

async function connectViaProxy(proxyUrl, destHost, destPort) {
  const proto = String(proxyUrl).split(':')[0].toLowerCase();
  if (proto.startsWith('socks')) return socks5Connect(proxyUrl, destHost, destPort);
  return httpProxyConnect(proxyUrl, destHost, destPort);
}

function tcpOpen(host, port, ms = 250) {
  return new Promise((resolve) => {
    const sock = net.connect({ host, port, family: 4 });
    const done = (ok) => { try { sock.destroy(); } catch { /* ignore */ } resolve(ok); };
    sock.setTimeout(ms);
    sock.once('connect', () => done(true));
    sock.once('timeout', () => done(false));
    sock.once('error', () => done(false));
  });
}

async function probeWellKnownProxies(exceptPort) {
  const checks = WELL_KNOWN_PROXIES.map(async (url) => {
    try {
      const u = new URL(url.replace(/^socks5h/i, 'http'));
      const port = Number(u.port);
      if (exceptPort && port === exceptPort) return null;
      if (await tcpOpen(u.hostname, port, 200)) return url;
    } catch { /* ignore */ }
    return null;
  });
  return (await Promise.all(checks)).filter(Boolean);
}

class LocalGitProxy {
  constructor(opts = {}) {
    this.allowHost = opts.allowHost || defaultAllowHost;
    this.resolveUpstream = opts.resolveUpstream || null;
    this.server = null;
    this.port = 0;
    this._start = null;
  }

  url() {
    if (!this.port) throw new Error('local git proxy not started');
    return `http://127.0.0.1:${this.port}`;
  }

  start() {
    if (this.port) return Promise.resolve(this.url());
    if (this._start) return this._start;
    this._start = new Promise((resolve, reject) => {
      this.server = http.createServer((_req, res) => {
        res.writeHead(405);
        res.end();
      });
      this.server.on('connect', (req, clientSocket, head) => {
        this._onConnect(req, clientSocket, head);
      });
      this.server.on('error', reject);
      this.server.listen(0, '127.0.0.1', () => {
        this.port = this.server.address().port;
        resolve(this.url());
      });
    });
    return this._start;
  }

  async close() {
    const s = this.server;
    this.server = null;
    this.port = 0;
    this._start = null;
    if (!s) return;
    await new Promise((resolve) => s.close(() => resolve()));
  }

  async _onConnect(req, clientSocket, head) {
    const { host, port } = parseConnectTarget(req.url, 443);
    const fail = (msg) => {
      try { clientSocket.write(`HTTP/1.1 502 Bad Gateway\r\n\r\n${msg || ''}`); } catch { /* ignore */ }
      try { clientSocket.destroy(); } catch { /* ignore */ }
    };
    if (!host || !this.allowHost(host)) {
      fail('host not allowed');
      return;
    }
    try {
      const dest = await this._connectOutbound(host, port);
      clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (head && head.length) dest.write(head);
      dest.pipe(clientSocket);
      clientSocket.pipe(dest);
      const hangup = () => { try { dest.destroy(); } catch { /* ignore */ } try { clientSocket.destroy(); } catch { /* ignore */ } };
      dest.on('error', hangup);
      clientSocket.on('error', hangup);
    } catch (e) {
      fail(e.message);
    }
  }

  async _connectOutbound(host, port) {
    const errors = [];
    const tryOne = (proxy) => {
      if (!proxy) return connectDirectPreferIPv4(host, port);
      return connectViaProxy(proxy, host, port);
    };

    const detected = this.resolveUpstream
      ? await this.resolveUpstream(`https://${host}/`)
      : await detectUpstreamProxy(`https://${host}/`, this.port);

    const first = [];
    if (detected) first.push(detected);
    first.push(null);

    for (const proxy of first) {
      try {
        return await tryOne(proxy);
      } catch (e) {
        errors.push(`${proxy || 'direct'}: ${e.message}`);
      }
    }

    for (const p of await probeWellKnownProxies(this.port)) {
      if (p === detected) continue;
      try {
        return await tryOne(p);
      } catch (e) {
        errors.push(`${p}: ${e.message}`);
      }
    }
    throw new Error(errors.join('; ') || `cannot reach ${host}:${port}`);
  }
}

let cachedUpstream = undefined;
let cachedAt = 0;
const UPSTREAM_TTL = 20000;

async function detectUpstreamProxy(targetUrl, exceptPort) {
  if (cachedUpstream !== undefined && Date.now() - cachedAt < UPSTREAM_TTL) return cachedUpstream;
  let found = await resolveElectronProxy(targetUrl);
  if (!found) found = envProxy(process.env);
  if (!found) found = readWindowsSystemProxy();
  if (found && exceptPort) {
    try {
      const u = new URL(found.replace(/^socks5h/i, 'http'));
      if (u.hostname === '127.0.0.1' && Number(u.port) === exceptPort) found = null;
    } catch { /* ignore */ }
  }
  cachedUpstream = found || null;
  cachedAt = Date.now();
  return cachedUpstream;
}

let singleton = null;

async function getLocalProxyUrl() {
  if (!singleton) singleton = new LocalGitProxy();
  return singleton.start();
}

function resetProxyCache() {
  cachedUpstream = undefined;
  cachedAt = 0;
}

module.exports = {
  LocalGitProxy,
  parseProxySpec,
  parseWindowsProxyServer,
  parseConnectTarget,
  normalizeProxyUrl,
  envProxy,
  defaultAllowHost,
  detectUpstreamProxy,
  getLocalProxyUrl,
  resetProxyCache,
};
