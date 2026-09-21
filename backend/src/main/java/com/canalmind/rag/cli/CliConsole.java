package com.canalmind.rag.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 命令行入口的公共小事（第 2 周第 4 项）。
 *
 * <p>只有两件事，但两件都踩过：
 * <ol>
 *   <li>{@link #pinUtf8Stdout()}——Windows 下 {@code System.out} 默认按控制台代码页
 *       （实测 GBK）编码，中文输出会变乱码。检索链路的证据全是中文
 *       （词元、条标题），乱码会让"证据"没法直接粘进文档，故必须钉死 UTF-8。</li>
 *   <li>{@link #envOr(String, String)}——参数默认值 = 命令行为空时取环境变量。</li>
 * </ol>
 *
 * <p>⚠️ 刻意**没有**把 {@code PgVectorIngestCli} 里同名的私有方法改过来调用本类：
 * 那个入口已被验证过（验收记录在 docs/进度看板.md §3.3），
 * 为一个 6 行方法去动它是"改工作代码换零收益"，不在本项范围内。
 */
public final class CliConsole {

    private CliConsole() {
    }

    /** 把 {@code System.out} 固定成 UTF-8（不随控制台代码页变化）。 */
    public static void pinUtf8Stdout() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
    }

    /** 环境变量优先，缺省用 {@code fallback}。 */
    public static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    /** 需要真实换行时用字符常量，不用转义字面量（AGENTS.md §7.2 第 1 条）。 */
    public static final String LF = String.valueOf((char) 10);
}
