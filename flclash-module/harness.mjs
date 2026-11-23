/**
 * 离线验证 po0-whitelist.js：假的 $httpClient + 假的 Po0 后端（GET 快照 / POST add 的
 * 幂等、固定槽位、FIFO 淘汰、429），不访问网络、不写真实白名单。
 *
 *   node flclash-module/harness.mjs
 */

import { readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const here = dirname(fileURLToPath(import.meta.url));
const source = await readFile(join(here, 'po0-whitelist.js'), 'utf8');

const TOKEN = 'pgnfw_TEST';
const ENDPOINT = 'https://po0.test';

/** 假的 Po0：按 po0fw 的服务端语义——按来源 /24 幂等加白，坑满 FIFO 淘汰最旧。 */
function backend(overrides = {}) {
  const state = {
    enabled: true,
    currentIp: '203.0.113.0/24',
    limit: 5,
    whitelist: [],
    seq: 0,
    ...overrides,
  };
  const requests = [];
  const snapshot = () => ({
    enabled: state.enabled,
    currentIp: state.currentIp,
    limit: state.limit,
    whitelist: state.whitelist.map((row) => ({ ip: row.ip, slot: row.slot })),
  });
  const reply = (status, body, headers = {}) => ({ status, headers, body: JSON.stringify(body) });
  const find = (ip) => state.whitelist.find((row) => row.ip === ip);
  const freeSlot = () => {
    for (let slot = 0; slot < state.limit; slot += 1) {
      if (!state.whitelist.some((row) => row.slot === slot)) {
        return slot;
      }
    }
    return null;
  };
  const place = (slot) => {
    state.whitelist = state.whitelist.filter((row) => row.slot !== slot);
    state.whitelist.push({ ip: state.currentIp, slot, seq: (state.seq += 1) });
  };

  return {
    state,
    requests,
    handle(method, url) {
      requests.push(`${method} ${url}`);
      if (state.mode === '429') {
        return { status: 429, headers: { 'retry-after': '60' }, body: '' };
      }
      const base = `${ENDPOINT}/api/firewall/${TOKEN}`;
      if (method === 'get' && url === base) {
        return reply(200, snapshot());
      }
      if (method === 'post' && url === `${base}/add`) {
        if (state.enabled && !find(state.currentIp)) {
          let slot = freeSlot();
          if (slot === null || state.whitelist.length >= state.limit) {
            // 坑满：按最早写入淘汰一条，这正是 po0fw 的自愈来源。
            const oldest = [...state.whitelist].sort((a, b) => a.seq - b.seq)[0];
            slot = oldest.slot === null ? (freeSlot() ?? 0) : oldest.slot;
            state.whitelist = state.whitelist.filter((row) => row !== oldest);
          }
          place(slot);
        }
        return reply(200, snapshot());
      }
      if (method === 'post' && url.startsWith(`${base}/add?slot=`)) {
        const slot = Number(url.slice(`${base}/add?slot=`.length));
        if (!state.enabled) {
          return reply(200, snapshot());
        }
        const covered = find(state.currentIp);
        if (covered && covered.slot !== slot) {
          return reply(409, { error: 'covered' });
        }
        const occupied = state.whitelist.some((row) => row.slot === slot);
        if (!occupied && state.whitelist.length >= state.limit) {
          return reply(400, { error: 'full' });
        }
        if (state.mode !== 'silent') {
          place(slot);
        }
        return reply(200, snapshot());
      }
      return reply(404, { error: 'not found' });
    },
  };
}

function run({ argument, server, store = {} }) {
  return new Promise((resolve) => {
    const notifications = [];
    const pending = new Set();
    const context = vm.createContext({
      $argument: argument,
      $notification: { post: (title, subtitle, body) => notifications.push({ title, subtitle, body }) },
      $persistentStore: {
        read: (key) => (key in store ? store[key] : null),
        write: (value, key) => {
          store[key] = value;
          return true;
        },
      },
      $httpClient: {
        get: (options, callback) => dispatch('get', options, callback),
        post: (options, callback) => dispatch('post', options, callback),
      },
      $done: () => {
        for (const timer of pending) {
          clearTimeout(timer);
        }
        pending.clear();
        resolve({ notifications, store });
      },
    });

    function dispatch(method, options, callback) {
      const timer = setTimeout(() => {
        pending.delete(timer);
        const answer = server.handle(method, options.url);
        callback(null, { status: answer.status, headers: answer.headers }, answer.body);
      }, 1);
      pending.add(timer);
    }

    vm.runInContext(source, context, { filename: 'po0-whitelist.js' });
  });
}

let failures = 0;
function check(name, condition, detail) {
  if (condition) {
    console.log(`  ok   ${name}`);
    return;
  }
  failures += 1;
  console.log(`  FAIL ${name}${detail === undefined ? '' : ` — ${JSON.stringify(detail)}`}`);
}

const arg = (token = TOKEN, slot = '0', endpoint = ENDPOINT, dry = 'false') =>
  `${token}|${slot}|${endpoint}|${dry}`;

console.log('1) 固定槽位空着 → 写进这一格');
{
  const server = backend();
  const result = await run({ argument: arg(), server });
  check('一次查询 + 一次写入', server.requests.length === 2, server.requests);
  check('槽位 0 是当前 /24', server.state.whitelist.find((r) => r.slot === 0)?.ip === '203.0.113.0/24');
  check('成功通知标题', result.notifications[0]?.title === '✓ Po0 已加白', result.notifications[0]);
  check(
    '正文用回读的实际槽位和出口',
    result.notifications[0]?.body === '槽位 0 · 203.0.113.0/24',
    result.notifications[0],
  );
}

console.log('2) 槽位里已经是当前 /24 → 只读');
{
  const server = backend({ whitelist: [{ ip: '203.0.113.0/24', slot: 0, seq: 1 }], seq: 1 });
  const result = await run({ argument: arg(), server });
  check('只查询，不写', server.requests.length === 1, server.requests);
  check('不通知', result.notifications.length === 0, result.notifications);
}

console.log('3) 槽位是旧网段 → 覆盖它');
{
  const server = backend({ whitelist: [{ ip: '198.51.100.0/24', slot: 0, seq: 1 }], seq: 1 });
  await run({ argument: arg(), server });
  check('槽位 0 换成当前 /24', server.state.whitelist.find((r) => r.slot === 0)?.ip === '203.0.113.0/24');
  check('没有多出别的格子', server.state.whitelist.length === 1, server.state.whitelist);
}

console.log('4) 当前 /24 在别的槽位 → 不写，提示先删');
{
  const server = backend({ whitelist: [{ ip: '203.0.113.0/24', slot: 2, seq: 1 }], seq: 1 });
  const result = await run({ argument: arg(TOKEN, '0'), server });
  check('只查询，不写', server.requests.length === 1, server.requests);
  check('提示已在槽位 2', result.notifications[0]?.body.includes('槽位 2'), result.notifications[0]);
}

console.log('5) 不填槽位（po0fw 默认模式）→ 缺失时 POST /add，服务端分配');
{
  const server = backend();
  await run({ argument: arg(TOKEN, ''), server });
  check('POST 不带 slot', server.requests[1] === `post ${ENDPOINT}/api/firewall/${TOKEN}/add`, server.requests);
  check('被放进 0 号空位', server.state.whitelist.find((r) => r.ip === '203.0.113.0/24')?.slot === 0);
}

console.log('6) 不填槽位 + 已在名单 → 不写');
{
  const server = backend({ whitelist: [{ ip: '203.0.113.0/24', slot: 3, seq: 1 }], seq: 1 });
  await run({ argument: arg(TOKEN, ''), server });
  check('只查询，不写', server.requests.length === 1, server.requests);
}

console.log('7) 平台停用 → 不写');
{
  const server = backend({ enabled: false });
  const result = await run({ argument: arg(), server });
  check('没有 POST', server.requests.every((r) => r.startsWith('get ')), server.requests);
  check('通知「暂停」', result.notifications[0]?.title.includes('暂停'), result.notifications[0]);
}

console.log('8) 429 → 按 Retry-After 退避，不写');
{
  const server = backend({ mode: '429' });
  const result = await run({ argument: arg(), server });
  const state = JSON.parse(result.store['po0.whitelist.state']);
  check('没有 POST', server.requests.length === 1, server.requests);
  check('退避约 60 秒', state.backoffUntil - Date.now() > 30 * 1000, state.backoffUntil - Date.now());
}

console.log('9) 退避期内再次触发 → 零请求');
{
  const server = backend();
  const store = { 'po0.whitelist.state': JSON.stringify({ backoffUntil: Date.now() + 60000 }) };
  const result = await run({ argument: arg(), server, store });
  check('零请求', server.requests.length === 0, server.requests);
  check('不通知', result.notifications.length === 0, result.notifications);
}

console.log('10) 写入没生效 → 不误报成功');
{
  const server = backend({ mode: 'silent' });
  const result = await run({ argument: arg(), server });
  check('通知「未生效」', result.notifications[0]?.title.includes('未生效'), result.notifications[0]);
  check('白名单没变', server.state.whitelist.length === 0, server.state.whitelist);
}

console.log('11) 坑满 + 不钉槽位 → FIFO 挤掉最旧的，自愈成功');
{
  const server = backend({
    limit: 2,
    whitelist: [
      { ip: '198.51.100.0/24', slot: 0, seq: 1 },
      { ip: '198.51.101.0/24', slot: 1, seq: 2 },
    ],
    seq: 2,
  });
  const result = await run({ argument: arg(TOKEN, ''), server });
  check('最旧的被挤掉', !server.state.whitelist.some((r) => r.ip === '198.51.100.0/24'), server.state.whitelist);
  check('当前 /24 进名单', server.state.whitelist.some((r) => r.ip === '203.0.113.0/24'));
  check('通知成功', result.notifications[0]?.title === '✓ Po0 已加白', result.notifications[0]);
  check(
    '正文带服务端实际分配的槽位',
    result.notifications[0]?.body === '槽位 0 · 203.0.113.0/24',
    result.notifications[0],
  );
}

console.log('12) 坑满 + 钉了空槽位 → 400，如实报错');
{
  const server = backend({
    limit: 3,
    whitelist: [
      { ip: '198.51.100.0/24', slot: 0, seq: 1 },
      { ip: '198.51.101.0/24', slot: 1, seq: 2 },
      { ip: '198.51.102.0/24', slot: null, seq: 3 },
    ],
    seq: 3,
  });
  const result = await run({ argument: arg(TOKEN, '2'), server });
  check('发出写入', server.requests.length === 2, server.requests);
  check('通知 HTTP 400', result.notifications[0]?.body.includes('400'), result.notifications[0]);
}

console.log('13) 槽位超出平台范围 → 不写');
{
  const server = backend({ limit: 5 });
  const result = await run({ argument: arg(TOKEN, '9'), server });
  check('只查询', server.requests.length === 1, server.requests);
  check('提示 0–4', result.notifications[0]?.body.includes('0–4'), result.notifications[0]);
}

console.log('14) 粘贴完整链接 → 解析出服务器与 token');
{
  const server = backend();
  const link = `${ENDPOINT}/api/firewall/${TOKEN}/add?slot=0`;
  await run({ argument: `${link}|0|`, server });
  check('请求打到链接里的服务器', server.requests[0] === `get ${ENDPOINT}/api/firewall/${TOKEN}`, server.requests);
  check('写入成功', server.state.whitelist.find((r) => r.slot === 0)?.ip === '203.0.113.0/24');
}

console.log('15) 没填 token → 零请求');
{
  const server = backend();
  const result = await run({ argument: arg(''), server });
  check('零请求', server.requests.length === 0, server.requests);
  check('提示未配置', result.notifications[0]?.title.includes('未配置'), result.notifications[0]);
}

console.log('16) 同一失败重复触发 → 6 小时内不重复通知');
{
  const server = backend({ enabled: false });
  const store = {};
  await run({ argument: arg(), server, store });
  const second = await run({ argument: arg(), server, store });
  check('第二次没有新通知', second.notifications.length === 0, second.notifications);
}

console.log('17) 当前 /24 在流动位、钉的是 0 → 不重复写，提示是流动位');
{
  const server = backend({ whitelist: [{ ip: '203.0.113.0/24', slot: null, seq: 1 }], seq: 1 });
  const result = await run({ argument: arg(TOKEN, '0'), server });
  check('只查询，不写', server.requests.length === 1, server.requests);
  check('提示「流动位」', result.notifications[0]?.body.includes('流动位'), result.notifications[0]);
}

console.log('18) 只读模式（dry=true）→ 只报告，不写');
{
  const server = backend();
  const result = await run({ argument: arg(TOKEN, '0', ENDPOINT, 'true'), server });
  check('只查询，不写', server.requests.length === 1, server.requests);
  check('白名单没变', server.state.whitelist.length === 0, server.state.whitelist);
  check('只读预览标题', result.notifications[0]?.title === 'Po0 只读预览', result.notifications[0]);
  check(
    '预览说明未写入并给出计划槽位',
    result.notifications[0]?.body === '未写入 · 槽位 0 · 203.0.113.0/24',
    result.notifications[0],
  );
}

console.log('19) 服务端把网段写成纯 IP → 仍按 /24 认作已在名单');
{
  const server = backend({ whitelist: [{ ip: '203.0.113.0', slot: null, seq: 1 }], seq: 1 });
  const result = await run({ argument: arg(), server });
  check('只查询，不写', server.requests.length === 1, server.requests);
  check('按 /24 认作已在名单，提示在流动位', result.notifications[0]?.body.includes('流动位'), result.notifications[0]);
}

console.log('');
if (failures === 0) {
  console.log('全部通过：19 组场景，未访问网络，未写入任何真实白名单。');
} else {
  console.log(`${failures} 项失败`);
  process.exitCode = 1;
}
