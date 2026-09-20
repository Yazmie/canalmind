package com.canalmind.rag.split;

import java.util.List;

/**
 * 切分策略接口。
 *
 * <p><b>为什么要有这个接口</b>：决策 <b>C-21</b> 明确要求
 * "结构化切分为主链路，<strong>固定长度切分保留为评测基线</strong>"，
 * 依据是《RAG 优化》<b>第 364 行</b>：
 * <i>"有了基线后，再依次比较固定长度、结构化、Parent-Child 和语义 Chunk"</i>。
 *
 * <p>故本项目<strong>至少会有两个实现同时存在</strong>，而且第 4 周还要比较
 * Parent-Child 与语义切分（C-21 记为"留作后续消融"）。
 * 用接口固定契约，是为了让消融实验能<strong>只替换策略、不触碰下游</strong>——
 * 若把切分逻辑写死在入库服务里，换策略就得改主链路，那就无法做到
 * 《RAG 优化》第 366 行要求的"每一轮只改变少量变量"。
 *
 * <p>实现须保证：<b>同样的输入产出同样的输出</b>（不含时间戳、不含随机数，
 * chunk 标识由内容派生）。这是评测可复现的前提。
 */
public interface Splitter {

    /**
     * 把一篇文档切分为 chunk 列表。
     *
     * @param doc 文档（含元数据与原文）
     * @return chunk 列表，顺序稳定（按文档出现顺序）
     */
    List<Chunk> split(CorpusDocument doc);

    /** 策略标识，写入每个 chunk 的 {@code strategy} 字段，用于消融对比时区分来源。 */
    String strategyName();

    /** 切分器版本，写入每个 chunk 的 {@code splitterVersion} 字段。 */
    String version();
}
