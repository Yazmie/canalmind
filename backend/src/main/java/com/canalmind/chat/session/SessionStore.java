package com.canalmind.chat.session;

/**
 * 会话上下文的**存储缝**（决策 C-38④）。
 *
 * <p><b>为什么现在就要留一个接口</b>：Redis 实现要等"需要多实例"那一天，而那一天来了
 * 再动 {@link ContextAssembler} 的内部实现，就得连它的测试一起重做。C-38④ 只要求"留接口"，
 * 于是本阶段唯一实现是 {@link InMemorySessionStore}，而全部上下文测试都跑在内存实现上——
 * 换存储时一行测试都不用改。
 *
 * <p><b>为什么本阶段"进程内"是正确的选择（⛔ 不是图省事）</b>：C-38 的备注已记录实测事实——
 * 本机 Redis 6379 **当前未监听**；而本阶段只有单机单 JVM、单会话 UI。
 * 引入外部存储会立刻多出一条新的失败路径：「连接失败 ⇒ 上下文为空 ⇒ 模型凭空作答」，
 * 而它伪装出来的症状是"模型不会做指代消解/参数继承"——属于本项目反复吃亏的
 * 「失败被伪装」（`docs/工程复盘/检索工程/分档TopK不能只调一个数.md` 记录的正是这一类）。
 *
 * <p><b>{@link #load(String)} 对未知会话返回空状态而不是 null</b>：本层每回合都要先 load，
 * 若返 null 则每个调用点都得判空、漏一处就是 NPE；而"空会话"本身就是合法状态（新会话第一句）。
 * 更关键的是：用 null 表达"没有"会让「**没有**这个会话」与「**读不到**这个会话」看起来一样。
 */
public interface SessionStore {

    /** 读取会话；未知会话返回 {@link SessionState#empty(String)}（⛔ 不返 null）。 */
    SessionState load(String sessionId);

    /** 覆盖保存（⛔ 不做增量合并——状态对象本身就是完整快照）。 */
    void save(SessionState state);

    /** 删除会话（显式结束会话时用）。 */
    void clear(String sessionId);

    /** 当前在存的会话数：供测试与容量自证（⛔ 它不是业务指标，别往评测报告里写）。 */
    int sessionCount();
}
