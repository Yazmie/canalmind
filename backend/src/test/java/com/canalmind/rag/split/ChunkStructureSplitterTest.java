package com.canalmind.rag.split;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ChunkStructureSplitter} 单元测试。
 *
 * <h2>本文件三条刻意的写法约定（都是踩坑后的修正，改动前请先读）</h2>
 *
 * <p><b>1. 换行一律用 {@link #NL}，不写字面量反斜杠-n。</b>
 * 本项目实测：向 Java 源文件写入时，源码里的反斜杠-n 转义会落成**两个字符**
 * （反斜杠 + n），于是 {@code split} 切不开、整个文档变成一行，切分器**静默不切**
 * （表现为"条数为 0"或"款不拆"），非常难查。
 * 用 {@code String.valueOf((char) 10)} 计算出的换行不受写入层影响。
 *
 * <p><b>2. 不用文本块（text block）构造测试文档。</b>
 * 文本块里的换行是**源文件字节**，一旦字节形态变化，字符串内容会静默改变；
 * 失败信息只会说"期望 3 个条、实际 0 个"，不会说"你的输入根本不是你以为的文本"。
 * 显式拼接让换行成为代码的一部分，可被阅读、检索与断言。
 *
 * <p><b>3. 合成文档的条标题必须用真实中文（{@code ### 第四条 【x】}）。</b>
 * 我一度用 ASCII（{@code ### 第四条 LONG}）做探针，{@code ARTICLE_HEAD} 不匹配、
 * 切出 0 块，我据此误判"切分器有 bug"。**验证工具本身也要被验证**——
 * 探针输入不合规，探针结论就无效。
 */
class ChunkStructureSplitterTest {

    private static final Path CORPUS = Path.of("..", "docs", "规则语料");

    /** 换行常量，理由见类注释第 1 条。 */
    private static final String NL = String.valueOf((char) 10);

    /** 回车常量，理由同上（避免字面量被写入层改写）。 */
    private static final String CR = String.valueOf((char) 13);

    private static final Pattern TABLE_MARKER =
            Pattern.compile("\\[TABLE:([0-9a-f]+) rows=(\\d+) cols=(\\d+)\\]");

    private final ChunkStructureSplitter splitter = new ChunkStructureSplitter();

    // ── 合成文档 ────────────────────────────────────────────────────────

    /** 合成文档：2 章 / 3 条，其中第二条含一张表。元数据块符合 C-20 的 8 字段结构。 */
    private static final String SYNTHETIC = String.join(NL,
            "> **【仿真语料声明】** 本文为 CanalMind 项目自建仿真语料，非官方文件。",
            "",
            "| 字段 | 值 |",
            "|---|---|",
            "| 文号 | PLYH-TEST-2026-999 |",
            "| 标题 | 测试用仿真办法 |",
            "| 发布机构 | 平陆运河航运服务中心（仿真） |",
            "| 版本 | V1.0 |",
            "| 生效期 | 2026-01-01 至 长期有效 |",
            "| 替代关系 | 无 |",
            "| 说明 | 单测合成文档 |",
            "",
            "# 测试用仿真办法",
            "",
            "## 第一章 总则",
            "",
            "### 第一条 【目的】",
            "",
            "为测试切分器而制定。",
            "",
            "#### 一、申请条件",
            "",
            "1. 条件甲；",
            "2. 条件乙。",
            "",
            "### 第二条 【含表格的条】",
            "",
            "费率如下：",
            "",
            "| 费种 | 费率 |",
            "|---|---|",
            "| 过闸费 | 1.20 |",
            "",
            "表后说明一句。",
            "",
            "## 第二章 附则",
            "",
            "### 第三条 【生效】",
            "",
            "自发布之日起施行。") + NL;

    private CorpusDocument syntheticDoc() {
        return DocumentParser.parse(Path.of("99-测试用仿真办法.md"), SYNTHETIC);
    }

    /** 构造一个带 8 字段元数据块的最小文档；{@code bodyLines} 为正文各行。 */
    private static String doc(String docNo, String title, String... bodyLines) {
        List<String> all = new ArrayList<>(List.of(
                "> **【仿真语料声明】** 仿真文档，非官方文件。",
                "",
                "| 字段 | 值 |",
                "|---|---|",
                "| 文号 | " + docNo + " |",
                "| 标题 | " + title + " |",
                "| 发布机构 | 仿真机构 |",
                "| 版本 | V1.0 |",
                "| 生效期 | 2026-01-01 至 长期有效 |",
                "| 替代关系 | 无 |",
                "| 说明 | 单测 |",
                "",
                "# " + title,
                ""));
        all.addAll(List.of(bodyLines));
        return String.join(NL, all) + NL;
    }

    /** 把 chunk 列表渲染成短描述，便于断言失败时直接看出切成了什么。 */
    private static String describe(List<Chunk> chunks) {
        List<String> rows = new ArrayList<>();
        for (Chunk c : chunks) {
            rows.add(c.getArticleNo() + "/" + c.getChunkType() + "/" + c.getCharCount()
                    + "/part" + c.getPartIndex() + "of" + c.getPartCount()
                    + "/" + c.getExtra().get("splitMode"));
        }
        return rows.toString();
    }

    private static long countArticles(String text) {
        Matcher m = Pattern.compile("(?m)^###\\s+第[零一二三四五六七八九十百千]+条").matcher(text);
        long c = 0;
        while (m.find()) {
            c++;
        }
        return c;
    }

    // ── 合成文档断言 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("合成文档：精确边界断言")
    class Synthetic {

        @Test
        @DisplayName("条数 == 正文 chunk 数（每条一块，条为唯一主单元）")
        void oneChunkPerArticle() {
            List<Chunk> chunks = splitter.split(syntheticDoc());
            long body = chunks.stream().filter(c -> c.getChunkType() == ChunkType.BODY).count();
            assertThat(body).as("实际 = %s", describe(chunks)).isEqualTo(3);
        }

        @Test
        @DisplayName("章不独立成块，但作为 chapterPath 元数据保留")
        void chapterIsMetadataNotChunk() {
            List<Chunk> chunks = splitter.split(syntheticDoc());
            assertThat(chunks).allSatisfy(c -> assertThat(c.getArticleNo()).isNotNull());
            assertThat(chunks).allSatisfy(c -> assertThat(c.getChapterPath()).hasSize(1));

            Chunk first = chunks.stream()
                    .filter(c -> "第一条".equals(c.getArticleNo())).findFirst().orElseThrow();
            Chunk third = chunks.stream()
                    .filter(c -> "第三条".equals(c.getArticleNo())).findFirst().orElseThrow();
            assertThat(first.getChapterPath()).containsExactly("第一章 总则");
            assertThat(third.getChapterPath()).containsExactly("第二章 附则");
        }

        @Test
        @DisplayName("表格单独成 TABLE chunk，正文中替换为指向它的占位标记")
        void tableExtractedAndReferenced() {
            List<Chunk> chunks = splitter.split(syntheticDoc());
            List<Chunk> tables = chunks.stream()
                    .filter(c -> c.getChunkType() == ChunkType.TABLE).toList();
            assertThat(tables).hasSize(1);

            Chunk table = tables.get(0);
            assertThat(table.getTableHeaders()).containsExactly("费种", "费率");
            assertThat(table.getTableRows()).hasSize(1);
            assertThat(table.getArticleNo()).isEqualTo("第二条");

            Chunk body = chunks.stream()
                    .filter(c -> c.getChunkType() == ChunkType.BODY
                            && "第二条".equals(c.getArticleNo()))
                    .findFirst().orElseThrow();
            assertThat(body.getContent()).contains("[TABLE:" + table.getChunkId());
            assertThat(body.getContent()).doesNotContain("| 过闸费 |");
            assertThat(body.getContent()).contains("表后说明一句");
        }

        @Test
        @DisplayName("条未超阈值时不产生子块（partIndex/partCount 均为 0）")
        void noSubSplitWhenUnderThreshold() {
            List<Chunk> chunks = splitter.split(syntheticDoc());
            assertThat(chunks).allSatisfy(c -> {
                assertThat(c.getPartIndex()).isZero();
                assertThat(c.getPartCount()).isZero();
            });
        }

        @Test
        @DisplayName("条超阈值时拆为多个子块，每块仍携带条号与标题（防御性路径）")
        void subSplitWhenOversized() {
            String filler = "这是用于撑长条正文的句子，反复出现以便超过阈值。";
            StringBuilder sb = new StringBuilder(doc("PLYH-TEST-2026-997", "超长条测试文档",
                    "## 第一章 正文",
                    "",
                    "### 第四条 【超长条】",
                    ""));
            for (int i = 1; i <= 4; i++) {
                sb.append(NL).append("#### ").append("一二三四".charAt(i - 1))
                  .append("、第").append(i).append("款").append(NL);
                for (int j = 0; j < 12; j++) {
                    sb.append(filler);
                }
                sb.append(NL);
            }
            sb.append(NL).append("### 第五条 【结尾条】").append(NL).append(NL)
              .append("结尾正文一句。").append(NL);

            CorpusDocument d = DocumentParser.parse(
                    Path.of("99-测试用仿真办法.md"), sb.toString());

            List<Chunk> chunks = splitter.split(d);
            List<Chunk> parts = chunks.stream()
                    .filter(c -> "第四条".equals(c.getArticleNo()))
                    .filter(c -> c.getChunkType() == ChunkType.BODY)
                    .toList();

            assertThat(parts)
                    .as("第四条 应被拆为多个子块；实际 = %s", describe(chunks))
                    .hasSizeGreaterThan(1);
            assertThat(parts).allSatisfy(c -> {
                assertThat(c.getArticleNo()).isEqualTo("第四条");
                assertThat(c.getArticleTitle()).isEqualTo("超长条");
                assertThat(c.getChapterPath()).isNotEmpty();
                assertThat(c.getContent()).isNotBlank();
            });
            assertThat(parts.stream().map(Chunk::getPartIndex).toList())
                    .containsExactlyElementsOf(
                            java.util.stream.IntStream.rangeClosed(1, parts.size()).boxed().toList());
            assertThat(parts).allSatisfy(c -> assertThat(c.getPartCount()).isEqualTo(parts.size()));
            assertThat(parts.stream().map(Chunk::getChunkId).distinct()).hasSize(parts.size());
            assertThat(chunks).filteredOn(c -> "第五条".equals(c.getArticleNo()))
                    .hasSize(1)
                    .allSatisfy(c -> {
                        assertThat(c.getPartIndex()).isZero();
                        assertThat(c.getPartCount()).isZero();
                    });
        }

        @Test
        @DisplayName("条超阈值且每款短于阈值时，按款（#### 一、）拆分，块数与款数一致")
        void subSplitByClauseKeepsClauseBoundaries() {
            String[] clauses = {
                "#### 一、甲款正文一句，足够短。",
                "#### 二、乙款正文一句，足够短。",
                "#### 三、丙款正文一句，足够短。",
                "#### 四、丁款正文一句，足够短。"};
            StringBuilder sb = new StringBuilder(doc("PLYH-TEST-2026-996", "按款拆分测试文档",
                    "## 第一章 正文",
                    "",
                    "### 第一条 【多款条】",
                    ""));
            for (String clause : clauses) {
                sb.append(NL).append(clause).append(NL);
            }

            CorpusDocument d = DocumentParser.parse(
                    Path.of("99-测试用仿真办法.md"), sb.toString());

            ChunkStructureSplitter tiny =
                    new ChunkStructureSplitter(new ChunkingProperties(30, 320, 60));
            List<Chunk> chunks = tiny.split(d);
            List<Chunk> parts = chunks.stream()
                    .filter(c -> c.getChunkType() == ChunkType.BODY)
                    .filter(c -> "第一条".equals(c.getArticleNo()))
                    .toList();

            assertThat(parts)
                    .as("应在款边界处拆分；实际 = %s", describe(chunks))
                    .hasSize(clauses.length);
            assertThat(parts).allSatisfy(c -> {
                assertThat(c.getExtra().get("splitMode")).isEqualTo("clause");
                assertThat(c.getArticleNo()).isEqualTo("第一条");
                assertThat(c.getArticleTitle()).isEqualTo("多款条");
            });
            for (int i = 0; i < parts.size(); i++) {
                String expectedHead = "#### " + "一二三四".charAt(i) + "、";
                assertThat(parts.get(i).getContent())
                        .as("第 %d 个子块应以款头开头", i + 1)
                        .startsWith(expectedHead);
            }
        }

        @Test
        @DisplayName("条标题兼容【】与非【】两种写法（README 要求带【】，但缺了不应直接崩）")
        void articleTitleOptional() {
            String text = SYNTHETIC.replace("### 第三条 【生效】", "### 第三条 生效");
            assertThat(text).as("替换必须真正生效").isNotEqualTo(SYNTHETIC);
            CorpusDocument d = DocumentParser.parse(Path.of("99-测试用仿真办法.md"), text);
            Chunk third = splitter.split(d).stream()
                    .filter(c -> "第三条".equals(c.getArticleNo())).findFirst().orElseThrow();
            assertThat(third.getArticleTitle()).isEqualTo("生效");
        }

        @Test
        @DisplayName("正文里的表格被抽出后，正文不应残留表格数据行")
        void noTableRowsLeftInBody() {
            List<Chunk> chunks = splitter.split(syntheticDoc());
            assertThat(chunks)
                    .filteredOn(c -> c.getChunkType() == ChunkType.BODY)
                    .allSatisfy(c -> assertThat(c.getContent())
                            .doesNotContain("| 过闸费 | 1.20 |"));
        }
    }

    // ── 真实语料断言 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("真实语料：结构性不变量")
    class RealCorpus {

        private List<CorpusDocument> docs() {
            List<CorpusDocument> list = new ArrayList<>();
            for (Path f : DocumentParser.listCorpusFiles(CORPUS)) {
                list.add(DocumentParser.load(f));
            }
            return list;
        }

        @Test
        @DisplayName("语料目录可解析出 26 篇文档（README 不计入）")
        void parsesAllDocs() {
            assertThat(docs()).hasSize(26);
        }

        @Test
        @DisplayName("每篇文档的条数 == 其正文 chunk 数，且无文档产出 0 个 chunk")
        void bodyChunkCountEqualsArticleCount() {
            for (CorpusDocument d : docs()) {
                List<Chunk> chunks = splitter.split(d);
                long body = chunks.stream().filter(c -> c.getChunkType() == ChunkType.BODY).count();
                long articles = countArticles(d.rawText());
                assertThat(body)
                        .as("文档 %s 的正文 chunk 数应等于条数；实际 = %s", d.docId(), describe(chunks))
                        .isEqualTo(articles);
                assertThat(chunks).as("文档 %s 不应产出空 chunk 列表", d.docId()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("全语料 chunkId 唯一")
        void chunkIdsUnique() {
            Set<String> seen = new HashSet<>();
            List<String> dup = new ArrayList<>();
            for (CorpusDocument d : docs()) {
                for (Chunk c : splitter.split(d)) {
                    if (!seen.add(c.getChunkId())) {
                        dup.add(c.getChunkId());
                    }
                }
            }
            assertThat(dup).as("重复的 chunkId").isEmpty();
        }

        @Test
        @DisplayName("正文里每个表格占位标记都指向同一文档中真实存在的表格 chunk")
        void tableMarkersResolve() {
            for (CorpusDocument d : docs()) {
                List<Chunk> chunks = splitter.split(d);
                Set<String> tableIds = new HashSet<>();
                for (Chunk c : chunks) {
                    if (c.getChunkType() == ChunkType.TABLE) {
                        tableIds.add(c.getChunkId());
                    }
                }
                for (Chunk c : chunks) {
                    if (c.getChunkType() != ChunkType.BODY) {
                        continue;
                    }
                    Matcher m = TABLE_MARKER.matcher(c.getContent());
                    while (m.find()) {
                        assertThat(tableIds)
                                .as("文档 %s 中正文引用的表格 id 应存在", d.docId())
                                .contains(m.group(1));
                    }
                }
            }
        }

        @Test
        @DisplayName("每个表格 chunk 至少被一个正文 chunk 引用，且行列数一致")
        void noOrphanTablesAndConsistentShape() {
            for (CorpusDocument d : docs()) {
                List<Chunk> chunks = splitter.split(d);
                List<Chunk> tables = chunks.stream()
                        .filter(c -> c.getChunkType() == ChunkType.TABLE).toList();
                if (tables.isEmpty()) {
                    continue;
                }
                String allBodies = chunks.stream()
                        .filter(c -> c.getChunkType() == ChunkType.BODY)
                        .map(Chunk::getContent)
                        .reduce("", (a, b) -> a + NL + b);
                for (Chunk t : tables) {
                    assertThat(allBodies)
                            .as("表格 %s 应被正文引用", t.getChunkId())
                            .contains(t.getChunkId());
                    assertThat(t.getTableHeaders()).isNotEmpty();
                    for (List<String> row : t.getTableRows()) {
                        assertThat(row).hasSameSizeAs(t.getTableHeaders());
                    }
                }
            }
        }

        @Test
        @DisplayName("已废止文档的 chunk 携带 effectiveTo；现行文档为 null")
        void effectivePeriodCarried() {
            for (CorpusDocument d : docs()) {
                for (Chunk c : splitter.split(d)) {
                    assertThat(c.getEffectiveFrom()).isEqualTo(d.effectiveFrom());
                    assertThat(c.getEffectiveTo()).isEqualTo(d.effectiveTo());
                }
            }
            CorpusDocument superseded = docs().stream()
                    .filter(CorpusDocument::isSuperseded).findFirst().orElseThrow();
            assertThat(superseded.effectiveTo()).isNotNull();
        }

        @Test
        @DisplayName("全部 chunk 都带 strategy / splitterVersion（消融对比与版本追溯的前提）")
        void versionStampPresent() {
            for (CorpusDocument d : docs()) {
                for (Chunk c : splitter.split(d)) {
                    assertThat(c.getStrategy()).isEqualTo(ChunkStructureSplitter.STRATEGY);
                    assertThat(c.getSplitterVersion()).isEqualTo(ChunkStructureSplitter.VERSION);
                }
            }
        }

        @Test
        @DisplayName("同输入同输出：两次切分结果逐字段一致（评测可复现的前提）")
        void deterministic() {
            for (CorpusDocument d : docs().subList(0, 5)) {
                List<Chunk> a = splitter.split(d);
                List<Chunk> b = splitter.split(d);
                assertThat(a).hasSameSizeAs(b);
                for (int i = 0; i < a.size(); i++) {
                    assertThat(b.get(i).getChunkId()).isEqualTo(a.get(i).getChunkId());
                    assertThat(b.get(i).getContent()).isEqualTo(a.get(i).getContent());
                    assertThat(b.get(i).getCharCount()).isEqualTo(a.get(i).getCharCount());
                }
            }
        }

        @Test
        @DisplayName("表格内容不被规范化裁剪（保留全部数据行）")
        void tableContentNotTruncated() {
            for (CorpusDocument d : docs()) {
                for (Chunk c : splitter.split(d)) {
                    if (c.getChunkType() != ChunkType.TABLE) {
                        continue;
                    }
                    long dataLines = c.getContent().lines()
                            .filter(l -> l.startsWith("|")).count();
                    assertThat(dataLines)
                            .as("表格 %s 的 Markdown 行数应与解析结果一致", c.getChunkId())
                            .isEqualTo(2L + c.getTableRows().size());
                }
            }
        }

        @Test
        @DisplayName("章路径非空且取自真实章标题")
        void chapterPathsPresent() {
            for (CorpusDocument d : docs()) {
                for (Chunk c : splitter.split(d)) {
                    assertThat(c.getChapterPath())
                            .as("%s %s", d.docId(), c.getArticleNo()).isNotEmpty();
                    assertThat(c.getChapterPath().get(0)).isNotBlank();
                }
            }
        }
    }

    // ── 其它 ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"第一条", "第十条", "第二十六条"})
    @DisplayName("中文条号均能被识别（覆盖 1 位/2 位/3 位情形）")
    void recognisesChineseArticleNumbers(String articleNo) {
        String text = SYNTHETIC.replace("### 第一条 【目的】", "### " + articleNo + " 【目的】");
        if (!"第一条".equals(articleNo)) {
            assertThat(text).as("替换必须真正生效，否则本用例无意义").isNotEqualTo(SYNTHETIC);
        }
        CorpusDocument d = DocumentParser.parse(Path.of("99-测试用仿真办法.md"), text);
        List<String> nos = new ArrayList<>(ChunkStructureSplitter.articleNumbers(splitter.split(d)));
        assertThat(nos).contains(articleNo);
    }

    @Test
    @DisplayName("空正文不产生 chunk（防御极端输入）")
    void emptyBodyNoChunk() {
        String text = doc("PLYH-TEST-2026-998", "空文档");
        assertThat(splitter.split(DocumentParser.parse(Path.of("99-x.md"), text))).isEmpty();
    }

    @Test
    @DisplayName("chars 统计口径：不含空白字符")
    void charCountIgnoresWhitespace() {
        assertThat(ChunkStructureSplitter.countNonWhitespace("一 二" + NL + "三\t四")).isEqualTo(4);
        assertThat(ChunkStructureSplitter.countNonWhitespace("   ")).isZero();
        assertThat(ChunkStructureSplitter.countNonWhitespace("")).isZero();
    }

    @Test
    @DisplayName("articleNumbers 返回条号有序集合")
    void articleNumbersHelper() {
        List<Chunk> chunks = splitter.split(syntheticDoc());
        assertThat(ChunkStructureSplitter.articleNumbers(chunks))
                .containsExactly("第一条", "第二条", "第三条");
        assertThat(ChunkStructureSplitter.articleNumbers(Collections.emptyList())).isEmpty();
    }

    @Test
    @DisplayName("CRLF 与 BOM 在切分前被归一化（否则会静默少切）")
    void normalizesBomAndCrlf() {
        int expected = splitter.split(syntheticDoc()).size();

        String crlf = SYNTHETIC.replace(NL, CR + NL);
        CorpusDocument crlfDoc = DocumentParser.parse(Path.of("99-crlf.md"), crlf);
        CorpusDocument bomDoc = DocumentParser.parse(
                Path.of("99-bom.md"), "\uFEFF" + SYNTHETIC);
        java.util.List<Chunk> bomChunks = splitter.split(bomDoc);

        assertThat(splitter.split(crlfDoc))
                .as("CRLF 文档应切出与 LF 相同的结果")
                .hasSize(expected);

        assertThat(bomDoc.title()).as("BOM 不应破坏元数据解析").isEqualTo("测试用仿真办法");
        assertThat(bomChunks)
                .as("带 BOM 文档应切出与不带 BOM 相同的结果")
                .hasSize(expected);
    }

}
