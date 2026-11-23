/**
 * Po0 自动加白 —— FlClash 模块脚本（Surge 脚本 API 兼容）
 *
 * 语义照 po0fw / po0-clash：
 *   1. 先只读 GET，拿到服务端看到的「当前出口 /24」和整张白名单；
 *   2. 当前 /24 已经在白名单里（无论哪一格）→ 什么都不做；
 *   3. 不在 → POST 加白：填了槽位就 POST /add?slot=N（po0fw 的 token@N 固定槽位），
 *      没填就 POST /add（po0fw 默认，坑满按 FIFO 淘汰，被挤掉下轮自愈）；
 *   4. 用 POST 返回的快照回读核验，没生效就不报成功。
 *
 * 模块的 [Rule] 把 Po0 的 IP 强制 DIRECT，所以服务端看到的出口是本机真实出口，
 * 而不是代理节点 IP。这条规则是命门：它被删掉或改成走代理，写进去的就是节点 IP。
 *
 * 参数（$argument，三段用 | 分隔）：
 *   token    pgnfw_ 开头的 token，或完整的 https://host/api/firewall/pgnfw_xxx/add 链接
 *   slot     固定槽位号（0..limit-1）；留空 = po0fw 默认的 FIFO 模式
 *   endpoint Po0 服务器地址，默认 https://124.221.69.228
 *   dry      'true' 时只读：只报告「本应写入什么」，不 POST
 *
 * 只调用 $done() 一次；失败先落盘再通知。
 */

var DEFAULT_ENDPOINT = 'https://124.221.69.228';
var STATE_KEY = 'po0.whitelist.state';
var REQUEST_TIMEOUT = 15000;
var NOTIFY_REPEAT_MS = 6 * 60 * 60 * 1000;

function finish() {
  $done({});
}

function readConfig() {
  var raw = typeof $argument === 'string' ? $argument : '';
  var parts = raw.split('|');
  var token = (parts[0] || '').trim();
  var slotText = (parts[1] || '').trim();
  var endpoint = (parts[2] || '').trim();
  var dry = (parts[3] || '').trim() === 'true';

  // 允许直接粘贴官方链接：从链接里同时取回服务器与 token。
  var link = token.match(/^https?:\/\/([^\/]+)\/api\/firewall\/(pgnfw_[A-Za-z0-9_-]+)/);
  if (link) {
    endpoint = 'https://' + link[1];
    token = link[2];
  }
  if (!endpoint) {
    endpoint = DEFAULT_ENDPOINT;
  }

  var slot = null;
  if (slotText !== '') {
    var parsed = parseInt(slotText, 10);
    slot = isNaN(parsed) || parsed < 0 ? -1 : parsed;
  }
  return {
    token: token,
    slot: slot,
    endpoint: endpoint.replace(/\/+$/, ''),
    dry: dry
  };
}

function loadState() {
  try {
    var text = $persistentStore.read(STATE_KEY);
    var parsed = text ? JSON.parse(text) : {};
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch (e) {
    return {};
  }
}

function saveState(state) {
  try {
    $persistentStore.write(JSON.stringify(state), STATE_KEY);
  } catch (e) {
    // 落盘失败不阻断本次检查，只是下次会重复通知。
  }
}

function notify(state, title, body) {
  var now = Date.now();
  var key = title + '|' + body;
  if (state.notifyKey === key && now - (state.notifyAt || 0) < NOTIFY_REPEAT_MS) {
    return;
  }
  state.notifyKey = key;
  state.notifyAt = now;
  $notification.post(title, '', body);
}

/** Concord 将拨号和 rustls 错误直接交给脚本。原文可能带完整请求地址，只返回固定文案。 */
function requestErrorMessage(error) {
  var text = String(error).trim().replace(/^Error:\s*/i, '');
  var certificate = /^(?:invalid peer certificate:\s*|InvalidCertificate\()/i.exec(text);
  if (certificate) {
    var reason = text.slice(certificate[0].length);
    if (/^(?:Expired(?:Context)?\b|certificate expired:)/i.test(reason)) {
      return 'TLS 证书已过期';
    }
    if (/^(?:NotValidYet(?:Context)?\b|certificate not valid yet:)/i.test(reason)) {
      return 'TLS 证书尚未生效';
    }
    return 'TLS 证书验证失败';
  }
  if (/^(?:certificate verification failed|certificate verify failed|peer sent no certificates)\b/i.test(text)) {
    return 'TLS 证书验证失败';
  }
  if (/^(?:the )?TLS handshake timed out\b/i.test(text)) {
    return 'TLS 握手超时';
  }
  if (/^(?:TLS handshake (?:failed|eof)\b|received unexpected handshake message:|peer is incompatible:|handshake not complete\b)/i.test(text)) {
    return 'TLS 握手失败';
  }
  if (/^(?:received fatal alert:|received corrupt message of type\b|peer misbehaved:)/i.test(text)) {
    return 'TLS 通信失败';
  }
  // Concord 的 DNS 错误也可能包含上游超时或 TLS 错误，按 DNS 失败报告。
  if (/^(?:no IP found for |all DNS requests failed\b|DNS exchange timed out\b)/i.test(text)) {
    return 'DNS 解析失败';
  }
  if (/^(?:network is unreachable|host is unreachable|no route to host)\b/i.test(text)) {
    return '网络或主机不可达，请检查当前网络';
  }
  if (/^(?:connection refused\b|connection rejected by )/i.test(text)) {
    return '连接被拒绝';
  }
  if (/^(?:connection (?:reset|aborted|closed before message completed)|broken pipe)\b/i.test(text)) {
    return '连接中断';
  }
  if (/^(?:the request timed out|connection timed out|operation timed out|timed out)\b/i.test(text)) {
    return '请求超时';
  }
  return '连接失败，原因未明';
}

function httpErrorMessage(status) {
  var message = 'HTTP ' + status;
  if (status >= 500) {
    return message + '，服务端或网关异常';
  }
  if (status === 401 || status === 403) {
    return message + '，请检查 token 和白名单权限';
  }
  return message;
}

function request(method, url, callback) {
  var options = {
    url: url,
    headers: { Accept: 'application/json' },
    timeout: REQUEST_TIMEOUT,
    'auto-redirect': false
  };
  $httpClient[method](options, function (error, response, body) {
    if (error) {
      callback(requestErrorMessage(error), 0, '', null);
      return;
    }
    var status = response && (response.status || response.statusCode) || 0;
    if (typeof status !== 'number' || status < 100 || status > 599 || status % 1 !== 0) {
      callback('未收到有效的 HTTP 响应', 0, '', null);
      return;
    }
    callback(null, status, body || '', (response && response.headers) || {});
  });
}

/** 平台用 Retry-After 说「多久之后再来」，别自己猜。 */
function backoffUntil(headers) {
  var value = null;
  for (var name in headers) {
    if (name.toLowerCase() === 'retry-after') {
      value = headers[name];
    }
  }
  if (value && typeof value === 'string') {
    value = [value];
  }
  var seconds = parseInt(value && value[0], 10);
  var wait = isNaN(seconds) ? 10 * 60 * 1000 : Math.min(seconds, 21600) * 1000;
  return Date.now() + Math.max(wait, 60 * 1000);
}

function snapshotOf(body) {
  var data = JSON.parse(body);
  if (!data || typeof data !== 'object') {
    throw new Error('snapshot');
  }
  return data;
}

/** 白名单按 /24 记，服务端可能回 "x.x.x.0/24" 也可能回纯 IP；按前三段比，照 po0-clash 的 sameC24。 */
function sameC24(a, b) {
  if (!a || !b) {
    return false;
  }
  if (a === b) {
    return true;
  }
  if (a.indexOf('/24') < 0 && b.indexOf('/24') < 0) {
    return false;
  }
  var pa = a.replace('/24', '').split('.');
  var pb = b.replace('/24', '').split('.');
  if (pa.length !== 4 || pb.length !== 4) {
    return false;
  }
  return pa[0] === pb[0] && pa[1] === pb[1] && pa[2] === pb[2];
}

function rowWithIp(snapshot, ip) {
  var rows = snapshot && snapshot.whitelist;
  if (!rows || typeof rows.length !== 'number') {
    return null;
  }
  for (var i = 0; i < rows.length; i++) {
    if (rows[i] && sameC24(String(rows[i].ip), ip)) {
      return rows[i];
    }
  }
  return null;
}

/** 槽位号，没有槽号的记录（流动位）返回 null。 */
function slotOf(row) {
  var slot = row && row.slot;
  return slot === null || slot === undefined ? null : Number(slot);
}

function slotText(row) {
  var slot = slotOf(row);
  return slot === null ? '流动位' : '槽位 ' + slot;
}

function main() {
  var config = readConfig();
  var state = loadState();
  var now = Date.now();

  if (!/^pgnfw_[A-Za-z0-9_-]+$/.test(config.token)) {
    notify(state, 'Po0 自动加白未配置', '请在模块参数里填入 pgnfw_ 开头的 token');
    saveState(state);
    return finish();
  }
  if (config.slot === -1) {
    notify(state, 'Po0 自动加白未配置', '槽位号只能是 0 或正整数，留空表示不固定槽位');
    saveState(state);
    return finish();
  }
  if (state.backoffUntil && now < state.backoffUntil) {
    return finish();
  }

  var base = config.endpoint + '/api/firewall/' + config.token;
  request('get', base, function (error, status, body, headers) {
    if (error) {
      notify(state, 'Po0 自动加白失败', '查询失败，' + error + '。本次未写入。');
      saveState(state);
      return finish();
    }
    if (status === 429) {
      state.backoffUntil = backoffUntil(headers);
      notify(state, 'Po0 自动加白暂停', '平台限流，稍后自动重试');
      saveState(state);
      return finish();
    }
    if (status !== 200) {
      notify(state, 'Po0 自动加白失败', '查询返回 ' + httpErrorMessage(status) + '。本次未写入。');
      saveState(state);
      return finish();
    }

    var snapshot;
    try {
      snapshot = snapshotOf(body);
    } catch (e) {
      notify(state, 'Po0 自动加白失败', '返回内容无法识别');
      saveState(state);
      return finish();
    }

    if (snapshot.enabled !== true) {
      notify(state, 'Po0 自动加白暂停', '平台已停用白名单');
      saveState(state);
      return finish();
    }

    var currentIp = String(snapshot.currentIp || '');
    var limit = parseInt(snapshot.limit, 10) || 0;
    if (!currentIp) {
      notify(state, 'Po0 自动加白失败', '平台没有返回当前出口');
      saveState(state);
      return finish();
    }

    var existing = rowWithIp(snapshot, currentIp);
    if (existing) {
      if (config.slot !== null && slotOf(existing) !== config.slot) {
        notify(
          state,
          'Po0 自动加白未改动',
          currentIp + ' 已在' + slotText(existing) + '；固定槽位 ' + config.slot +
            ' 空着。要换槽位，先在面板删掉那条记录。'
        );
      }
      state.ip = currentIp;
      state.slot = config.slot;
      state.backoffUntil = 0;
      saveState(state);
      return finish();
    }

    if (config.slot !== null && limit && config.slot >= limit) {
      notify(state, 'Po0 自动加白未执行', '槽位 ' + config.slot + ' 超出平台范围 0–' + (limit - 1));
      saveState(state);
      return finish();
    }

    if (config.dry) {
      notify(
        state,
        'Po0 只读预览',
        '未写入 · ' + (config.slot === null ? '自动分配' : '槽位 ' + config.slot) + ' · ' + currentIp
      );
      saveState(state);
      return finish();
    }

    var target = base + (config.slot === null ? '/add' : '/add?slot=' + config.slot);
    request('post', target, function (postError, postStatus, postBody, postHeaders) {
      if (postError || postStatus >= 500) {
        var problem = postError ? '写入请求异常，' + postError : '写入返回 ' + httpErrorMessage(postStatus);
        notify(state, 'Po0 自动加白待核验', problem + '。下次查询时核验写入结果。');
        saveState(state);
        return finish();
      }
      if (postStatus === 429) {
        state.backoffUntil = backoffUntil(postHeaders);
        notify(state, 'Po0 自动加白暂停', '平台限流，稍后自动重试');
        saveState(state);
        return finish();
      }
      if (postStatus !== 200) {
        notify(state, 'Po0 自动加白失败', '写入返回 ' + httpErrorMessage(postStatus));
        saveState(state);
        return finish();
      }

      var after;
      try {
        after = snapshotOf(postBody);
      } catch (e) {
        notify(state, 'Po0 自动加白待核验', '写入已提交，但返回内容无法识别');
        saveState(state);
        return finish();
      }

      var written = rowWithIp(after, currentIp);
      var placedRight =
        written && (config.slot === null || slotOf(written) === config.slot);
      if (!placedRight) {
        notify(
          state,
          'Po0 自动加白未生效',
          '写入后 ' + currentIp + ' 不在' + (config.slot === null ? '白名单' : '槽位 ' + config.slot)
        );
        saveState(state);
        return finish();
      }

      state.ip = currentIp;
      state.slot = config.slot;
      state.backoffUntil = 0;
      notify(state, '✓ Po0 已加白', slotText(written) + ' · ' + currentIp);
      saveState(state);
      return finish();
    });
  });
}

main();
