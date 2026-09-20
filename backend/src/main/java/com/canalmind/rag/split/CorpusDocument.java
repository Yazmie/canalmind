package com.canalmind.rag.split;

import java.nio.file.Path;

/**
 * 一篇语料文档：8 字段元数据块 + 原始正文。
 *
 * <p>元数据块是决策 <b>C-20</b> 的产物，也是 <b>C-21 结构化切分的前置条件</b>——
 * C-20 记录原文：<i>"元数据块是结构化切分（C-21）的前置条件——无元数据则「来源和版本」
 * 无法满足规格书第 626 行要求"</i>。
 *
 * @param docId        文档标识（文件名去扩展名）
 * @param categoryNo   主题类编号（文件名前缀，如 "01"）
 * @param docNo        文号（元数据字段「文号」）
 * @param title        标题（元数据字段「标题」）
 * @param issuer       发布机构
 * @param version      版本
 * @param effectiveFrom 生效期起（ISO-8601）
 * @param effectiveTo  生效期止；{@code null} 表示长期有效
 * @param supersedes   替代关系
 * @param note         说明
 * @param rawText      完整原文（含元数据块），**已归一化**（去 BOM、换行统一为 LF），
 *                     切分时逐行处理，用行号做溯源
 * @param sourceFile   来源文件绝对路径（仅用于报告，不进入 chunk 元数据，
 *                     以保证 chunk 内容与机器路径无关、可跨机器复现）
 */
public record CorpusDocument(
        String docId,
        String categoryNo,
        String docNo,
        String title,
        String issuer,
        String version,
        String effectiveFrom,
        String effectiveTo,
        String supersedes,
        String note,
        String rawText,
        Path sourceFile) {

    /**
     * 紧凑构造器：**在构造时就归一化 rawText**。
     *
     * <p><b>为什么必须放在这里而不是 {@code parse}</b>：{@code parse} 只归一化自己的局部变量，
     * 却把**原样**的 {@code text} 存进了 {@code rawText}。于是
     * {@code ChunkStructureSplitter.split()} 里的 {@code findBodyStartLine(doc.rawText())}
     * 拿到的是未归一化文本——实测同一篇文档 LF 版返回 15、CRLF 版返回 **29**，
     * 表格也因此静默不被识别（少切一块，且**不报错**）。
     *
     * <p>放在构造器里的好处：不管调用方是 {@code parse}、CLI、测试还是未来的导入器，
     * 都拿不到未归一化的 {@code rawText}，这个不一致就**不可能**再出现。
     * 这类"把不变量钉在数据边界上"的修法，比在每个使用点各自容错可靠得多。
     */
    public CorpusDocument {
        rawText = DocumentParser.normalizeText(rawText);
    }

    /** 生效期是否覆盖给定日期（ISO-8601）；长期有效视为无上界。 */
    public boolean isEffectiveOn(String isoDate) {
        if (effectiveFrom != null && isoDate.compareTo(effectiveFrom) < 0) {
            return false;
        }
        return effectiveTo == null || isoDate.compareTo(effectiveTo) < 0;
    }

    /** 是否已废止（有明确终止日期）。用于《评测报告》区分现行/废止版本。 */
    public boolean isSuperseded() {
        return effectiveTo != null;
    }
}
