package com.canalmind.chat.frame;

import java.util.List;

/**
 * 结构化帧里的一条**引用来源**（前端按学术引用样式渲染，不是"AI 来源卡片"）。
 *
 * <p>字段全部来自 {@code rag_chunk} 的元数据列（见 V3 迁移），
 * <b>不做任何拼接或推断</b>：文档名、文号、条款号、所属章节、第几段，
 * 在切分时就已经解析入库，这里只是把它们原样搬出来。
 * 拼装一句话（如"《过闸费费率表（2026版）》第四条"）是**前端**的事——
 * 服务端只出字段，因为同一份数据要供不同展示形态使用（列表、悬浮、导出）。
 *
 * @param chunkId      chunk 主键，供前端去重与排查
 * @param docTitle     文档标题（如「过闸费费率表（2026版）」）
 * @param docNo        文号（如 {@code PLYH-FL-2026-006}），可能为空
 * @param articleNo    条款号（如「第四条」），可能为空（表格块等）
 * @param articleTitle 条款标题（如「【建材类过闸费费率】」），可能为空
 * @param chapterPath  章节路径（如 {@code ["第二章 费率标准"]}）
 * @param partIndex    该条款被切成的第几段（从 1 起）
 * @param partCount    该条款一共被切成几段（用于渲染"第 1/2 段"）
 * @param excerpt      正文前若干字的摘录，换行已折成空格（见 {@link ChatFrameCodec#excerpt}）
 */
public record FrameCitation(String chunkId, String docTitle, String docNo, String articleNo,
                            String articleTitle, List<String> chapterPath, int partIndex,
                            int partCount, String excerpt) {

    public FrameCitation {
        chapterPath = chapterPath == null ? List.of() : List.copyOf(chapterPath);
    }
}
