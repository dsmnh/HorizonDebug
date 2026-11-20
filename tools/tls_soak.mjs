/**
 * TLS 平面长连接稳定性探针：App 平面 TLS 握手 → 校验 HELLO_ACK 携带 0x02 标记 → 每 30 秒 PING/PONG，持续 N 分钟。
 * 用法：node tools/tls_soak.mjs [分钟数] [端口]
 * 退出码 0 且打印 SOAK_PASS 表示全程无断连且 PING/PONG 配对。
 */
import tls from 'node:tls';

const MINUTES = Number(process.argv[2] ?? 30);
const APP_PORT = Number(process.argv[3] ?? 44444);
const T = { HELLO: 0x01, HELLO_ACK: 0x02, PING: 0x30, PONG: 0x31 };

function encode(type, obj, flags) {
  const body = obj == null ? Buffer.alloc(0) : Buffer.from(JSON.stringify(obj), 'utf8');
  const out = Buffer.alloc(4 + 2 + body.length);
  out.writeUInt32BE(body.length + 2, 0);
  out[4] = type;
  out[5] = flags & 0xff;
  body.copy(out, 6);
  return out;
}

let pings = 0;
let pongs = 0;
let failures = 0;

function log(msg) {
  console.log(`[${new Date().toISOString()}] ${msg}`);
}

function connect() {
  return new Promise((resolve, reject) => {
    // 探针只验证长连接与帧标记，自签证书不影响结论，故不校验证书链
    const socket = tls.connect({ host: '127.0.0.1', port: APP_PORT, rejectUnauthorized: false }, () => resolve(socket));
    socket.once('error', reject);
  });
}

async function main() {
  const started = Date.now();
  const socket = await connect();
  log('TLS 握手完成');
  let buf = Buffer.alloc(0);
  let ackSeen = false;
  let flagsOk = false;

  socket.on('data', (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    for (;;) {
      if (buf.length < 4) return;
      const len = buf.readUInt32BE(0);
      if (buf.length < 4 + len) return;
      const raw = buf.subarray(4, 4 + len);
      buf = buf.subarray(4 + len);
      const type = raw[0];
      const flags = raw[1];
      if (type === T.HELLO_ACK) {
        ackSeen = true;
        flagsOk = (flags & 0x02) !== 0;
        log(`HELLO_ACK flags=0x${flags.toString(16).padStart(2, '0')} tls=0x02:${flagsOk}`);
      } else if (type === T.PONG) {
        pongs += 1;
      }
    }
  });
  socket.on('close', () => log('连接被关闭'));
  socket.on('error', (e) => log(`socket 错误：${e.message}`));

  socket.write(encode(T.HELLO, { v: 1, app: 'TlsSoak/1.0', plane: 'app' }, 0x02));
  await new Promise((r) => setTimeout(r, 1500));
  if (!ackSeen) {
    failures += 1;
    log('未收到 HELLO_ACK');
  }
  if (!flagsOk) {
    failures += 1;
    log('HELLO_ACK 未携带 TLS 标记');
  }

  const deadline = started + MINUTES * 60 * 1000;
  while (Date.now() < deadline) {
    if (socket.destroyed) {
      failures += 1;
      log('连接已断开，终止');
      break;
    }
    pings += 1;
    socket.write(encode(T.PING, {}, 0x02));
    await new Promise((r) => setTimeout(r, 30000));
  }

  if (pings !== pongs) {
    failures += 1;
    log(`PING/PONG 不匹配：pings=${pings} pongs=${pongs}`);
  }
  socket.destroy();
  const mins = ((Date.now() - started) / 60000).toFixed(1);
  log(`结束：时长 ${mins} 分钟，PING ${pings} 次，PONG ${pongs} 次，失败 ${failures} 项`);
  console.log(failures === 0 ? 'SOAK_PASS' : 'SOAK_FAIL');
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('SOAK_ERROR', e.message);
  process.exit(2);
});
