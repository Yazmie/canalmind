package com.canalmind.rag.tokenize;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中文分词器的测试（第 2 周第 4 项）。
 *
 * <p>分四组：
 * <ol>
 *   <li><b>词典是否真的生效</b>——逐条核对（最要命的一类失败是"词典静默失效"）；</li>
 *   <li><b>规则段（代码/数字）的确定性</b>——这部分不经过 jieba，输出应逐位可预期；</li>
 *   <li><b>归一化与清洗</b>——NFKC、表格占位标记、停用词；</li>
 *   <li><b>可复现性与并发</b>——本类声称"实例可共享、输出逐位可复现"，必须有用例守着。</li>
 * </ol>
 *
 * <p>⚠️ 汉字段只断言「包含/不包含」而少用「完全等于」：jieba 的切分结果依赖词典与概率，
 * 写死整串会把"词典正常演进"判成失败；而代码段不经过 jieba，可以放心写死。
 */
class JiebaShippingTokenizerTest {

    /** ⛔ 用计算字符表达换行，不用转义字面量（AGENTS.md §7.2 第 1、2 条）。 */
    private static final String LF = String.valueOf((char) 10);

    private static final JiebaShippingTokenizer TOKENIZER = new JiebaShippingTokenizer();

    @Test
    @DisplayName("⚠️ 逐条核对：每条自定义词典词都被完整切出（词频没退回默认值）")
    void everyDictionaryWordIsKeptWhole() {
        for (String word : ShippingDictionary.words()) {
            assertThat(TOKENIZER.tokenize(word))
                    .as("词典词「%s」应被完整切出；若被切碎，说明词频没生效（三列格式陷阱）", word)
                    .contains(word);
        }
    }

    @Test
    @DisplayName("基础词典里没有的领域词（南宁港六景作业区/闸室/过闸）现在能被整词切出")
    void domainWordsAbsentFromBaseDictAreNowWhole() {
        // 实测：jieba 基础词典里既没有"南宁港六景作业区"，也没有"闸室"和"过闸"
        // （基础词典 349045 词、总频次 60101878；参考：通过 -7.45、规定 -7.80、船 -8.95、闸 -10.85）
        assertThat(TOKENIZER.tokenize("南宁港六景作业区的闸室过闸安排"))
                .contains("南宁港六景作业区", "闸室", "过闸");
    }

    @Test
    @DisplayName("INDEX 模式额外输出词典内的 2~3 字子词（召回更宽）；两种模式都保留整词")
    void indexModeIsBroaderThanSearchMode() {
        JiebaShippingTokenizer index = new JiebaShippingTokenizer(JiebaShippingTokenizer.MODE_INDEX);
        JiebaShippingTokenizer search = new JiebaShippingTokenizer(JiebaShippingTokenizer.MODE_SEARCH);

        List<String> indexTokens = index.tokenize("危险货物专用闸次");
        List<String> searchTokens = search.tokenize("危险货物专用闸次");

        assertThat(indexTokens).contains("危险货物专用闸次");
        assertThat(searchTokens).contains("危险货物专用闸次");
        // INDEX = 整词 + 词典内的子词，故应是 SEARCH 的超集
        assertThat(indexTokens).containsAll(searchTokens);
        assertThat(indexTokens.size()).isGreaterThan(searchTokens.size());
    }

    // ── 规则段：不经过 jieba，输出逐位可预期 ────────────────────────────────

    @Test
    @DisplayName("文号/编号：整串与部件都产出（完整文号精确命中，部分文号靠部件召回）")
    void codeRunYieldsWholeAndParts() {
        assertThat(TOKENIZER.tokenize("PLYH-GZ-2026-001"))
                .containsExactly("plyh-gz-2026-001", "plyh", "gz", "2026", "001");
    }

    @Test
    @DisplayName("小数：数字之间的点不拆，\"4.20\" 不会变成 \"4\" 与 \"20\"")
    void decimalPointIsNotSplit() {
        assertThat(TOKENIZER.tokenize("4.20")).containsExactly("4.20");
    }

    @Test
    @DisplayName("纯数字与量纲：\"300 米\" → 300 与米都在（米是计费/尺度量纲，刻意不是停用词）")
    void numbersAndUnitsAreKept() {
        List<String> tokens = TOKENIZER.tokenize("闸室有效长度 300 米");
        assertThat(tokens).contains("300", "米", "闸室有效长度");
    }

    @Test
    @DisplayName("单字母部件被丢弃（\"A-B\" 只出整串），避免把噪声灌进倒排表")
    void singleCharPartsAreDropped() {
        assertThat(TOKENIZER.tokenize("A-B")).containsExactly("a-b");
    }

    @Test
    @DisplayName("首尾连接符被剥掉（\"-2026-\" → 2026）")
    void leadingAndTrailingConnectorsAreTrimmed() {
        assertThat(TOKENIZER.tokenize("(2026)")).containsExactly("2026");
    }

    @Test
    @DisplayName("纯标点/纯连接符段不产出任何词元（表格分隔行 \"---\" 之类）")
    void punctuationOnlyRunsProduceNothing() {
        assertThat(TOKENIZER.tokenize("---")).isEmpty();
        assertThat(TOKENIZER.tokenize("| --- | --- |")).isEmpty();
        assertThat(TOKENIZER.tokenize("，，。？！、；：")).isEmpty();
    }

    // ── 归一化与清洗 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("⚠️ 表格占位标记被剥掉：16 位 chunk_id 不会变成词元")
    void tableMarkerIsStripped() {
        // 实测语料里 87 处，形如 [TABLE:7f83bb32ecaf43ac rows=6 cols=3]
        // （早前文档写成 [[TABLE:n]]，见 docs/进度看板.md §3.4 更正）
        List<String> tokens = TOKENIZER.tokenize("过闸费[TABLE:7f83bb32ecaf43ac rows=6 cols=3]船闸");
        assertThat(tokens).contains("过闸费", "船闸");
        assertThat(tokens).noneMatch(t -> t.contains("table") || t.contains("7f83bb32ecaf43ac"));
        assertThat(tokens).noneMatch(t -> t.contains("rows") || t.contains("cols"));
    }

    @Test
    @DisplayName("未闭合的标记原样保留（让人看得见异常数据，而不是静默吞掉）")
    void unclosedTableMarkerStaysVisible() {
        assertThat(TOKENIZER.tokenize("过闸费[TABLE:7f83bb32ecaf43ac"))
                .contains("过闸费", "table");
    }

    @Test
    @DisplayName("⚠️ 行首 Markdown 列表编号被剥掉（实测 1170 处、覆盖 66% 正文 chunk）")
    void listMarkersAreStripped() {
        // 不剥离时，词元 1/2/3/4 会挤进 df 榜首（df 317/318/310/217，即出现在 60% 的 chunk 里）
        String rules = "下列船舶：" + LF + "1. 干散货船；" + LF + "2. 集装箱船；" + LF + "3. 多用途船。";
        List<String> tokens = TOKENIZER.tokenize(rules);
        assertThat(tokens).contains("干散货船", "集装箱船", "多用途船");
        assertThat(tokens).doesNotContain("1", "2", "3");
    }

    @Test
    @DisplayName("列表编号剥离只匹配语料里真实存在的写法，不误伤数值")
    void listMarkerStrippingDoesNotHitRealNumbers() {
        // 1) 点后面是数字 → 是小数，不是编号
        assertThat(TOKENIZER.tokenize("4.20 元/吨")).contains("4.20");
        assertThat(TOKENIZER.tokenize("计费单位" + LF + "4.20 元/吨")).contains("4.20");
        // 2) 不在行首 → 是内容
        assertThat(TOKENIZER.tokenize("障碍消除后 2 小时内补办申报")).contains("2");
        // 3) 年份是 4 位数字且后面不是「点 + 空白」→ 保留
        assertThat(TOKENIZER.tokenize("自 2026 年 1 月 1 日起施行")).contains("2026");
        // 4) 语料里不存在的括号式编号形式 → 不做猜测性剥离（保持"只处理实测存在的写法"）
        assertThat(TOKENIZER.tokenize("(1) 提单编号")).isNotEmpty();
    }

    @Test
    @DisplayName("NFKC：全角括号/全角数字归一化，查询与文档两侧切出同一批词元")
    void fullWidthIsNormalized() {
        assertThat(TOKENIZER.tokenize("（过闸费）")).contains("过闸费");
        assertThat(TOKENIZER.tokenize("２０２６")).containsExactly("2026");
    }

    @Test
    @DisplayName("停用词被剔除，但规范性模态词与量纲词**不**剔除")
    void stopwordsRemovedButModalityKept() {
        List<String> tokens = TOKENIZER.tokenize("过闸费的收费标准应当以上述规定为准");
        assertThat(tokens).doesNotContain("的", "以", "为");
        // "不得退费"与"可以退费"是相反结论 → 模态词必须保留
        assertThat(TOKENIZER.tokenize("不得退费")).contains("不得");
        assertThat(TOKENIZER.tokenize("可以退费")).contains("可以");
        // "以上/以下"是尺度类条款的判据 → 必须保留
        assertThat(TOKENIZER.tokenize("三十日以上")).contains("以上");
        assertThat(TOKENIZER.tokenize("以下情形不予受理")).contains("以下");
    }

    @Test
    @DisplayName("⚠️ 实测特征：单字量纲词会被并进复合词（\"三十日以上\" 里没有独立的\"日\"）")
    void singleCharMeasureWordMayBeAbsorbedIntoCompound() {
        // 第 2 周第 4 项实测：`三十日以上` → ["三十","十日","三十日","以上"]，
        // **没有**独立的"日"。原因不是它被当停用词剔除（"日"刻意不在停用词表里），
        // 而是 jieba 基础词典里存在"三十日"这类日期复合词，最大概率路径把它整词切出来了。
        //
        // 后果是**真实存在的一条召回边界**：查询"日"（单字）召不回这个 chunk。
        // 反例：`300 米` → ["300","米"]，因为空白把"米"与数字隔开了，米能独立成词。
        // 该边界已如实登记为第 4 周的消融候选变量（单字词元是否补投），
        // 不在本项静默"修掉"——改分词粒度会同时改变整条评测基线。
        assertThat(TOKENIZER.tokenize("三十日以上")).doesNotContain("日");
        assertThat(TOKENIZER.tokenize("300 米")).contains("米");
    }

    @Test
    @DisplayName("空/null/纯空白 → 空结果，不抛异常")
    void emptyInputsProduceNoTokens() {
        assertThat(TOKENIZER.tokenize(null)).isEmpty();
        assertThat(TOKENIZER.tokenize("")).isEmpty();
        assertThat(TOKENIZER.tokenize("   ")).isEmpty();
        assertThat(TOKENIZER.tokenize(String.valueOf((char) 10))).isEmpty();
    }

    @Test
    @DisplayName("词元干净：无空白、无表格管道符、无 markdown 星号")
    void tokensAreClean() {
        List<String> tokens = TOKENIZER.tokenize("**过闸费** | 元/吨 | 4.20 元");
        assertThat(tokens).isNotEmpty().allSatisfy(t -> assertThat(t)
                .doesNotContain(" ", "*", "|", String.valueOf((char) 9)));
    }

    // ── 词频、可复现、并发 ────────────────────────────────────────────────

    @Test
    @DisplayName("词频统计：按词元排序、计数正确（入库顺序与日志因此可比）")
    void termFrequenciesAreSortedAndCounted() {
        Map<String, Integer> freqs = TOKENIZER.termFrequencies("过闸费 过闸费 船闸");
        assertThat(freqs).containsEntry("过闸费", 2).containsEntry("船闸", 1);
        assertThat(new ArrayList<>(freqs.keySet())).isSorted();
    }

    @Test
    @DisplayName("同一输入两次调用逐位相同（评测基线的前提）")
    void tokenizationIsDeterministic() {
        String text = "过闸费的计费单位是元/吨，文号 PLYH-GZ-2026-001，闸室有效长度 300 米";
        assertThat(TOKENIZER.tokenize(text)).isEqualTo(TOKENIZER.tokenize(text));
    }

    @Test
    @DisplayName("实例可被多线程共享：8 线程 × 200 次结果完全一致（无共享可变状态）")
    void tokenizerIsSafeForConcurrentUse() throws Exception {
        String text = "危险货物专用闸次应当提前 24 小时申报，过闸费按元/吨计费";
        List<String> expected = TOKENIZER.tokenize(text);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<List<String>>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> {
                    List<String> last = null;
                    for (int n = 0; n < 200; n++) {
                        last = TOKENIZER.tokenize(text);
                        if (!expected.equals(last)) {
                            return last;
                        }
                    }
                    return last;
                });
            }
            for (Future<List<String>> f : pool.invokeAll(tasks)) {
                assertThat(f.get()).isEqualTo(expected);
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ── 档案 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("档案：档案名/模式/词典与停用词指纹都写对了（含规则版本——改了规则必须换档案）")
    void profileCarriesIdentity() {
        TokenizerProfile profile = TOKENIZER.profile(1.2, 0.75);
        assertThat(profile.profileName())
                .isEqualTo("jieba:shipping-dict-v1:rules-" + JiebaShippingTokenizer.RULES_VERSION + ":INDEX");
        assertThat(profile.tokenizerName()).isEqualTo("jieba-analysis");
        // 档案里的版本串 = 库版本 + 本类规则版本：只比库版本会让"改了规则却不换档案"静默通过
        assertThat(profile.tokenizerVersion())
                .isEqualTo("1.0.2+rules-" + JiebaShippingTokenizer.RULES_VERSION);
        assertThat(profile.segmentMode()).isEqualTo("INDEX");
        assertThat(profile.dictionarySha256()).isEqualTo(ShippingDictionary.sha256());
        assertThat(profile.dictionaryTerms()).isEqualTo(ShippingDictionary.termCount());
        assertThat(profile.stopwordsSha256()).isEqualTo(StopwordList.sha256());
        assertThat(profile.bm25K1()).isEqualTo(1.2);
        assertThat(profile.bm25B()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("⚠️ 版本常量必须与真实依赖 jar 一致（版本漂移会让检索基线悄悄改变）")
    void versionMatchesJarArtifact() {
        String location = JiebaSegmenter.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        assertThat(location)
                .as("分词器版本常量 %s 应与实际加载的 jar 一致；实际位置：%s",
                        JiebaShippingTokenizer.VERSION, location)
                .contains("jieba-analysis-" + JiebaShippingTokenizer.VERSION);
    }

    @Test
    @DisplayName("非法切分模式 → 立即报错（不静默退回默认值）")
    void invalidSegmentModeRejected() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new JiebaShippingTokenizer("FULL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INDEX");
    }
}
