package com.canalmind.rag.tokenize;

import com.huaban.analysis.jieba.WordDictionary;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 自定义航运词典（第 2 周第 4 项，规格书第 629 行"**自定义航运词典**"）。
 *
 * <p>文件由 {@code backend/scripts/build_shipping_dictionary.py} **生成**，
 * 权威来源是 {@code generate_seed_data.py} 的 PORTS / LOCKS / CARGO_TYPES / VESSEL_TYPES
 * 与 {@code V1__init_business_schema.sql} 第 232–234 行的 CHECK 枚举。
 * 手写内容只允许出现在生成脚本的 {@code CURATED_TERMS} 表里——这样"词典与业务数据一致"
 * 是**机器保证**的，而不是靠人记得同步（本项目在语料上已经吃过一次跨来源冲突的亏，
 * 见 {@code docs/规则语料/README.md} §4.1）。
 *
 * <h2>⚠️ 两类"不报错、只静默失效"的坑（本类的解析器就是为拦它们而写的）</h2>
 *
 * <p>jieba-analysis 的 {@code WordDictionary.loadUserDict} 用
 * {@code line.split("[\t ]+")} 取前两个字段，并且：
 * <ol>
 *   <li><b>恰好两列才取词频</b>：写成标准 jieba 的 {@code 词 词频 tag}（三列）时，
 *       {@code tokens.length == 3}，于是词频**静默退回默认值 3.0**。
 *       实测基础词典 {@code total = 60101878}，{@code log(3/60101878) = -16.81}，
 *       比基础词典里任何词都低（常用实词约 -7 ~ -11）——结果是自定义词
 *       **几乎永远不会被切出来，且不报错**。故本类对列数做**严格**校验。</li>
 *   <li><b>注释行会抛异常</b>：以 {@code #} 开头的行会被解析成「词 = {@code #}、
 *       词频 = 注释正文」，随后 {@code Double.valueOf} 抛 NumberFormatException，
 *       而该异常**不被 loadUserDict 捕获**（它只 catch IOException）。
 *       故词典文件里**不允许任何注释**——来源与版本只能写在本类的 javadoc
 *       与生成脚本里。</li>
 * </ol>
 *
 * <h2>身份用 sha256，而不是版本号</h2>
 *
 * <p>{@link #sha256()} 是**文件字节**的摘要，会被写进 {@code rag_token_profile}
 * 并由数据库守卫 {@code rag_assert_token_profile} 校验：词典改一个词，指纹就变，
 * 就地重灌会被拒绝，必须先新建档案。理由见 V4 迁移文件头——
 * "版本号靠人记得改，sha256 由文件字节算出"。
 *
 * <h2>加载时机</h2>
 *
 * <p>在**类初始化**时加载一次（{@code LOADED} 静态字段）。jieba 的
 * {@code WordDictionary} 是全局单例，本类保证每个 JVM 只灌一次词典，
 * 且灌完之后不再写入——这是 {@link JiebaShippingTokenizer} 可以并发使用的前提。
 */
public final class ShippingDictionary {

    /** 类路径资源路径（{@code Class.getResourceAsStream} 要求绝对路径以 "/" 开头）。 */
    public static final String RESOURCE_PATH = "/rag/tokenize/shipping-dictionary.txt";

    /**
     * 词典版本（人读的标识，进档案名）。
     *
     * <p>⚠️ 它**不**随文件内容自动变——内容变了由 {@link #sha256()} 发现。
     * 若确实要新建一版档案（例如换分词粒度），改这里比改文件名更省事。
     */
    public static final String VERSION = "shipping-dict-v1";

    /** 词频下限：实测基础词典常见词最低约 -11（log 概率），取 1 以上即可，这里留足余量。 */
    private static final long MIN_FREQ = 1L;

    private static final String SHA256_PATTERN = "^[0-9a-f]{64}$";

    private ShippingDictionary() {
    }

    /** 加载结果（不可变）。 */
    private record Loaded(String sha256, Map<String, Integer> freqs) {
    }

    /**
     * 类初始化即加载：读资源 → 严格校验 → 灌进 jieba 的全局词典。
     *
     * <p>刻意**不用懒加载**：分词器是检索链路的热路径对象，"第一次调用时才加载"
     * 会把"词典坏了"这个错误推迟到某次线上查询里，而且是在一个难以定位的线程上。
     * 静态初始化失败会直接以 ExceptionInInitializerError 暴露，位置明确。
     */
    private static final Loaded LOADED = load();

    private static Loaded load() {
        byte[] bytes = readResourceBytes();
        String sha = sha256Hex(bytes);
        Map<String, Integer> freqs = parse(new String(bytes, StandardCharsets.UTF_8));

        // 真正的加载交给 jieba 自己（它会写进全局单例的词表与 trie）。
        // ⚠️ 放在本类的校验**之后**：先确认文件格式没问题，再让库去解析——
        // 否则三列格式那种情况会"加载成功但词频是默认值"，静默失效。
        //
        // ⚠️ jieba-analysis 1.0.2 的 loadUserDict **只接受 java.nio.file.Path**
        // （javap 核对：loadUserDict(Path) / loadUserDict(Path, Charset)，没有 InputStream 重载），
        // 而本词典是打进 jar 的类路径资源，没有真实路径，故必须先落一份**字节完全相同**的临时文件
        // 再交给库。sha256 算的是资源字节、临时文件是同一份 bytes 写出来的，两者一致。
        Path temp = null;
        try {
            temp = Files.createTempFile("canalmind-shipping-dict-", ".txt");
            Files.write(temp, bytes);
            WordDictionary.getInstance().loadUserDict(temp, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "加载航运词典失败（落临时文件或读取时出错）：" + RESOURCE_PATH, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    // 删不掉不影响正确性（词条已在内存里），但不能吞掉——留痕便于发现临时目录异常
                    System.err.println("警告：航运词典临时文件未能删除：" + temp);
                }
            }
        }
        verifyLoaded(freqs.keySet());
        return new Loaded(sha, Collections.unmodifiableMap(freqs));
    }

    /**
     * 加载后的断言：**每个词条都真的进了 jieba 的词表**。
     *
     * <p>为什么必须断言：{@code loadUserDict} 把 IOException **吞掉只打日志**
     * （方法签名不抛异常），所以"临时文件没写成""文件被占用读不了"这类失败
     * 在它的视角下是<b>静默</b>的——分词继续用基础词典，结果只是"某些词被切碎"。
     * {@code containsWord} 查的是词典 trie，逐条核对能把这类失败变成启动即报错。
     *
     * <p>代价是 164 次 trie 查询（忽略不计），换来"词典一定生效"这个前提成立。
     */
    private static void verifyLoaded(java.util.Set<String> words) {
        WordDictionary dictionary = WordDictionary.getInstance();
        List<String> missing = new ArrayList<>();
        for (String word : words) {
            if (!dictionary.containsWord(word)) {
                missing.add(word);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "航运词典加载后核对失败：" + missing.size() + " 个词条不在 jieba 词表里（前几个："
                            + missing.subList(0, Math.min(5, missing.size())) + "）。"
                            + "loadUserDict 会把 IOException 吞掉只打日志，故此处显式核对，"
                            + "避免「词典没生效」被静默忽略。");
        }
    }

    private static byte[] readResourceBytes() {
        try (InputStream in = ShippingDictionary.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException(
                        "类路径上找不到航运词典 " + RESOURCE_PATH
                                + "。它由 backend/scripts/build_shipping_dictionary.py 生成，"
                                + "请先运行该脚本（或检查 resources 是否被打进 classpath）。");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取航运词典失败：" + RESOURCE_PATH, e);
        }
    }

    /**
     * 严格解析：格式不对就**报错**，不"容错跳过"。
     *
     * <p>为什么不容错：这些错误全都有"跳过之后程序照跑、只是词典失效"的性质，
     * 而词典失效的症状是"某些查询召回变少"——最费时间的一类 bug。
     * 让它在这里停下，错误信息里直接给出生成脚本的路径。
     */
    static Map<String, Integer> parse(String content) {
        Map<String, Integer> freqs = new LinkedHashMap<>();
        String[] lines = content.split(String.valueOf((char) 10), -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                // 允许**唯一的**结尾空串（文件以 LF 结尾时的 split 产物）。
                if (i == lines.length - 1) {
                    continue;
                }
                throw malformed(i + 1, "空行", "空行会让 loadUserDict 执行 freqs.put(null, ...)");
            }
            if (line.startsWith("#")) {
                throw malformed(i + 1, "注释行",
                        "以 # 开头的行会被解析成「词=#、词频=注释正文」，"
                                + "随后 Double.valueOf 抛 NumberFormatException 且不被捕获");
            }
            String[] parts = line.split("[\t ]+");
            if (parts.length != 2) {
                throw malformed(i + 1, "字段数为 " + parts.length,
                        "词典每行必须**恰好两列**「词 词频」；"
                                + "写成标准 jieba 的三列（词 词频 tag）会让词频静默退回默认值 3.0，"
                                + "自定义词几乎不会被切出（实测 log(3/60101878) = -16.81）");
            }
            String word = parts[0];
            long freq;
            try {
                freq = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                throw malformed(i + 1, "词频不是整数：" + parts[1], "词频必须是正整数");
            }
            if (freq < MIN_FREQ) {
                throw malformed(i + 1, "词频 " + freq + " < " + MIN_FREQ, "词频必须为正整数");
            }
            if (!word.equals(word.trim())) {
                throw malformed(i + 1, "词两端有空白：" + word, "空白会让同一个词变成两个词元");
            }
            if (freqs.putIfAbsent(word, (int) freq) != null) {
                throw malformed(i + 1, "重复词：" + word, "重复条目会让词频取决于是哪一行先被读到");
            }
        }
        if (freqs.isEmpty()) {
            throw new IllegalStateException("航运词典是空的：" + RESOURCE_PATH);
        }
        return freqs;
    }

    private static IllegalStateException malformed(int lineNo, String what, String why) {
        return new IllegalStateException(
                "航运词典格式错误（" + RESOURCE_PATH + " 第 " + lineNo + " 行：" + what + "）。"
                        + "原因：" + why + "。"
                        + "该文件由 backend/scripts/build_shipping_dictionary.py 生成，"
                        + "请勿手工编辑；需要加词请改脚本里的 CURATED_TERMS 后重新生成。");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest(bytes)) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            String hex = sb.toString();
            if (!hex.matches(SHA256_PATTERN)) {
                throw new IllegalStateException("算出的 sha256 形状不对：" + hex);
            }
            return hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 词典内容的 sha256（十六进制小写），进档案、由数据库守卫校验。 */
    public static String sha256() {
        return LOADED.sha256();
    }

    /** 词条数。 */
    public static int termCount() {
        return LOADED.freqs().size();
    }

    /** 全部词条（保序：文件顺序 = 段序，便于自检输出可比对）。 */
    public static Set<String> words() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(LOADED.freqs().keySet()));
    }

    /** 词条 → 词频（只读）。 */
    public static Map<String, Integer> frequencies() {
        return LOADED.freqs();
    }
}
