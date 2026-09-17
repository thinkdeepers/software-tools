// 传输层：系统代理解析、GIT_CONFIG 合并、本地 CONNECT 代理、克隆失败分类
const net = require('net');
const { parseProxySpec, parseWindowsProxyServer, parseConnectTarget, LocalGitProxy, defaultAllowHost } = require('../src/gitproxy');
const { mergeGitConfigs, needsNetwork, isTransportError, isAuthError, interpretLsRemote, formatGitFailure, gitAuthHeader, authUrl, buildGitEnv } = require('../src/gitops');
const { isRemoteRefMissing } = require('../src/syncengine');

let failures = 0;
function ok(msg) { console.log(`  ✓ ${msg}`); }
function fail(msg) { failures++; console.log(`  ✗ ${msg}`); }
function eq(actual, expected, msg) {
  if (actual === expected) ok(msg);
  else fail(`${msg}（期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}）`);
}

const USER_ERR = "Cloning into '.'... fatal: unable to access 'https://github.com/thinkdeepers/houbeililiang.git/': Failed to connect to github.com:443 after 21105 ms: Could not connect to server";

function step(msg) { console.log(`\n▶ ${msg}`); }

function httpConnect(proxyUrl, destHost, destPort) {
  return new Promise((resolve, reject) => {
    const u = new URL(proxyUrl);
    const sock = net.connect({ host: u.hostname, port: Number(u.port) }, () => {
      sock.write(`CONNECT ${destHost}:${destPort} HTTP/1.1\r\nHost: ${destHost}:${destPort}\r\n\r\n`);
    });
    let buf = Buffer.alloc(0);
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('CONNECT timeout')); }, 5000);
    sock.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      const idx = buf.indexOf('\r\n\r\n');
      if (idx === -1) return;
      sock.removeAllListeners('data');
      clearTimeout(timer);
      const head = buf.slice(0, idx).toString('utf8');
      if (!/^HTTP\/1\.\d 200/i.test(head)) {
        sock.destroy();
        reject(new Error(head.split('\r\n')[0] || head));
        return;
      }
      resolve(sock);
    });
    sock.once('error', (e) => { clearTimeout(timer); reject(e); });
  });
}

async function main() {
  step('1. 识别用户报告的克隆失败不是「远程分支没了」，而是传输层问题');
  isTransportError(USER_ERR) ? ok('Failed to connect 判定为传输错误') : fail('没认出 Failed to connect');
  isRemoteRefMissing(USER_ERR) ? fail('把连接失败误判成分支已删除') : ok('连接失败不会被当成分支已删除');
  const parsed = interpretLsRemote({ code: 128, err: USER_ERR, out: '' });
  parsed.error && parsed.exists !== false
    ? ok('ls-remote 连接失败不会当成「分支不存在」')
    : fail(`ls-remote 连接失败解析不对: ${JSON.stringify(parsed)}`);
  interpretLsRemote({ code: 2, err: "fatal: couldn't find remote ref refs/heads/dev", out: '' }).exists === false
    ? ok('分支确实不存在时 exists=false')
    : fail('没认出远程分支不存在');
  /不是整机断网/.test(formatGitFailure('克隆仓库', { code: 128, err: USER_ERR }))
    ? ok('失败文案明确写了不是整机断网')
    : fail('失败文案没有说明不是网络断开');

  step('2. 解析系统/PAC 代理字符串');
  eq(parseProxySpec('DIRECT'), null, 'DIRECT → 无代理');
  eq(parseProxySpec('PROXY 127.0.0.1:7890'), 'http://127.0.0.1:7890', 'HTTP 代理');
  eq(parseProxySpec('HTTPS 127.0.0.1:7890; DIRECT'), 'http://127.0.0.1:7890', '取 PAC 第一跳');
  eq(parseProxySpec('SOCKS5 127.0.0.1:7891'), 'socks5h://127.0.0.1:7891', 'SOCKS5 走远端 DNS');
  eq(parseWindowsProxyServer('127.0.0.1:7890'), 'http://127.0.0.1:7890', 'Windows 单一代理');
  eq(parseWindowsProxyServer('http=127.0.0.1:7890;https=127.0.0.1:7890'), 'http://127.0.0.1:7890', 'Windows 协议分项代理');
  eq(parseConnectTarget('github.com:443').host, 'github.com', 'CONNECT 目标主机');
  eq(parseConnectTarget('github.com:443').port, 443, 'CONNECT 目标端口');
  defaultAllowHost('github.com') && defaultAllowHost('codeload.github.com') && !defaultAllowHost('example.com')
    ? ok('本地代理只放行 GitHub 主机')
    : fail('主机放行规则不对');

  step('3. 合并 GIT_CONFIG 时不能覆盖进程里已有的代理配置');
  const env = mergeGitConfigs({
    GIT_CONFIG_COUNT: '2',
    GIT_CONFIG_KEY_0: 'http.proxy',
    GIT_CONFIG_VALUE_0: 'http://127.0.0.1:7890',
    GIT_CONFIG_KEY_1: 'https.proxy',
    GIT_CONFIG_VALUE_1: 'http://127.0.0.1:7890',
  }, [
    ['credential.helper', ''],
    ['http.version', 'HTTP/1.1'],
  ]);
  eq(env.GIT_CONFIG_COUNT, '4', 'COUNT 在原有基础上累加');
  eq(env.GIT_CONFIG_KEY_0, 'http.proxy', '原有 KEY_0 还在');
  eq(env.GIT_CONFIG_VALUE_0, 'http://127.0.0.1:7890', '原有代理值还在');
  eq(env.GIT_CONFIG_KEY_2, 'credential.helper', '追加 credential.helper');
  eq(env.GIT_CONFIG_VALUE_2, '', 'credential.helper 允许空字符串');
  needsNetwork(['clone', '--branch', 'main', 'https://github.com/a/b.git', '.'])
    ? ok('https clone 需要走代理通道')
    : fail('https clone 应走网络');
  needsNetwork(['clone', '--branch', 'main', 'file:///tmp/repo.git', '.'])
    ? fail('file:// clone 不该走网络代理')
    : ok('本地 file:// clone 不走网络代理');
  needsNetwork(['clone', '--branch', 'dev', '--single-branch', '/tmp/gsync-cache', '.'])
    ? fail('从本地缓存展开分支不该走网络')
    : ok('从本地缓存展开分支不走网络');
  needsNetwork(['commit', '-m', 'x'])
    ? fail('本地 commit 不该走网络代理')
    : ok('本地 commit 不走网络代理');

  step('4. 本地 CONNECT 代理能把 git 流量转到本机服务（模拟 git.exe 被墙、本进程能上网）');
  const echo = net.createServer((c) => { c.on('data', (d) => c.write(d)); });
  await new Promise((resolve, reject) => {
    echo.listen(0, '127.0.0.1', resolve);
    echo.once('error', reject);
  });
  const echoPort = echo.address().port;
  const proxy = new LocalGitProxy({
    allowHost: (h) => h === '127.0.0.1' || defaultAllowHost(h),
    resolveUpstream: async () => null,
  });
  const proxyUrl = await proxy.start();
  try {
    const tun = await httpConnect(proxyUrl, '127.0.0.1', echoPort);
    const pong = await new Promise((resolve, reject) => {
      tun.once('data', (d) => resolve(d.toString()));
      tun.write('ping');
      setTimeout(() => reject(new Error('echo timeout')), 2000);
    });
    pong === 'ping' ? ok('经本地代理 CONNECT 后数据原样回来') : fail(`回程数据不对: ${pong}`);
    tun.destroy();

    let denied = false;
    try {
      await httpConnect(proxyUrl, 'example.com', 443);
    } catch (e) {
      denied = /502|not allowed/i.test(String(e.message));
    }
    denied ? ok('非 GitHub 主机被拒绝，避免把本地代理当开放跳板') : fail('未拒绝非 GitHub 主机');
  } finally {
    await proxy.close();
    await new Promise((resolve) => echo.close(resolve));
  }

  step('5. git 克隆必须用 Basic 认证，不能叠 Bearer（否则 GitHub 回 invalid credentials）');
  const AUTH_ERR = "Cloning into '.'... remote: invalid credentials fatal: Authentication failed for 'https://github.com/thinkdeepers/houbeililiang.git/'";
  isAuthError(AUTH_ERR) ? ok('invalid credentials 判定为认证错误') : fail('没认出 invalid credentials');
  isTransportError(AUTH_ERR) ? fail('把认证失败误判成传输错误') : ok('认证失败不是传输错误');
  interpretLsRemote({ code: 128, err: AUTH_ERR, out: '' }).error
    ? ok('ls-remote 认证失败不会当成分支已删除')
    : fail('认证失败被当成 exists=false');
  /Basic 认证/.test(formatGitFailure('克隆仓库', { code: 128, err: AUTH_ERR }))
    ? ok('认证失败文案说明了原因')
    : fail('认证失败文案不对');
  const header = gitAuthHeader('ghp_testtoken');
  header.startsWith('Authorization: Basic ') && !/Bearer/.test(header)
    ? ok('extraHeader 使用 Basic 而不是 Bearer')
    : fail(`认证头不对: ${header}`);
  const decoded = Buffer.from(header.replace('Authorization: Basic ', ''), 'base64').toString('utf8');
  eq(decoded, 'x-access-token:ghp_testtoken', 'Basic 内容是 x-access-token:TOKEN');
  eq(authUrl('https://x-access-token@github.com/thinkdeepers/houbeililiang.git'), 'https://github.com/thinkdeepers/houbeililiang.git', '远程 URL 去掉用户名，避免双份 Authorization');
  eq(authUrl('https://github.com/thinkdeepers/houbeililiang.git'), 'https://github.com/thinkdeepers/houbeililiang.git', '干净 https URL 保持不变');
  eq(authUrl('file:///tmp/repo.git'), 'file:///tmp/repo.git', 'file:// 测试远程不受影响');
  const gitEnv = await buildGitEnv({ token: 'ghp_testtoken' }, ['status']);
  const headers = [];
  for (let i = 0; i < Number(gitEnv.GIT_CONFIG_COUNT); i++) {
    if (gitEnv[`GIT_CONFIG_KEY_${i}`] === 'http.extraHeader') headers.push(gitEnv[`GIT_CONFIG_VALUE_${i}`]);
  }
  headers.length === 1 && headers[0] === gitAuthHeader('ghp_testtoken')
    ? ok('git 环境只注入一份 Basic extraHeader')
    : fail(`extraHeader 不对: ${JSON.stringify(headers)}`);
  headers.some(h => /Bearer/.test(h))
    ? fail('仍然注入了 Bearer，GitHub git 会回 invalid credentials')
    : ok('没有注入 Bearer 头');

  console.log(`\n结果：${failures === 0 ? '全部通过' : failures + ' 项失败'}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('\n测试异常:', e);
  process.exit(1);
});
