package com.canalmind.chat.session;

import com.canalmind.chat.session.ContextAssembler.ContextBudget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多轮上下文（第 4 周第 2 项，C-38）的测试。
 *
 * <p><b>为什么核心那条用例要把摘要器做成"把数字全丢掉"的桩</b>：C-38③ 的硬约束是
 * 「压缩不得吃掉结构化参数」，而这条约束**只要在别处碰巧也出现同一个数字就会假通过**。
 * 所以 {@code L4-008} 用例刻意让摘要器返回一段**不含任何数字**的摘抄，
 * 并让应答正文也不重复那个吨位，最后断言 {@code 1520} 在整个上下文里**只出现一次**、
 * 且出现在"最近原始参数"块里。若按"上下文里含 1520 就算过"来写，
 * 参数块被吃掉时用例照样会绿——本项目对此有专门记录
 * （`docs/工程复盘/工程方法/验证工具本身也要被验证/查重探针的误报与假PASS/`）。
 *
 * <p><b>⚠️ 本文件能证明的与不能证明的（诚实边界）</b>：
 * 压缩路径**不是靠金标准集跑出来的**——本项目数据集最长对话只有 2 轮
 * （实测 {@code docs/评测报告/golden-set-v3.json} 120 条里对话长度为 2 的 25 条、
 * 其余 95 条无对话；{@code backend/scripts/golden-extra-items.json} 80 条里同样 25 条），
 * 而默认 {@code keepRecentTurns} 就是 2 ⇒ 现实预算下压缩分支从不被走到。
 * 因此压缩的正确性**只由下面"强制极小预算"（预算给 1）的用例证明**，
 * ⛔ 不得据此声称"压缩已在评测中生效"（`AGENTS.md` §6 第 1、3 条）。
 */
class ContextAssemblerTest {

    /** 换行用计算字符，⛔ 不用转义字面量（`AGENTS.md` §7.2 硬规定，夹具同样适用）。 */
    private static final String LF = String.valueOf((char) 10);

    private static final String SYSTEM = "你是平陆运河航运物流业务助手，只依据规则语料与受控工具作答。";

    /** 强制极小预算：一定小于任何真实上下文的估算值，用于**主动**触发压缩（C-38② 的「可强制触发」）。 */
    private static final int FORCE_COMPACT_TOKENS = 1;

    // ── 1. 消息顺序 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 顺序固定：基础提示 → 会话摘要 → 最近原始参数 → 各轮问答 → 新问题")
    void messageOrderIsFixed() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        // 已有一版摘要且边界为 3：第 1–2 轮由摘要代表，第 3 轮仍是原文（带结构化参数）
        store.save(new SessionState("s-order", List.of(
                turn(1, "GX00042 是条什么船？总吨位多少？", "多用途船。", "query_vessel(vessel_id=GX00042)"),
                turn(2, "它跑哪条航线？", "平陆运河。", ""),
                turn(3, "那它的载重吨呢？", "载重吨 2300。", "query_vessel(vessel_id=GX00042,deadweight=2300)")),
                "更早：用户问过 GX00042 的基本情况。", 3));
        ContextAssembler assembler = assembler(store, SummarizerStub.returning("不会用到"), 100_000, 2);

        ContextAssembler.AssembledContext ctx = assembler.assemble("s-order", "它跟这笔港杂费有没有关系？");

        assertThat(ctx.messages()).extracting(ContextMessage::role)
                .containsExactly("system", "system", "system", "user", "assistant", "user");

        assertThat(ctx.messages().get(0).content())
                .as("第 1 条必须是基础提示本身，且逐字")
                .isEqualTo(SYSTEM);
        assertThat(ctx.messages().get(1).content())
                .as("第 2 条是摘要块")
                .startsWith(ContextAssembler.SUMMARY_HEADER)
                .contains("更早：用户问过 GX00042 的基本情况。");
        assertThat(ctx.messages().get(2).content())
                .as("第 3 条是最近原始参数块，且只带仍是原文的那些轮次")
                .startsWith(ContextAssembler.RAW_PARAMS_HEADER)
                .contains("query_vessel(vessel_id=GX00042,deadweight=2300)")
                .doesNotContain("第 2 轮：");
        assertThat(ctx.messages().get(3).content()).isEqualTo("那它的载重吨呢？");
        assertThat(ctx.messages().get(4).content()).isEqualTo("载重吨 2300。");
        assertThat(ctx.messages().get(5).content()).isEqualTo("它跟这笔港杂费有没有关系？");

        // 顺序本身就是设计：摘要块必须在原始参数块之前，否则摘要在模型眼里成了"最新印象"
        assertThat(indexOfMessageStartingWith(ctx.messages(), ContextAssembler.SUMMARY_HEADER))
                .as("摘要在前、原始参数在后（C-38③ 防「模型拿摘要复述当参数」）")
                .isLessThan(indexOfMessageStartingWith(ctx.messages(), ContextAssembler.RAW_PARAMS_HEADER));
    }

    // ── 2. 硬约束：压缩不得吃掉结构化参数 ──────────────────────────────────

    @Test
    @DisplayName("★ 压缩不得吃掉结构化参数：L4-008 第一轮的 1520 压缩后仍逐字在上下文里")
    void recentStructuredParamsSurviveCompaction() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        // 夹具说明：真实样本 L4-008 只有 2 轮（docs/评测报告/golden-set-v3.json 第 1773 行起，
        // 总吨位 1520 在第 1762 行），而"2 轮 + keepRecentTurns=2"根本不会发生折叠
        // ⇒ 这里补两轮更早的会话来制造"可折叠的更早段"，让携带 1520 的那一轮真的被折叠越过边界。
        store.save(new SessionState("s-l4008", List.of(
                SessionTurn.of(1, "费率表放哪了？", "在规则语料里。", "retrieval"),
                SessionTurn.of(2, "引航费按什么分档？", "按船舶总吨位。", "retrieval"),
                // ↓ 这一轮就是 L4-008 的真实第 1 轮：问题原文与参数原文都照抄数据集
                turn(3, "GX00042 是条什么船？总吨位多少？",
                        "多用途船，吨位见本轮的查询结果。",
                        "query_vessel(vessel_id=GX00042,gross_tonnage=1520)")), "", 0));
        // 摘要器刻意"吃掉数字"：返回的摘要里一个数字都没有
        SummarizerStub stub = SummarizerStub.returning("用户先问过费率位置与分档口径，助手按规则语料作答。");
        ContextAssembler assembler = assembler(store, stub, FORCE_COMPACT_TOKENS, 1);

        ContextAssembler.AssembledContext ctx =
                assembler.assemble("s-l4008", "还有哪些跟它一个船型、但比它大的？");

        assertThat(ctx.compacted()).as("预算被压到 1，压缩必须真的发生").isTrue();
        assertThat(stub.calls()).isEqualTo(1);
        assertThat(stub.lastFolded()).extracting(SessionTurn::index)
                .as("只折叠更早的 1、2 轮")
                .containsExactly(1, 2);
        assertThat(ctx.summaryUsed())
                .as("摘要里没有 1520，故下面断言命中只可能来自原始参数块")
                .doesNotContain("1520");
        assertThat(ctx.rawTurns()).extracting(SessionTurn::index)
                .as("最近 1 轮必须是原文，含其 structuredParamsJson")
                .containsExactly(3);

        String paramsBlock = ctx.messages().stream()
                .filter(m -> ContextMessage.ROLE_SYSTEM.equals(m.role()))
                .filter(m -> m.content().startsWith(ContextAssembler.RAW_PARAMS_HEADER))
                .findFirst()
                .orElseThrow(() -> new AssertionError("压缩后上下文里没有「最近原始参数」块"))
                .content();
        assertThat(paramsBlock)
                .as("参数必须逐字（⛔ 不是「1520 左右」这类复述）")
                .contains("query_vessel(vessel_id=GX00042,gross_tonnage=1520)")
                .contains("1520");
        assertThat(ctx.messages().stream().filter(m -> m.content().contains("1520")).count())
                .as("1520 只允许出现在原始参数块里，否则这条断言失去区分力")
                .isEqualTo(1);
    }

    // ── 3. 折叠与边界推进 ──────────────────────────────────────────────────

    @Test
    @DisplayName("压缩把更早轮次折进摘要：边界前进、被折原文去掉、近期原文与参数逐字保留")
    void compactionFoldsEarlierTurnsAndAdvancesBoundary() throws Exception {
        SessionState state = new SessionState("s-fold", List.of(
                turn(1, "问一", "答一", "tool_a(x=1)"),
                turn(2, "问二", "答二", "tool_b(y=2)"),
                turn(3, "问三", "答三", "tool_c(z=3)"),
                turn(4, "问四", "答四", "tool_d(w=4)")), "", 0);

        SummarizerStub stub = SummarizerStub.returning("更早两轮的摘要");
        SessionState folded = new ContextCompactor(stub).compact(state, 2);

        assertThat(stub.calls()).isEqualTo(1);
        assertThat(stub.lastFolded()).extracting(SessionTurn::index).containsExactly(1, 2);
        assertThat(stub.lastPreviousSummary()).as("首次摘要：旧摘要是空串，⛔ 不是 null").isEmpty();
        assertThat(folded.summarizedUpTo()).as("折到第 2 轮 ⇒ 边界前进到 3").isEqualTo(3);
        assertThat(folded.summary()).isEqualTo("更早两轮的摘要");
        assertThat(folded.rawTurns()).extracting(SessionTurn::index)
                .as("边界之上的轮次顺序不变")
                .containsExactly(3, 4);
        assertThat(folded.rawTurns()).extracting(SessionTurn::structuredParamsJson)
                .as("近期参数逐字保留")
                .containsExactly("tool_c(z=3)", "tool_d(w=4)");
        assertThat(folded.turns()).extracting(SessionTurn::index)
                .as("已折叠的原文不再留在状态里：否则「上下文里有几轮原文」会取决于谁先谁后")
                .containsExactly(3, 4);

        // 再压一次：边界继续前进，且旧摘要必须被带进摘要器入参（否则更早的内容会一次丢一段）
        SessionState twice = new ContextCompactor(stub).compact(folded, 1);
        assertThat(stub.lastFolded()).extracting(SessionTurn::index).containsExactly(3);
        assertThat(stub.lastPreviousSummary()).isEqualTo("更早两轮的摘要");
        assertThat(twice.summarizedUpTo()).isEqualTo(4);
        assertThat(twice.rawTurns()).extracting(SessionTurn::index).containsExactly(4);

        // 装配侧同样体现：被折的轮次不再作为原文出现，摘要块用的是折叠后的摘要
        InMemorySessionStore store = new InMemorySessionStore();
        store.save(state);
        SummarizerStub assemblerStub = SummarizerStub.returning("更早两轮的摘要");
        ContextAssembler.AssembledContext ctx =
                assembler(store, assemblerStub, FORCE_COMPACT_TOKENS, 2).assemble("s-fold", "新问题");

        assertThat(ctx.compacted()).isTrue();
        assertThat(ctx.summaryUsed()).isEqualTo("更早两轮的摘要");
        assertThat(ctx.messages()).extracting(ContextMessage::content)
                .contains(ContextAssembler.SUMMARY_HEADER + LF + "更早两轮的摘要");
        assertThat(ctx.messages()).extracting(ContextMessage::content)
                .as("被折进摘要的 1、2 轮不应再作为原文出现")
                .doesNotContain("问一", "答一", "问二", "答二")
                .contains("问三", "答三", "问四", "答四", "新问题");
    }

    // ── 4. 未超预算不触发（含反向对照） ────────────────────────────────────

    @Test
    @DisplayName("未超预算时摘要器调用次数 == 0（计数桩），且反向对照：强制小预算下必被调用")
    void summarizerNotCalledWithinBudgetAndCalledWhenForced() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        store.save(new SessionState("s-budget", List.of(
                turn(1, "问一", "答一", "tool_a(x=1)"),
                turn(2, "问二", "答二", "tool_b(y=2)"),
                turn(3, "问三", "答三", "tool_c(z=3)")), "", 0));
        SummarizerStub stub = SummarizerStub.returning("压缩后的摘要");

        ContextAssembler.AssembledContext wide =
                assembler(store, stub, 100_000, 1).assemble("s-budget", "新问题");
        assertThat(wide.compacted()).isFalse();
        assertThat(stub.calls()).as("预算给足却调了摘要器，说明预算判断根本没生效").isZero();
        assertThat(wide.note()).contains("未触发压缩");
        assertThat(wide.rawTurns()).extracting(SessionTurn::index).containsExactly(1, 2, 3);

        // 反向对照：同一个桩、同一份会话，只把预算压到 1 —— 必须被调用。
        // 没有这一半，"调用次数 == 0"可能只是因为桩/链路压根没接上（空断言）。
        ContextAssembler.AssembledContext forced =
                assembler(store, stub, FORCE_COMPACT_TOKENS, 1).assemble("s-budget", "新问题");
        assertThat(stub.calls()).as("强制小预算下摘要器仍未被调用").isEqualTo(1);
        assertThat(forced.compacted()).isTrue();
        assertThat(forced.note()).contains("已压缩");
    }

    // ── 5. 摘要失败不丢历史 ────────────────────────────────────────────────

    @Test
    @DisplayName("摘要器抛异常时不丢历史：原文全部保留，note 里能分辨出「摘要失败」")
    void summarizerFailureKeepsHistoryAndIsVisibleInNote() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        store.save(new SessionState("s-fail", List.of(
                turn(1, "问一", "答一", "tool_a(x=1)"),
                turn(2, "问二", "答二", "tool_b(y=2)"),
                turn(3, "问三", "答三", "tool_c(z=3)")), "", 0));
        SummarizerStub stub = SummarizerStub.failing(new IOException("桩：摘要调用超时"));

        ContextAssembler.AssembledContext ctx =
                assembler(store, stub, FORCE_COMPACT_TOKENS, 1).assemble("s-fail", "新问题");

        assertThat(stub.calls()).isEqualTo(1);
        assertThat(ctx.compacted()).isFalse();
        assertThat(ctx.note())
                .as("失败必须能从 note 里一眼分辨，⛔ 不许静默按「压缩成功但没内容」处理")
                .contains("摘要失败")
                .contains("IOException")
                .contains("未丢弃任何轮次");
        assertThat(ctx.rawTurns()).extracting(SessionTurn::index)
                .as("失败时一轮都不许丢")
                .containsExactly(1, 2, 3);
        assertThat(ctx.messages()).extracting(ContextMessage::content).contains("问一", "答一", "问二", "答二", "问三", "答三");
        assertThat(ctx.summaryUsed()).as("失败的那次没有留下半成品摘要").isEmpty();
        assertThat(ctx.messages()).extracting(ContextMessage::content)
                .noneMatch(content -> content.startsWith(ContextAssembler.SUMMARY_HEADER));
    }

    // ── 6. 保留轮数大于总轮数 ──────────────────────────────────────────────

    @Test
    @DisplayName("keepRecentTurns 大于总轮数：既不压缩也不报错，且压缩器原样返回同一状态")
    void keepRecentTurnsLargerThanTurnCountDoesNotCompactOrThrow() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        store.save(new SessionState("s-keep", List.of(
                turn(1, "问一", "答一", "tool_a(x=1)"),
                turn(2, "问二", "答二", "tool_b(y=2)")), "", 0));
        SummarizerStub stub = SummarizerStub.returning("不会被用到");

        ContextAssembler.AssembledContext ctx =
                assembler(store, stub, FORCE_COMPACT_TOKENS, 5).assemble("s-keep", "新问题");

        assertThat(stub.calls()).isZero();
        assertThat(ctx.compacted()).isFalse();
        assertThat(ctx.rawTurns()).extracting(SessionTurn::index).containsExactly(1, 2);
        assertThat(ctx.note()).as("要说明「超预算但无可折叠」，而不是假装压过了").contains("无可折叠");

        SessionState state = store.load("s-keep");
        assertThat(new ContextCompactor(stub).compact(state, 5))
                .as("无可折叠时原样返回（调用方据此判断边界有没有前进）")
                .isSameAs(state);
    }

    // ── 7. 配置非法必须当场炸 ──────────────────────────────────────────────

    @Test
    @DisplayName("ContextCompactor：keepRecentTurns < 1 抛 IllegalStateException，且不惊动摘要器")
    void compactorRejectsNonPositiveKeepRecentTurns() {
        SummarizerStub stub = SummarizerStub.returning("不该被调用");
        ContextCompactor compactor = new ContextCompactor(stub);
        SessionState state = new SessionState("s-neg", List.of(
                turn(1, "问一", "答一", "tool_a(x=1)"),
                turn(2, "问二", "答二", "tool_b(y=2)")), "", 0);

        assertThatThrownBy(() -> compactor.compact(state, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keepRecentTurns");
        assertThatThrownBy(() -> compactor.compact(state, -1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keepRecentTurns");
        assertThat(stub.calls()).as("配置非法必须在调用模型之前炸掉").isZero();

        // 空摘要同样拒绝：折叠后原文会被丢掉，空摘要等于把轮次折没了
        assertThatThrownBy(() -> new ContextCompactor(SummarizerStub.returning("   ")).compact(state, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空摘要");
    }

    // ── 8. 不含空内容 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("messages 里没有任何空内容；未答完的一轮只发问题、不发空助手消息")
    void noBlankContentInMessages() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        store.save(new SessionState("s-blank", List.of(
                new SessionTurn(1, "问一（这一轮降级未答）", "", "retrieval", ""),
                turn(2, "问二", "答二", "tool_b(y=2)")), "", 0));

        ContextAssembler.AssembledContext ctx =
                assembler(store, SummarizerStub.returning("x"), 100_000, 2).assemble("s-blank", "新问题");

        assertThat(ctx.messages()).allSatisfy(message -> {
            assertThat(message.content())
                    .as("空块会把「本来就没有这一块」与「生成这一块时失败了」混成一件事")
                    .isNotBlank();
            assertThat(message.role()).isIn(ContextMessage.ROLE_SYSTEM, ContextMessage.ROLE_USER,
                    ContextMessage.ROLE_ASSISTANT);
        });
        assertThat(ctx.messages()).extracting(ContextMessage::role)
                .as("第 1 轮没有应答 ⇒ 不发空 assistant 消息")
                .containsExactly("system", "system", "user", "user", "assistant", "user");

        // 空正文/非法角色在构造点就拒绝，而不是靠装配处小心
        assertThatThrownBy(() -> new ContextMessage(ContextMessage.ROLE_USER, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
        assertThatThrownBy(() -> new ContextMessage("tool", "正文"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
    }

    // ── 9. 估算器的手算期望值 ──────────────────────────────────────────────

    @Test
    @DisplayName("TokenEstimator.heuristic()：纯 CJK 与纯 ASCII 的期望值按手算字面量断言")
    void heuristicEstimatorHandComputedValues() {
        TokenEstimator estimator = TokenEstimator.heuristic();

        // 手算（算式写在这里，断言一律用字面量）：
        //   「运河智答」   = 4 个 CJK 字符                        → 4 × 1            = 4
        //   "abcdefgh"    = 8 个非 CJK 字符                       → ceil(8 / 4)      = 2
        //   "运河智答 v1"  = 4 个 CJK + 3 个非 CJK（空格、v、1）    → 4 + ceil(3 / 4)  = 4 + 1 = 5
        assertThat(estimator.estimate("运河智答")).isEqualTo(4);
        assertThat(estimator.estimate("abcdefgh")).isEqualTo(2);
        assertThat(estimator.estimate("运河智答 v1")).isEqualTo(5);
        assertThat(estimator.estimate("")).isZero();
        assertThat(estimator.estimate(null)).isZero();

        // 单调性：压缩判断依赖的是这个性质，⛔ 不是绝对值的准确度（估算器未与真实分词器对过）
        assertThat(estimator.estimate("运河智答运河智答")).isGreaterThan(estimator.estimate("运河智答"));
        assertThat(estimator.estimate("abcdefghij")).isGreaterThanOrEqualTo(estimator.estimate("abcdefgh"));
    }

    // ── 10. 追加轮次与预算校验 ─────────────────────────────────────────────

    @Test
    @DisplayName("appendTurn 拒绝落在摘要覆盖范围内的轮次；预算非正一律拒绝；assemble 不落库新问题")
    void appendTurnGuardAndBudgetValidation() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        store.save(new SessionState("s-append", List.of(
                turn(3, "问三", "答三", "tool_c(z=3)")), "更早的摘要", 3));
        ContextAssembler assembler = assembler(store, SummarizerStub.returning("x"), 100_000, 2);

        assertThatThrownBy(() -> assembler.appendTurn("s-append", turn(2, "问二", "答二", "")))
                .as("index 落在摘要覆盖范围内的轮次不会出现在上下文里，接受它等于静默丢一轮")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("摘要覆盖范围");

        assembler.appendTurn("s-append", turn(4, "问四", "答四", "tool_d(w=4)"));
        assertThat(store.load("s-append").rawTurns()).extracting(SessionTurn::index).containsExactly(3, 4);

        assertThatThrownBy(() -> new ContextAssembler.ContextBudget(0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxEstimatedTokens");
        assertThatThrownBy(() -> new ContextAssembler.ContextBudget(100, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keepRecentTurns");
        assertThat(ContextAssembler.ContextBudget.DEFAULT.maxEstimatedTokens()).isEqualTo(4096);
        assertThat(ContextAssembler.ContextBudget.DEFAULT.keepRecentTurns()).isEqualTo(2);

        // assemble 只读：不会把本轮问题存成"已完成的一轮"（否则模型调用失败后会留下假轮次）
        InMemorySessionStore fresh = new InMemorySessionStore();
        ContextAssembler onlyRead = assembler(fresh, SummarizerStub.returning("x"), 100_000, 2);
        onlyRead.assemble("s-notpersist", "第一句");
        assertThat(fresh.load("s-notpersist").turns()).isEmpty();
        assertThat(fresh.sessionCount()).as("读取未知会话不应凭空造出条目").isZero();
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    private static ContextAssembler assembler(SessionStore store, ContextSummarizer summarizer,
                                             int maxEstimatedTokens, int keepRecentTurns) {
        return new ContextAssembler(store, summarizer, TokenEstimator.heuristic(),
                new ContextBudget(maxEstimatedTokens, keepRecentTurns), SYSTEM);
    }

    private static SessionTurn turn(int index, String question, String answer, String structuredParamsJson) {
        return new SessionTurn(index, question, answer, "structured", structuredParamsJson);
    }

    private static int indexOfMessageStartingWith(List<ContextMessage> messages, String prefix) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).content().startsWith(prefix)) {
                return i;
            }
        }
        throw new AssertionError("上下文里没有以该块头开头的消息：" + prefix);
    }

    /**
     * 摘要器桩：既能数调用次数（证明"未触发"不是空断言），也能按剧本丢数字或直接失败。
     */
    private static final class SummarizerStub implements ContextSummarizer {

        private final AtomicInteger calls = new AtomicInteger();
        private final String result;
        private final Exception failure;
        private List<SessionTurn> lastFolded = List.of();
        private String lastPreviousSummary = "";

        private SummarizerStub(String result, Exception failure) {
            this.result = result;
            this.failure = failure;
        }

        static SummarizerStub returning(String result) {
            return new SummarizerStub(result, null);
        }

        static SummarizerStub failing(Exception failure) {
            return new SummarizerStub(null, failure);
        }

        @Override
        public String summarize(String previousSummary, List<SessionTurn> toCompact) throws Exception {
            calls.incrementAndGet();
            lastPreviousSummary = previousSummary;
            lastFolded = List.copyOf(toCompact);
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        int calls() {
            return calls.get();
        }

        List<SessionTurn> lastFolded() {
            return lastFolded;
        }

        String lastPreviousSummary() {
            return lastPreviousSummary;
        }
    }

    @Test
    @DisplayName("★ C-53 证据：换成**生产摘要器**后，压缩真的折叠了轮次，且它的摘要真的进了上下文")
    void productionSummarizerActuallyFolds() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        store.save(new SessionState("s-c53", List.of(
                SessionTurn.of(1, "费率表放哪了？", "在规则语料里。", "retrieval"),
                SessionTurn.of(2, "引航费按什么分档？", "按船舶总吨位。", "retrieval"),
                turn(3, "GX00042 是条什么船？总吨位多少？", "多用途船。",
                        "query_vessel(vessel_id=GX00042,gross_tonnage=1520)")), "", 0));

        // ⚠️ 生产摘要器（桩住 invoker ⇒ **零付费**）——这正是 C-53 要交付的那条路径。
        //    上面那些用例用的是测试桩 SummarizerStub；本用例刻意换**真实现**，
        //    因为"桩能折叠"与"生产实现能折叠"是两件事。
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        LlmContextSummarizer production =
                new LlmContextSummarizer(new com.canalmind.structured.JsonChatInvoker() {
                    @Override
                    public String invoke(String systemPrompt, String userPrompt) {
                        seen.set(userPrompt);
                        return "{\"summary\":\"更早：用户问过费率位置与引航费分档口径。\"}";
                    }
                });

        ContextAssembler assembler = assembler(store, production, FORCE_COMPACT_TOKENS, 1);
        ContextAssembler.AssembledContext ctx =
                assembler.assemble("s-c53", "还有哪些跟它一个船型、但比它大的？");

        // ① 压缩**真的发生**了（⛔ 不是"跑成功了"——这条断言看的就是折叠标志）
        assertThat(ctx.compacted()).as("预算被压到 1，压缩必须真的发生").isTrue();
        // ② 生产摘要器**自己产出的那段摘要**真的被用进了上下文
        assertThat(ctx.summaryUsed())
                .as("生产摘要器的输出必须真的进入上下文，否则等于没接上")
                .contains("引航费分档口径");
        // ③ 它确实收到了待折叠轮次，且**结构化参数原文**在里面（C-38③ 不许被吃掉）
        assertThat(seen.get()).as("生产摘要器实际收到的提示词＝[%s]", seen.get()).contains("费率表放哪了？");
        // ④ ⚠️ 而**近期轮（第 3 轮）不得进摘要器**：它的原始参数由 C-38③ 的「最近原始参数」块
        //    **原样保留**在上下文里，⛔ 不靠摘要传递。若它出现在这里，说明
        //    "近期轮不被摘要"这条被破坏了——而那正是"参数被摘要改写掉"的入口。
        //    （本条我一开始写反了：我断言参数**应当**出现在摘要器输入里；实测打印出真实提示词后
        //      才看清 keepRecentTurns=1 时第 3 轮根本不会被折叠 ⇒ **错的期望，对的实现**。）
        assertThat(seen.get()).as("近期轮的原始参数不该进摘要器")
                .doesNotContain("gross_tonnage=1520");
    }
}
