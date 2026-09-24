#!/usr/bin/env node
/**
 * CanalMind 前端自检（**零依赖**，只用 Node 内置模块）
 *
 * 用法：`node frontend/test/check_frontend.js` ｜ 退出码：0 = 全过，1 = 有失败
 *
 * ───────────────────────────────────────────────────────────────────────────
 * 为什么要有这个文件（它补的是一个真实缺口）
 * ───────────────────────────────────────────────────────────────────────────
 * `frontend/index.html` 的正确性原本**只由一次性的临时脚本**验证过：脚本跑完就删了。
 * 后果有两条，都很贵：
 *   ① "前端现在是对的"这句话**再也不能被复查**——别人跑不了，我自己下次也得重写一遍；
 *   ② 以后谁改坏了前端**也不会变红**——契约只活在报告里，没活在检查里。
 * ⇒ 把那些断言固化成仓库里可跑的检查。
 *
 * ───────────────────────────────────────────────────────────────────────────
 * 覆盖四类
 * ───────────────────────────────────────────────────────────────────────────
 *   A 静态红线     需求里明令禁止的视觉手法与字样、外部请求、必备要素、关键数字自洽
 *   B SSE 解码契约 与后端 `SseTextCodec` 互为逆；⚠️ 含"token 要反转义 / structured ⛔ 不反转义"
 *                  这条**不对称**约定（后端只对 token 调 `SseTextCodec::escape`）
 *   C 渲染         用假 DOM 空跑页面的**真实渲染函数**，断言四类卡片、引用、能力边界
 *   D 后端接口路径 用假 fetch 喂一段**被刻意切碎**的 SSE 流：分帧、正文整段显示、
 *                  错误分支、以及**每条会话各一个 sessionId**（这一条是回归护栏：
 *                  修复前全页共用一个 sessionId，会让后端把不同会话的上下文混在一起）
 *
 * ───────────────────────────────────────────────────────────────────────────
 * ⛔ 没有覆盖的（别把本文件当成"前端全验证过了"）
 * ───────────────────────────────────────────────────────────────────────────
 *   · **真实几何**（横向溢出 / 侧边栏宽度 / 金额字号）需要真浏览器——量法见
 *     `docs/进度看板.md` §0.4.13 的一次性 CDP `Runtime.evaluate` 手法，本脚本不替代它。
 *   · **与真后端 + PG + Ollama 的端到端联调**（正文生成需付费），本轮未做。
 *   · 各浏览器的真实渲染差异、无障碍、移动端。
 */

"use strict";

const fs = require("fs");
const path = require("path");
const vm = require("vm");

const LF = String.fromCharCode(10);   // ⚠️ 按项目纪律：真实换行用计算字符，⛔ 不写转义字面量
const BS = String.fromCharCode(92);

const HTML_PATH = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.join(__dirname, "..", "index.html");

let checks = 0;
let fails = 0;
function ok(cond, msg) {
  checks++;
  if (!cond) {
    fails++;
    console.log("  [FAIL] " + msg);
  }
}
function section(title) {
  console.log(LF + "── " + title);
}

/* ============================================================================
   读取与切片
   ============================================================================ */

function readHtml() {
  return fs.readFileSync(HTML_PATH, "utf8");
}

function scriptOf(html) {
  const m = /<script>([\s\S]*?)<\/script>/.exec(html);
  if (!m) {
    throw new Error("index.html 里找不到 <script> 段");
  }
  return m[1];
}

/** 只取"会被人看到"的标记区：<body> 之后、<script> 之前，去掉 HTML 注释。 */
function uiMarkupOf(html) {
  const bodyAt = html.indexOf("<body>");
  const scriptAt = html.indexOf("<script>");
  if (bodyAt < 0 || scriptAt < 0 || scriptAt < bodyAt) {
    throw new Error("index.html 结构不像预期（body/script 位置）");
  }
  return html.slice(bodyAt, scriptAt).replace(/<!--[\s\S]*?-->/g, "");
}

/* ============================================================================
   假 DOM：让页面的渲染函数真的跑起来（⛔ 不是为了"看起来像浏览器"，而是为了能断言产物）
   ============================================================================ */

function makeDom() {
  const els = {};
  function mkEl(id) {
    return {
      id: id,
      innerHTML: "",
      textContent: "",
      value: "",
      disabled: false,
      scrollTop: 0,
      scrollHeight: 100,
      style: {},
      href: "",
      download: "",
      addEventListener: function () {},
      appendChild: function () {},
      removeChild: function () {},
      setAttribute: function () {},
      getAttribute: function () { return null; },
      closest: function () { return null; },
      click: function () {},
      querySelector: function () { return mkEl("sub"); }
    };
  }
  const document = {
    getElementById: function (id) { if (!els[id]) { els[id] = mkEl(id); } return els[id]; },
    querySelector: function () { return mkEl("q"); },
    createElement: function () { return mkEl("tmp"); },
    body: { appendChild: function () {}, removeChild: function () {} },
    addEventListener: function () {}
  };
  return { document: document, els: els };
}

/* ============================================================================
   A. 静态红线
   ============================================================================ */

function checkStatic(html) {
  section("A 静态红线");

  const ui = uiMarkupOf(html);
  const uiNoWs = ui;

  // ── 禁用字样（只查"会被人看到"的标记区；正文里的措辞由 C 段按渲染产物查）──
  [["AI", /AI/], ["智能", /智能/], ["大模型", /大模型/], ["人工智能", /人工智能/],
   ["GPT", /GPT/], ["聊天机器人", /聊天机器人/]].forEach(function (pair) {
    ok(!pair[1].test(uiNoWs), "界面标记区出现禁用字样「" + pair[0] + "」");
  });

  // ── 禁用视觉手法：渐变 / 发光 / 模糊 / 动画 / 大圆角 ──
  const styleBlock = (/<style>([\s\S]*?)<\/style>/.exec(html) || ["", ""])[1];
  ok(!/gradient/i.test(styleBlock), "样式里出现 gradient 渐变");
  ok(!/box-shadow|text-shadow/i.test(styleBlock), "样式里出现 box-shadow / text-shadow");
  ok(!/blur\(/i.test(styleBlock), "样式里出现 blur()");
  ok(!/@keyframes|animation\s*:/i.test(styleBlock), "样式里出现 animation / @keyframes（需求：不要花哨动画）");
  ok(!/transition\s*:/i.test(styleBlock), "样式里出现 transition");
  ok(!/border-radius:\s*(1[0-9]|[2-9][0-9])px/i.test(styleBlock), "出现 ≥10px 的大圆角");

  // ── 禁用元素 ──
  ok(!/<img|<svg|\.svg|avatar|robot/i.test(html), "出现图片 / SVG / 头像 / 机器人元素");

  // ── emoji 与箭头类字形（在界面标记区里不得出现）──
  const glyph = /[\u2600-\u27BF\u2B00-\u2BFF\uFE0F\uD83C-\uDBFF\u2190-\u21FF\u2705\u274C]/;
  const gm = ui.match(glyph);
  ok(!gm, "界面标记区出现 emoji / 箭头类字形：" + (gm ? JSON.stringify(gm[0]) : ""));

  // ── 零外部请求：只允许本机后端地址 ──
  const urls = (html.match(/https?:\/\/[^"'\s)]+/g) || []);
  const foreign = urls.filter(function (u) { return u.indexOf("http://127.0.0.1:8080") !== 0; });
  ok(foreign.length === 0, "出现非本机地址的外部请求：" + JSON.stringify(foreign));

  // ── 必备要素 ──
  ["平陆运河航运助手", "CanalMind", "查过闸费", "查通航规则", "查船期",
   "bge-m3:1024", "glm-5.3-flash", "glm-5.3", "v0.1.0-SNAPSHOT",
   "min-width:1200px", "240px"].forEach(function (needle) {
    ok(html.indexOf(needle) >= 0, "缺少必备要素：" + needle);
  });

  // ── 三个快捷标签都要带 data-q（否则点了没反应）──
  const tags = html.match(/class="tag"[^>]*data-q="[^"]+"/g) || [];
  ok(tags.length === 3, "快捷标签应恰好 3 个且各带 data-q，实测 " + tags.length);

  // ── 关键数字必须自洽（⛔ 不是"看着像"）──
  //    ⚠️ 注意：**算术自洽**不能在 A 段做——A 段还没装载页面，只能拿脚本里的常量自己跟自己对，
  //    那是一条**假检查**（实测变异逃逸：把页面里的 3.30 改成 3.35，假检查照样绿）。
  //    真正的自洽检查放在 E 段，从**页面自己声明的帧数据**里取值再算。
  ok(html.indexOf("10560.00") >= 0, "页面缺少金额 10560.00");
  ok(html.indexOf("CT-过闸费-建材-2000-20260916") >= 0, "页面缺少语料派生的规则码");
  ok(html.indexOf("PLYH-FL-2026-006") >= 0, "页面缺少文号 PLYH-FL-2026-006");
  ok(html.indexOf("[2000,5000)") >= 0, "页面缺少档位 [2000,5000)");
}

/* ============================================================================
   E. 业务数字自洽：从**页面自己声明的帧数据**里取值再算
   ============================================================================ */

function checkBusinessNumbers(page) {
  section("E 业务数字自洽（取值来自页面，⛔ 不用脚本里的常量）");

  const f = page.frameQuoteFee();
  ok(!!f && !!f.tool, "frameQuoteFee() 应返回带 tool 的帧");
  if (!f || !f.tool) { return; }

  const cols = f.tool.columns;
  const row = f.tool.rows[0];
  ok(f.tool.rows.length === 1, "过闸费应恰好一行结果，实测 " + f.tool.rows.length);

  const col = function (name) { return cols.indexOf(name); };
  ["费种", "货类", "计费吨位", "吨位口径", "单价", "单位", "金额(元)", "规则码"].forEach(function (c) {
    ok(col(c) >= 0, "列头缺少 " + c);
  });

  const tonnage = parseFloat(row[col("计费吨位")]);
  const unit = parseFloat(row[col("单价")]);
  const amount = row[col("金额(元)")];
  const ruleCode = row[col("规则码")];
  const unitName = row[col("单位")];
  const basis = row[col("吨位口径")];

  ok(Number.isFinite(tonnage) && Number.isFinite(unit), "计费吨位与单价必须是数字");
  ok(unitName === "元/吨", "计费单位应为 元/吨，实测 " + unitName);
  ok(basis === "载货吨位", "吨位口径应为 载货吨位，实测 " + basis);

  const product = (tonnage * unit).toFixed(2);
  ok(product === amount,
    "金额与「吨位 × 单价」不一致：算得 " + product + "，页面写 " + amount);

  // ── 档位必须与规则码、吨位三者一致 ──
  const band = /\[(\d+),(\d+)\)/.exec(f.tool.facts.join(" "));
  ok(!!band, "facts 里找不到档位区间 [下限,上限)");
  if (band) {
    const lo = band[1];
    const hi = parseInt(band[2], 10);
    ok(ruleCode.indexOf("-" + lo + "-") > 0,
      "规则码里的档位下限与 facts 声明的档位不一致：规则码 " + ruleCode + " vs 档位下限 " + lo);
    ok(tonnage >= parseInt(lo, 10) && tonnage < hi,
      "吨位 " + tonnage + " 不在档位 [" + lo + "," + hi + ") 内（左闭右开）");
  }

  // ── 人读的"计算过程"必须与机器可比的列行说同一件事 ──
  const calc = (f.tool.facts || []).filter(function (s) { return s.indexOf("计算过程") === 0; })[0];
  ok(!!calc, "facts 里缺少「计算过程」一项");
  if (calc) {
    ok(calc.indexOf(String(tonnage)) > 0, "计算过程文字里的吨位与列行不一致：" + calc);
    ok(calc.indexOf(String(unit)) > 0, "计算过程文字里的单价与列行不一致：" + calc);
    ok(calc.indexOf(amount) > 0, "计算过程文字里的金额与列行不一致：" + calc);
  }

  // ── 金额必须能在屏幕上被强调地渲染出来（与 C 段呼应）──
  const html = page.renderFrame({ v: 1, citations: [], tool: f.tool });
  ok(html.indexOf('class="amount"') > 0 && html.indexOf(amount) > 0,
    "金额未被渲染成强调元素");
}

/* ============================================================================
   B. SSE 解码契约：与后端 SseTextCodec 互为逆
   ============================================================================ */

/** 复刻后端 `SseTextCodec.escape` 的语义（单遍扫描：反斜杠→双反斜杠，LF→\n，CR→\r）。 */
function backendEscape(s) {
  let out = "";
  for (let i = 0; i < s.length; i++) {
    const c = s.charAt(i);
    if (c === BS) { out += BS + BS; }
    else if (c === LF) { out += BS + "n"; }
    else if (c === "\r") { out += BS + "r"; }
    else { out += c; }
  }
  return out;
}

function checkSseContract(page) {
  section("B SSE 解码契约");

  const unescapeSse = page.unescapeSse;
  const parseSseBlock = page.parseSseBlock;

  // ── 往返：nasty 字符串经"后端转义"后，前端必须还原得一字不差 ──
  const cases = [
    "plain",
    "a" + LF + "b",
    "a\rb",
    "C:" + BS + "new" + BS + "path",
    "JSON 示例 {" + LF + "} 与字面量 " + BS + "n",
    LF + LF,
    BS,
    "混合：" + LF + BS + "\r入",
    "",
    "真实换行" + LF + "后面还有" + BS + BS + "两个反斜杠" + LF
  ];
  cases.forEach(function (s, i) {
    const back = unescapeSse(backendEscape(s));
    ok(back === s, "往返[" + i + "] 不一致：期望 " + JSON.stringify(s) + " 实得 " + JSON.stringify(back));
  });

  // ── 已知映射 ──
  ok(unescapeSse("a" + BS + "nb") === "a" + LF + "b", "已知映射：反斜杠+n 应还原成换行");
  ok(unescapeSse(BS + BS) === BS, "已知映射：双反斜杠应还原成一个反斜杠");
  ok(unescapeSse(BS + "q") === BS + "q", "未知转义应原样保留（向前兼容）");
  ok(unescapeSse(BS) === BS, "末尾孤立反斜杠应原样保留");

  // ── 分帧 ──
  const e1 = parseSseBlock("event: token" + LF + "data: 你好" + BS + "n世界");
  ok(e1 && e1.event === "token" && e1.data === "你好" + BS + "n世界",
    "parseSseBlock 应取出原始 data（⛔ 不在解析层反转义）");
  ok(parseSseBlock(": heartbeat") === null, "注释行应被忽略");
  const e4 = parseSseBlock("data: a" + LF + "data: b");
  ok(e4 && e4.data === "a" + LF + "b", "多 data 行应以换行拼接");

  // ── ⚠️ 契约的**不对称**：后端只对 token 调 escape，structured 是**原样 JSON** ──
  //    这一条最容易写错：如果前端把 structured 也反转义，含反斜杠的 JSON 会解析失败或悄悄变形。
  const hostile = { v: 1, facts: ["含换行" + LF + "的事实", "路径 C:" + BS + "new" + BS + "path"] };
  const rawJson = JSON.stringify(hostile);
  ok(rawJson.indexOf(LF) < 0, "JSON.stringify 的结果必须是单行（SSE 载荷不得含裸换行）");
  const parsed = JSON.parse(parseSseBlock("event: structured" + LF + "data: " + rawJson).data);
  ok(parsed.facts[0] === "含换行" + LF + "的事实", "structured 载荷应原样 JSON.parse（换行要还原）");
  ok(parsed.facts[1] === "路径 C:" + BS + "new" + BS + "path", "structured 载荷里的反斜杠不得被二次处理");
}

/* ============================================================================
   C. 渲染：用假 DOM 空跑页面的真实渲染函数
   ============================================================================ */

function checkRendering(page) {
  section("C 渲染");

  const BAN = /[\u2600-\u27BF\u2B00-\u2BFF\uFE0F\uD83C-\uDBFF\u2190-\u21FF\u2705\u274C]/;
  const artifacts = [];

  page.SESSIONS.forEach(function (s) {
    page.state.activeId = s.id;
    page.drawSessions();
    page.drawTopic();
    page.drawConversation();
    artifacts.push(["会话 " + s.id, page.els.convInner.innerHTML]);
  });

  const probes = [
    ["查过闸费", "建材类货物，载货 3200 吨，2026-10-12 过闸，过闸费多少？"],
    ["引航费问金额", "那引航费呢？多少钱？"],
    ["查船期", "下周三有哪些船期靠泊？"],
    ["查通航规则", "危险货物运输申报要提前多久提交？"],
    ["费率版本", "2027 年 1 月以后过闸，过闸费按哪版费率表算？"],
    ["停泊费问规则", "停泊费停靠不足一天怎么算？"],
    ["无关问题", "今天天气怎么样？"]
  ];
  probes.forEach(function (p) {
    const r = page.mockReply(p[1]);
    artifacts.push([p[0], page.renderMessage({ role: "sys", text: r.text, frame: r.frame })]);
  });

  // 四类卡片各渲染一次
  const okFrame = page.renderFrame({
    v: 1, citations: [],
    tool: { name: "query_traffic", status: "OK", columns: ["分组", "金额(元)", "运单数"],
      rows: [["建材", "1284930.00", "412"], ["能源", "980120.00", "305"]],
      facts: [], question: null, degradeKind: null }
  });
  artifacts.push(["OK 多行表格", okFrame]);
  const degraded = page.renderFrame({
    v: 1, citations: [],
    tool: { name: "x", status: "DEGRADED", columns: [], rows: [],
      facts: ["结构化通道降级（TOOL_NONE）：不属于工具集"], question: null, degradeKind: "TOOL_NONE" }
  });
  artifacts.push(["DEGRADED", degraded]);
  const clarify = page.renderFrame({
    v: 1, citations: [],
    tool: { name: "quote_fee", status: "NEEDS_CLARIFICATION", columns: [], rows: [],
      facts: [], question: "请补充船舶总吨位。", degradeKind: null }
  });
  artifacts.push(["NEEDS_CLARIFICATION", clarify]);

  // ── 全局反例断言：产物里不得出现这些"看起来正常其实错了"的东西 ──
  artifacts.forEach(function (a) {
    const label = a[0], h = a[1];
    ok(h.length > 0, label + "：渲染产物为空");
    ok(!BAN.test(h), label + "：渲染产物里出现禁用字形");
    ok(h.indexOf("undefined") < 0, label + "：渲染产物里出现 undefined");
    ok(h.indexOf("NaN") < 0, label + "：渲染产物里出现 NaN");
    ok(h.indexOf("**") < 0, label + "：** 强调标记漏到页面上（renderText 未生效）");
    ok(h.indexOf("null") < 0 || label.indexOf("会话") === 0, label + "：渲染产物里出现 null 字样");
  });

  function pick(name) {
    return artifacts.filter(function (a) { return a[0] === name; })[0][1];
  }

  const q = pick("查过闸费");
  ok(q.indexOf('class="amount"') > 0, "过闸费卡片缺金额元素");
  ok(q.indexOf("10560.00") > 0, "过闸费卡片缺金额 10560.00");
  ok(q.indexOf("CT-过闸费-建材-2000-20260916") > 0, "过闸费卡片缺规则码");
  ok(q.indexOf('sup class="cite"') > 0, "过闸费回答缺上标引用");
  ok(q.indexOf("引用来源") > 0, "过闸费回答缺引用来源区");
  ok(q.indexOf("能力边界") > 0, "未实现条款应如实显示在卡片上");
  ok(pick("引航费问金额").indexOf("card clarify") > 0, "引航费应渲染成追问卡片");
  ok(pick("查船期").indexOf("查询范围内无数据") > 0, "船期应如实渲染成无数据卡片");
  ok(pick("查船期").indexOf("card status") > 0, "无数据卡片样式类应为 status");
  ok(pick("费率版本").indexOf("PLYH-FL-2026-006") > 0, "版本回答缺引用文号");
  ok(pick("费率版本").indexOf("第十一条") > 0, "版本回答缺引用条款");
  ok(okFrame.indexOf('<table class="res">') > 0, "多行结果应渲染成表格");
  ok(okFrame.indexOf("amount-cell") > 0, "表格里的金额列应有强调样式");
  ok(degraded.indexOf("本次未给出确定性结果") > 0, "降级态卡片标题不符");
  ok(clarify.indexOf("请补充船舶总吨位。") > 0, "追问态应显示 question 文本");
}

/* ============================================================================
   D. 后端接口路径：假 fetch + 被刻意切碎的 SSE 流
   ============================================================================ */

function makeManualReader(chunks) {
  const pending = [];
  let i = 0;
  return {
    read: function () {
      if (i < chunks.length) {
        const value = Buffer.from(chunks[i++], "utf8");
        return new Promise(function (res) { pending.push(function () { res({ done: false, value: value }); }); });
      }
      return new Promise(function (res) { pending.push(function () { res({ done: true }); }); });
    },
    /** 放行最老的一次 read；返回是否真的放行了（false = 没有待放行的 read） */
    step: function () {
      const f = pending.shift();
      if (f) { f(); return true; }
      return false;
    }
  };
}

const flush = function () { return new Promise(function (r) { setImmediate(r); }); };
async function flushTimes(n) {
  for (let i = 0; i < n; i++) { await flush(); }
}

async function checkBackendPath(page, els) {
  section("D 后端接口路径（假 fetch）");

  const requests = [];
  let plan = null;   // {chunks:[...]} 或 {status:500}

  global.fetch = function (url) {
    requests.push(url);
    const p = plan;
    if (p && p.reject) {
      // fetch 层被拦：跨域被浏览器拦下 / 后端没起 / 断网 —— 浏览器给的都是同一个 TypeError
      return Promise.reject(new TypeError(p.reject));
    }
    if (p && p.status && p.status !== 200) {
      return Promise.resolve({ ok: false, status: p.status, body: null });
    }
    if (p && p.reader) {
      return Promise.resolve({ ok: true, status: 200, body: { getReader: function () { return p.reader; } } });
    }
    const chunks = (p && p.chunks) || [];
    let i = 0;
    return Promise.resolve({
      ok: true, status: 200,
      body: {
        getReader: function () {
          return {
            read: function () {
              if (i < chunks.length) {
                return Promise.resolve({ done: false, value: Buffer.from(chunks[i++], "utf8") });
              }
              return Promise.resolve({ done: true });
            }
          };
        }
      }
    });
  };

  // ── 造一段"真"的 SSE：meta → structured（原样 JSON）→ token（已转义）→ done ──
  const hostileFrame = {
    v: 1,
    route: { channel: "STRUCTURED", ruleId: "R1-数值诉求-结构化", reason: "命中数值诉求" },
    tool: {
      name: "quote_fee", status: "OK",
      columns: ["费种", "金额(元)", "规则码"],
      rows: [["过闸费", "10560.00", "CT-过闸费-建材-2000-20260916"]],
      facts: ["含换行的事实：" + LF + "第二行", "路径 C:" + BS + "new" + BS + "path"],
      question: null, degradeKind: null
    },
    citations: [page.citationOf("过闸费费率表（2026版）", "PLYH-FL-2026-006", "第五条",
      "【过闸费标准】", ["第二章 收费标准"], "建材类按吨位分档计收")]
  };
  const frameJson = JSON.stringify(hostileFrame);
  const tokenA = "过闸费 10560.00 元";
  const tokenB = "（第一行" + LF + "第二行）";   // ⚠️ token 里带真换行 ⇒ 必须被转义后才上线路

  function sseStream(eol) {
    return "event: meta" + eol + 'data: {"sessionId":"x"}' + eol + eol
      + "event: structured" + eol + "data: " + frameJson + eol + eol
      + "event: token" + eol + "data: " + backendEscape(tokenA) + eol + eol
      + "event: token" + eol + "data: " + backendEscape(tokenB) + eol + eol
      + "event: done" + eol + "data: {}" + eol + eol;
  }

  /** 刻意切碎：切在事件中间、转义序列中间、空行中间 —— SSE 解析器就是在这里翻车的 */
  function shred(text) {
    const sizes = [7, 13, 3, 29, 5, 41, 2, 17];
    const out = [];
    let i = 0, k = 0;
    while (i < text.length) {
      const n = sizes[k++ % sizes.length];
      out.push(text.slice(i, i + n));
      i += n;
    }
    return out;
  }

  const expectedText = tokenA + tokenB;

  // ── D1：LF 流，切碎 ──
  async function runOnce(eol, sessionId, label) {
    page.state.dataSource = "api";
    page.state.activeId = sessionId;
    plan = { chunks: shred(sseStream(eol)) };
    requests.length = 0;
    const before = (page.state.history[sessionId] || []).length;
    page.submit("建材3200吨过闸费多少");
    await flushTimes(40);

    const url = requests[0] || "";
    ok(url.indexOf("/api/chat/stream?question=") > 0, label + "：请求 URL 不像 SSE 接口");
    ok(url.indexOf("sessionId=" + encodeURIComponent(page.sessionIdFor(sessionId))) > 0,
      label + "：请求里的 sessionId 与该会话的后端标识不一致");

    const msgs = page.state.history[sessionId];
    const sys = msgs[msgs.length - 1];
    ok(msgs.length === before + 2, label + "：应新增「用户提问 + 系统回答」两条，实测 " + (msgs.length - before));
    ok(sys.role === "sys", label + "：最后一条应为系统回答，实测 " + sys.role);
    ok(sys.text === expectedText, label + "：正文不完整或被重复：" + JSON.stringify(sys.text));
    // ⚠️ 帧断言必须 null 安全：帧没渲染时不要在这里抛异常，否则后续断言全部跑不到
    const tool = sys.frame && sys.frame.tool;
    ok(!!tool, label + "：结构化帧未渲染");
    if (tool) {
      ok(tool.rows[0][1] === "10560.00", label + "：帧里的金额不对：" + tool.rows[0][1]);
      ok(tool.facts[0] === "含换行的事实：" + LF + "第二行", label + "：帧里含真换行的事实未还原");
      ok(tool.facts[1] === "路径 C:" + BS + "new" + BS + "path", label + "：帧里的反斜杠被改坏");
    }
    ok(page.state.busy === false, label + "：流结束后 busy 未复位（发送键会一直禁用）");
    ok(els.send.disabled === false, label + "：流结束后发送键未恢复可用");
  }

  await runOnce(LF, "s1", "LF");
  await runOnce("\r\n", "s2", "CRLF");   // 兼容 CRLF 服务端

  // ── D2：正文必须**整段**出现，⛔ 不许边收边渲染（需求：不要逐字打字机）──
  {
    const text = sseStream(LF);
    // 切成"帧 + 第一个 token"和"其余"，手动放行前一半
    const cutAt = text.indexOf("event: token");
    const reader = makeManualReader([text.slice(0, cutAt), text.slice(cutAt)]);
    page.state.dataSource = "api";
    page.state.activeId = "s3";
    plan = { reader: reader };
    page.submit("建材3200吨过闸费多少");
    await flushTimes(10);   // 让 fetch 的 promise 落地、pump 挂上第一次 read
    reader.step();          // 放行 chunk0 = meta + structured
    await flushTimes(10);

    // 此时 structured 已到、正文一片都还没到
    let msgs = page.state.history["s3"];
    let sys = msgs[msgs.length - 1];
    ok(sys.text === "", "帧到达时正文应为空（正文不得边收边渲染），实测 " + JSON.stringify(sys.text));
    ok(!!sys.frame, "帧应先于正文渲染出来");

    // 放行剩下的（含两个 token 与 done）
    for (let i = 0; i < 12; i++) { reader.step(); await flush(); }
    await flushTimes(40);
    msgs = page.state.history["s3"];
    sys = msgs[msgs.length - 1];
    ok(sys.text === expectedText, "全部到达后正文应一次给全：" + JSON.stringify(sys.text));
  }

  // ── D3：HTTP 错误分支 ──
  {
    page.state.dataSource = "api";
    page.state.activeId = "s4";
    plan = { status: 500 };
    page.submit("触发错误");
    await flushTimes(20);
    const msgs = page.state.history["s4"];
    const last = msgs[msgs.length - 1];
    ok(last.role === "error", "HTTP 500 时应渲染错误行，实测 " + last.role);
    ok(last.text.indexOf("500") > 0, "错误行应带上状态码，实测 " + JSON.stringify(last.text));
    ok(!/跨域|CORS/.test(last.text),
      "HTTP 500 是后端**答复了**的状态码，⛔ 不得说成跨域被拦（两类成因必须分开），实测 "
        + JSON.stringify(last.text));
    ok(page.state.busy === false, "出错后 busy 未复位（发送键会永久禁用）");
    ok(els.send.disabled === false, "出错后发送键未恢复可用");
  }

  // ── D4：⭐ sessionId 必须**每条会话各一个**（这是本轮修复的回归护栏）──
  {
    const ids = ["s1", "s2", "s3", "s4"].map(function (id) { return page.sessionIdFor(id); });
    ok(new Set(ids).size === 4, "4 条会话应各有各的 sessionId，实测只有 " + new Set(ids).size + " 个不同值");
    ok(page.sessionIdFor("s1") === page.sessionIdFor("s1"),
      "同一会话重复取 sessionId 必须稳定（否则多轮上下文会断）");
    ok(page.sessionIdFor("s1") !== page.sessionIdFor("s2"), "不同会话不得共用 sessionId");
    ids.forEach(function (id, i) {
      ok(id.indexOf("web-") === 0, "sessionId[" + i + "] 前缀异常：" + id);
    });
  }

  // ── D5：⭐ fetch 层被拦时，文案必须指向跨域（2026-09-24 真实故障的回归护栏）──
  //     那次故障：后端没发 CORS 头 ⇒ 浏览器 TypeError: Failed to fetch；
  //     而页面当时显示「接口不可用：无法连接 …」，把「跨域被拦」读成了「连不上」，
  //     排查方向被带偏。⇒ 现在文案必须把跨域列为可能原因，并保留原文便于搜索。
  {
    page.state.dataSource = "api";
    page.state.activeId = "s2";
    plan = { reject: "Failed to fetch" };
    page.submit("你好");
    await flushTimes(20);
    const msgs = page.state.history["s2"];
    const last = msgs[msgs.length - 1];
    ok(last.role === "error", "fetch 被拦时应渲染错误行，实测 " + last.role);
    ok(/跨域|CORS/.test(last.text),
      "文案必须指出「可能是跨域被拦」——⛔ 不能只说无法连接，实测 " + JSON.stringify(last.text));
    ok(last.text.indexOf("Failed to fetch") > 0,
      "原始错误文本要保留（用户可拿去搜索/贴日志），实测 " + JSON.stringify(last.text));
    ok(page.state.busy === false, "fetch 被拦后 busy 未复位");
    ok(els.send.disabled === false, "fetch 被拦后发送键未恢复可用");
  }
}

/* ============================================================================
   主流程
   ============================================================================ */

async function main() {
  const html = readHtml();
  console.log("前端自检：" + HTML_PATH);
  console.log("字节 " + Buffer.byteLength(html, "utf8") + " / 行 " + html.split(LF).length);

  checkStatic(html);

  // 装载页面：假 DOM + 假 fetch，页面脚本在**本进程的同一个 realm** 里执行
  const dom = makeDom();
  const els = dom.els;
  global.document = dom.document;
  global.window = {
    setInterval: function () { return 0; },
    // ⚠️ 同步执行回调：演示模式那条 setTimeout 路径要能被确定性地断言
    setTimeout: function (fn) { if (typeof fn === "function") { fn(); } return 0; }
  };
  global.setInterval = function () { return 0; };
  global.Blob = function () {};
  global.URL = { createObjectURL: function () { return ""; }, revokeObjectURL: function () {} };
  global.fetch = function () { return Promise.reject(new Error("未安装假 fetch")); };

  vm.runInThisContext(scriptOf(html), { filename: "index.html:<script>" });

  const page = {
    state: state, SESSIONS: SESSIONS, els: els,
    unescapeSse: unescapeSse, parseSseBlock: parseSseBlock,
    mockReply: mockReply, renderMessage: renderMessage, renderFrame: renderFrame,
    citationOf: citationOf, sessionIdFor: sessionIdFor, submit: submit,
    frameQuoteFee: frameQuoteFee,
    drawSessions: drawSessions, drawTopic: drawTopic, drawConversation: drawConversation
  };

  checkSseContract(page);
  checkRendering(page);
  checkBusinessNumbers(page);
  await checkBackendPath(page, els);

  console.log(LF + "════════════════════════════════════════════════════════");
  if (fails === 0) {
    console.log("[PASS] 前端自检通过：" + checks + " 条断言，0 失败");
    console.log("       ⚠️ 未覆盖：真实几何（需真浏览器）与真后端端到端联调，见文件头说明。");
    process.exit(0);
  } else {
    console.log("[FAIL] 前端自检失败：" + checks + " 条断言，失败 " + fails + " 条");
    process.exit(1);
  }
}

main().catch(function (e) {
  console.error("[FAIL] 自检自身抛异常：" + (e && e.stack ? e.stack : e));
  process.exit(1);
});
