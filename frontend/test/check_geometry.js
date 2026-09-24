#!/usr/bin/env node
/**
 * CanalMind 前端**真几何**自检：在真 Chrome 里量布局，⛔ 不看截图。
 *
 * 用法：`node frontend/test/check_geometry.js` ｜ 退出码：0 = 全过，1 = 失败/起不来
 *
 * ───────────────────────────────────────────────────────────────────────────
 * 为什么单独一个文件、为什么不在 check_frontend.js 里
 * ───────────────────────────────────────────────────────────────────────────
 *   `check_frontend.js` 用假 DOM 空跑渲染函数，覆盖**契约与产物**，零依赖、可在任何环境跑。
 *   但它量不到**几何**：横向溢出、侧边栏宽度、输入区是否贴底——这些只有真浏览器能回答。
 *   ⇒ 两者互补，缺一不可：假 DOM 证明"内容对"，真浏览器证明"排得下"。
 *
 * ───────────────────────────────────────────────────────────────────────────
 * ⚠️ 运行环境（实测踩过，别浪费时间）
 * ───────────────────────────────────────────────────────────────────────────
 *   ① **DSH 受限沙箱下 Chrome 起不来**：Chrome 的 Mojo IPC 需要命名管道，进程会以
 *      0xFFFF0001 立刻退出，表现为"等不到 CDP target"。
 *      ⇒ 需要在更宽权限下运行（本机实测 danger-full-access 才起得来）。
 *   ② `window.resizeTo()` 在 headless 下**无效**（实测两次测量视口完全一样）。
 *      ⇒ 必须用 CDP 的 `Emulation.setDeviceMetricsOverride` 指定精确视口。
 *   ③ 本进程的 stdout 在管道下**会丢**。⇒ 结果同时同步写 `result.txt`，失败也留证据。
 *   ④ 只认本机 Chrome 路径；找不到就直接失败退出，⛔ 不静默跳过（静默跳过等于没检查）。
 */

"use strict";

const { spawn } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

const LF = String.fromCharCode(10);
const PAGE = "file:///" + path.resolve(__dirname, "..", "index.html").replace(/\\/g, "/");
const RESULT = path.join(os.tmpdir(), "canalmind-geom-result.txt");
const PROFILE = path.join(os.tmpdir(), "canalmind-geom-profile");
const PORT = Number(process.env.CM_GEOM_PORT || 9334);
const CHROME_CANDIDATES = [
  "C:" + String.fromCharCode(92) + "Program Files" + String.fromCharCode(92) + "Google" + String.fromCharCode(92) + "Chrome" + String.fromCharCode(92) + "Application" + String.fromCharCode(92) + "chrome.exe",
  "C:" + String.fromCharCode(92) + "Program Files (x86)" + String.fromCharCode(92) + "Microsoft" + String.fromCharCode(92) + "Edge" + String.fromCharCode(92) + "Application" + String.fromCharCode(92) + "msedge.exe"
];

fs.writeFileSync(RESULT, "");
const say = (m) => { fs.appendFileSync(RESULT, m + LF); try { console.log(m); } catch (e) { /* stdout 可能丢 */ } };

let checks = 0;
let fails = 0;
function ok(cond, msg) {
  checks++;
  if (!cond) { fails++; say("  [FAIL] " + msg); }
}
function section(t) { say(LF + "── " + t); }
function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

const MEASURE = `(() => {
  const r = (el) => { const b = el.getBoundingClientRect(); return {w: Math.round(b.width), h: Math.round(b.height), b: Math.round(b.bottom)}; };
  const over = [];
  document.querySelectorAll('*').forEach((el) => {
    const b = el.getBoundingClientRect();
    if (b.width > 0 && b.right > window.innerWidth + 1) {
      over.push(el.tagName + (el.className ? '.' + String(el.className).split(' ')[0] : '') + ' right=' + Math.round(b.right));
    }
  });
  const a = document.querySelector('.amount');
  const cs = a ? getComputedStyle(a) : null;
  const sid = document.getElementById('sid');
  return JSON.stringify({
    inner: [window.innerWidth, window.innerHeight],
    scrollW: document.documentElement.scrollWidth,
    clientW: document.documentElement.clientWidth,
    overflowCount: over.length,
    overflowSample: over.slice(0, 5),
    sidebar: r(document.querySelector('aside')),
    topbar: r(document.querySelector('.topbar')),
    composer: r(document.querySelector('.composer')),
    main: r(document.querySelector('.main')),
    amount: cs ? {fontSize: cs.fontSize, weight: cs.fontWeight, color: cs.color, mono: /mono|Consolas/i.test(cs.fontFamily)} : null,
    sid: {display: getComputedStyle(sid).display, text: sid.textContent},
    dataSource: document.getElementById('dataSource').value,
    sessionCount: document.querySelectorAll('.session').length,
    tagCount: document.querySelectorAll('.tags .tag').length
  });
})()`;

const setSource = (v) => `(() => { const s = document.getElementById('dataSource'); s.value = '${v}'; s.dispatchEvent(new Event('change'));
  const sid = document.getElementById('sid');
  return JSON.stringify({display: getComputedStyle(sid).display, text: sid.textContent}); })()`;

const clickSession = (i) => `(() => { const it = document.querySelectorAll('.session')[${i}]; if (!it) { return '{"error":"NO_ITEM"}'; } it.click();
  const sid = document.getElementById('sid');
  return JSON.stringify({idx: ${i}, topic: document.getElementById('topic').textContent,
    display: getComputedStyle(sid).display, text: sid.textContent}); })()`;

async function findTarget() {
  for (let i = 0; i < 60; i++) {
    try {
      const res = await fetch("http://127.0.0.1:" + PORT + "/json/list");
      const list = await res.json();
      const t = list.filter((x) => x.type === "page" && x.webSocketDebuggerUrl)[0];
      if (t) { return t; }
    } catch (e) { /* 还没起来 */ }
    await sleep(250);
  }
  throw new Error("等不到 CDP target（15 秒）");
}

function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    let id = 0;
    const waiting = new Map();
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
      const w = waiting.get(msg.id);
      if (w) { waiting.delete(msg.id); w(msg); }
    };
    ws.onerror = () => reject(new Error("CDP WebSocket 出错"));
    ws.onopen = () => resolve({
      send: (method, params) => new Promise((res) => {
        const myId = ++id;
        waiting.set(myId, res);
        ws.send(JSON.stringify({ id: myId, method: method, params: params || {} }));
      }),
      eval: async (expr) => {
        const m = await new Promise((res) => {
          const myId = ++id;
          waiting.set(myId, res);
          ws.send(JSON.stringify({ id: myId, method: "Runtime.evaluate",
            params: { expression: expr, returnByValue: true, awaitPromise: true } }));
        });
        return m.result && m.result.result ? m.result.result.value : null;
      },
      close: () => ws.close()
    });
  });
}

/** 一个视口下的通用几何断言 */
function assertLayout(label, m, expect) {
  say("  " + label + "：innerWidth=" + m.inner[0] + " scrollW=" + m.scrollW + " clientW=" + m.clientW +
    " 横向溢出=" + m.overflowCount + (m.overflowSample.length ? " " + JSON.stringify(m.overflowSample) : ""));
  ok(m.inner[0] === expect.width, label + "：视口宽应为 " + expect.width + "，实测 " + m.inner[0]);
  ok(m.sidebar.w === 240, label + "：侧边栏应恒为 240px，实测 " + m.sidebar.w);
  ok(m.topbar.h === 52, label + "：顶栏高应为 52px，实测 " + m.topbar.h);
  // ⚠️ 布局宽度 ≠ 视口宽度：min-width:1200px 的含义就是"视口更窄时**不挤压**，
  //    布局仍按 1200 排、改用横向滚动"。所以窄视口下拿 innerWidth 当基准会误报
  //    （本脚本第一版就这么错过一次：1000px 下报"240+960≠1000"，其实完全正确）。
  const layoutW = Math.max(1200, m.inner[0]);
  ok(m.sidebar.w + m.main.w === layoutW,
    label + "：侧边栏+主区应铺满布局宽度 " + layoutW + "（实测 " + m.sidebar.w + "+" + m.main.w + "=" + (m.sidebar.w + m.main.w) + "）");
  ok(m.composer.b === m.inner[1], label + "：输入区应贴住视口底部（bottom=" + m.composer.b + " 视口高=" + m.inner[1] + "）");
  ok(m.sessionCount === 4, label + "：侧边栏应有 4 条会话，实测 " + m.sessionCount);
  ok(m.tagCount === 3, label + "：快捷标签应恰好 3 个，实测 " + m.tagCount);
  ok(m.amount && m.amount.fontSize === "22px", label + "：金额字号应为 22px，实测 " + (m.amount && m.amount.fontSize));
  ok(m.amount && m.amount.weight === "700", label + "：金额应为 700 字重，实测 " + (m.amount && m.amount.weight));
  ok(m.amount && m.amount.color === "rgb(183, 121, 31)", label + "：金额应为金褐强调色 rgb(183,121,31)，实测 " + (m.amount && m.amount.color));
  ok(m.amount && m.amount.mono === true, label + "：金额必须是等宽字体");
  if (expect.noSqueeze) {
    ok(m.scrollW === m.scrollW && m.scrollW === m.clientW && m.scrollW === expect.width,
      label + "：≥1200px 时不应出现横向滚动（scrollW=" + m.scrollW + " clientW=" + m.clientW + "）");
    ok(m.overflowCount === 0, label + "：≥1200px 时不应有元素越出右边界，实测 " + m.overflowCount + " 个 " + JSON.stringify(m.overflowSample));
    ok(m.main.w === m.inner[0] - 240, label + "：主区应吃掉剩余宽度，实测 " + m.main.w);
  } else {
    // ⚠️ 小于 1200px 时**故意**不挤压：min-width:1200px ⇒ 出现横向滚动是**设计行为**，不是 bug
    ok(m.scrollW === 1200, label + "：<1200px 时应锁定 1200px 宽度（不挤压），实测 scrollW=" + m.scrollW);
    ok(m.clientW < 1200, label + "：<1200px 时视口本身应窄于 1200（说明确实是在滚动而非拉伸）");
    ok(m.overflowCount > 0, label + "：<1200px 时应出现横向溢出（内容不缩，改滚动）");
  }
}

(async () => {
  const chrome = CHROME_CANDIDATES.filter((p) => fs.existsSync(p))[0];
  if (!chrome) {
    console.error("[FAIL] 找不到 Chrome / Edge 可执行文件，无法做真几何自检。");
    CHROME_CANDIDATES.forEach((p) => console.error("        试过：" + p));
    process.exit(1);
  }
  say("真几何自检：" + PAGE);
  say("浏览器：" + chrome);

  try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* 忽略 */ }
  fs.mkdirSync(PROFILE, { recursive: true });

  const child = spawn(chrome, [
    "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
    "--remote-debugging-port=" + PORT, "--user-data-dir=" + PROFILE, PAGE
  ], { stdio: "ignore" });

  try {
    let target;
    try {
      target = await findTarget();
    } catch (e) {
      say("[FAIL] " + e.message);
      say("  ⚠️ 最可能的原因：Chrome 在当前沙箱下起不来——它的 Mojo IPC 需要命名管道，");
      say("     进程会以 0xFFFF0001 立刻退出。请在更宽权限下运行本脚本。");
      say("     自查办法：直接跑 `chrome --headless=new --remote-debugging-port=9334 about:blank`，");
      say("     若秒退则确认是环境限制，而不是本脚本的问题。");
      process.exitCode = 1;
      return;
    }

    const cdp = await connect(target.webSocketDebuggerUrl);
    await sleep(800);

    section("几何：1200 / 1440（应不挤不溢出）与 1000（应改为滚动）");
    const views = [[1200, 900, true], [1440, 900, true], [1000, 800, false]];
    for (const v of views) {
      await cdp.send("Emulation.setDeviceMetricsOverride",
        { width: v[0], height: v[1], deviceScaleFactor: 1, mobile: false });
      await sleep(400);
      const m = JSON.parse(await cdp.eval(MEASURE));
      assertLayout("视口 " + v[0] + "x" + v[1], m, { width: v[0], noSqueeze: v[2] });
      if (v[2]) {
        ok(m.sid.display === "none" && m.sid.text === "",
          "演示数据模式下会话号应隐藏（那时压根没有后端会话），实测 display=" + m.sid.display);
      }
    }

    section("交互：会话号必须**每条会话各一个**且同会话稳定");
    const api = JSON.parse(await cdp.eval(setSource("api")));
    ok(api.display === "block", "后端接口模式下会话号应显示，实测 display=" + api.display);
    ok(api.text.indexOf("会话号 ") === 0, "会话号文案应形如「会话号 web-…」，实测 " + JSON.stringify(api.text));

    const a0 = JSON.parse(await cdp.eval(clickSession(0)));
    const a1 = JSON.parse(await cdp.eval(clickSession(1)));
    const a2 = JSON.parse(await cdp.eval(clickSession(2)));
    const back = JSON.parse(await cdp.eval(clickSession(0)));
    say("    s1=" + a0.text + " ｜ s2=" + a1.text + " ｜ s3=" + a2.text);
    ok(new Set([a0.text, a1.text, a2.text]).size === 3,
      "3 条会话的会话号应互不相同（⛔ 全页共用一个会让后端把会话上下文混在一起）");
    ok(back.text === a0.text, "切回第 1 条会话应拿回同一个会话号（否则多轮上下文会断）");
    ok(a0.topic !== a1.topic, "切换会话时标题应跟着变");

    const mock = JSON.parse(await cdp.eval(setSource("mock")));
    ok(mock.display === "none" && mock.text === "", "切回演示模式后会话号应重新隐藏");

    cdp.close();
  } finally {
    try { child.kill(); } catch (e) { /* 忽略 */ }
    await sleep(300);
    try { fs.rmSync(PROFILE, { recursive: true, force: true }); } catch (e) { /* 忽略 */ }
  }

  say(LF + "════════════════════════════════════════════════════════");
  if (fails === 0) {
    say("[PASS] 真几何自检通过：" + checks + " 条断言，0 失败");
    say("       明细同时写在 " + RESULT);
  } else {
    say("[FAIL] 真几何自检失败：" + checks + " 条断言，失败 " + fails + " 条");
    process.exitCode = 1;
  }
})().catch((e) => {
  say("[FAIL] 自检自身抛异常：" + (e && e.stack ? e.stack : e));
  process.exitCode = 1;
});
