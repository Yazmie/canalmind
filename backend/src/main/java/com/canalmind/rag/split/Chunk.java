package com.canalmind.rag.split;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 chunk（切分产物）。
 *
 * <p><b>字段设计依据</b>（知识库，逐条对应，不凭个人偏好增删）：
 *
 * <ul>
 *   <li><b>层级元数据</b>（{@code chapterPath} / {@code articleNo} / {@code articleTitle}）——
 *       《RAG 文档处理与切分策略》<b>第 109 行</b>：
 *       <i>"另一个被低估的手段是保留层级元数据。在 Metadata 里记录章节路径、父子标题、
 *       段落编号等信息，检索时可以按层级过滤，生成时也能补回上下文。这块成本低但收益大，
 *       很多团队却忽略了。"</i> 本条即为此要求的最小实现。</li>
 *
 *   <li><b>来源 / 版本 / 权限 / 章节路径</b>（{@code docId} / {@code docNo} / {@code version}
 *       / {@code effective*}）—— 同文件<b>第 253 行</b>要求"记录 Chunk 大小分布、来源、版本、
 *       权限与章节路径"。其中 {@code effective*} 是本项目<b>主路径</b>所需：
 *       检索必须按版本有效期过滤（见 C-22 配套约束与《评测报告》检索段）。</li>
 *
 *   <li><b>embedding 模型标识</b>（{@code embeddingModel}）—— 决策 <b>C-22 配套硬约束</b>：
 *       模型信息写入 chunk 元数据，查询时校验，不匹配则<strong>拒绝</strong>而非降级。
 *       依据《RAG 知识库文档如何更新》第 29、33、39、41 行。切分阶段就预留此字段，
 *       是为了让入库时"必须写入"成为结构上的强制，而不是靠记得填。</li>
 *
 *   <li><b>切分器版本</b>（{@code splitterVersion} + {@code strategy}）——
 *       《RAG 优化》<b>第 366 行</b>：<i>"每一轮只改变少量变量，同时记录解析器、Chunk、
 *       Embedding、索引、Query Rewrite、Rerank 和 Prompt 的版本"</i>。
 *       第 4 周消融实验要靠它说明"两次跑分差别来自哪一版切分"。</li>
 * </ul>
 *
 * <p>本类为可变 POJO（非 record）：字段会随后续阶段（入库 / 评测）逐步补齐
 * （如 {@code embeddingModel} 在切分时为空、入库时写入），
 * 用 builder 逐项赋值比一次性 20+ 参数的构造器更不易错。
 */
public class Chunk {

    // ── 标识 ────────────────────────────────────────────────────────────
    /** 稳定 chunk 标识（内容 + 结构位置的 SHA-256 前 16 位十六进制）。 */
    private String chunkId;

    /** chunk 类型：正文 / 表格。 */
    private ChunkType chunkType;

    // ── 来源文档 ────────────────────────────────────────────────────────
    /** 文档标识（本源取自文件名去扩展名，稳定且可读）。 */
    private String docId;

    /** 文档标题（元数据块「标题」字段，也是 H1）。 */
    private String docTitle;

    /** 文号，如 PLYH-GZ-2026-001。 */
    private String docNo;

    /** 版本，如 V2.0。 */
    private String version;

    /** 生效期起（ISO-8601，如 2026-09-16）。 */
    private String effectiveFrom;

    /** 生效期止；{@code null} 表示长期有效（对应语料写作的"长期有效"）。 */
    private String effectiveTo;

    /** 替代关系原文（"替代 V1.0（PLYH-GZ-2025-011）"或"无"）。 */
    private String supersedes;

    /** 主题类编号（由文件名前缀得出，如 "01"），用于按类过滤。 */
    private String docCategoryNo;

    // ── 层级结构（知识库第 109 行要求）──────────────────────────────────
    /** 章节路径，如 ["第一章 总则"]；无章时为 [文档标题]。 */
    private List<String> chapterPath = new ArrayList<>();

    /** 条号，如"第三条"；表格 chunk 记录其所属条。 */
    private String articleNo;

    /** 条标题（【】内文字），如"职责分工"。 */
    private String articleTitle;

    /**
     * 条内再拆分时的部件序号（从 1 起）；未拆分时为 0。
     *
     * <p>语料实测无一条超过阈值，故当前恒为 0——但该路径有单测覆盖
     * （见 {@code ChunkStructureSplitterTest}），以免将来语料变长时从未被执行过。
     */
    private int partIndex;

    /** 条内再拆分时的部件总数；未拆分时为 0。 */
    private int partCount;

    // ── 表格（仅 TABLE 类型）────────────────────────────────────────────
    /** 表头列名。 */
    private List<String> tableHeaders;

    /** 数据行，每行按列顺序取值；行内列数与表头一致。 */
    private List<List<String>> tableRows;

    // ── 版本与一致性 ────────────────────────────────────────────────────
    /** 切分策略标识，如 {@code structure-article-v1} / {@code fixed-length-baseline-v1}。 */
    private String strategy;

    /** 切分器版本。改变切分逻辑必须提升它，以便消融对比可追溯（知识库第 366 行）。 */
    private String splitterVersion;

    /** embedding 模型标识，切分阶段留空，入库时写入（C-22 配套硬约束）。 */
    private String embeddingModel;

    // ── 内容与度量 ──────────────────────────────────────────────────────
    /** chunk 正文（表格 chunk 为表格的 Markdown 原文）。 */
    private String content;

    /** 正文字符数（不含空白），用于块大小分布审计（知识库第 179–183 行）。 */
    private int charCount;

    /** 对来源文档的溯源位置：条目行号范围 "起-止"（1 起）。 */
    private String sourceLines;

    /** 扩展字段（保留给后续阶段，避免为每加一项元数据就改类结构）。 */
    private Map<String, Object> extra = new LinkedHashMap<>();

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public ChunkType getChunkType() {
        return chunkType;
    }

    public void setChunkType(ChunkType chunkType) {
        this.chunkType = chunkType;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public String getDocTitle() {
        return docTitle;
    }

    public void setDocTitle(String docTitle) {
        this.docTitle = docTitle;
    }

    public String getDocNo() {
        return docNo;
    }

    public void setDocNo(String docNo) {
        this.docNo = docNo;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(String effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public String getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(String effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public String getSupersedes() {
        return supersedes;
    }

    public void setSupersedes(String supersedes) {
        this.supersedes = supersedes;
    }

    public String getDocCategoryNo() {
        return docCategoryNo;
    }

    public void setDocCategoryNo(String docCategoryNo) {
        this.docCategoryNo = docCategoryNo;
    }

    public List<String> getChapterPath() {
        return chapterPath;
    }

    public void setChapterPath(List<String> chapterPath) {
        this.chapterPath = chapterPath;
    }

    public String getArticleNo() {
        return articleNo;
    }

    public void setArticleNo(String articleNo) {
        this.articleNo = articleNo;
    }

    public String getArticleTitle() {
        return articleTitle;
    }

    public void setArticleTitle(String articleTitle) {
        this.articleTitle = articleTitle;
    }

    public int getPartIndex() {
        return partIndex;
    }

    public void setPartIndex(int partIndex) {
        this.partIndex = partIndex;
    }

    public int getPartCount() {
        return partCount;
    }

    public void setPartCount(int partCount) {
        this.partCount = partCount;
    }

    public List<String> getTableHeaders() {
        return tableHeaders;
    }

    public void setTableHeaders(List<String> tableHeaders) {
        this.tableHeaders = tableHeaders;
    }

    public List<List<String>> getTableRows() {
        return tableRows;
    }

    public void setTableRows(List<List<String>> tableRows) {
        this.tableRows = tableRows;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getSplitterVersion() {
        return splitterVersion;
    }

    public void setSplitterVersion(String splitterVersion) {
        this.splitterVersion = splitterVersion;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getCharCount() {
        return charCount;
    }

    public void setCharCount(int charCount) {
        this.charCount = charCount;
    }

    public String getSourceLines() {
        return sourceLines;
    }

    public void setSourceLines(String sourceLines) {
        this.sourceLines = sourceLines;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
    }
}
