package com.canalmind.rag.tokenize;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * BM25 通道的停用词表（第 2 周第 4 项）。
 *
 * <p>与 {@link ShippingDictionary} 的两点刻意不同：
 * <ol>
 *   <li><b>允许注释</b>：这个文件由本类自己解析（不经过 jieba 的 loadUserDict），
 *       所以可以用 {@code #} 写选取口径——而词典文件**不行**（见
 *       {@link ShippingDictionary} 类注释第 2 条坑）；</li>
 *   <li><b>不需要词频</b>：停用词只用于"剔除"，一行一个词。</li>
 * </ol>
 *
 * <p>选取口径与"刻意不剔除哪些"写在资源文件头部（{@code stopwords.txt}），
 * 复核时读那一份即可，不在这里重复。
 *
 * <p>停用词表**参与档案指纹**（{@code rag_token_profile.stopwords_sha256}）：
 * 改一个词就会让指纹变化并拒绝就地重灌——这是刻意的，否则"两次评测跑分不同"
 * 无法归因到具体是哪一版停用词（知识库《RAG 优化》第 266–274 行要求版本化）。
 */
public final class StopwordList {

    /** 类路径资源路径。 */
    public static final String RESOURCE_PATH = "/rag/tokenize/stopwords.txt";

    private StopwordList() {
    }

    private record Loaded(String sha256, Set<String> words) {
    }

    private static final Loaded LOADED = load();

    private static Loaded load() {
        byte[] bytes;
        try (InputStream in = StopwordList.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("类路径上找不到停用词表 " + RESOURCE_PATH);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取停用词表失败：" + RESOURCE_PATH, e);
        }

        Set<String> words = new LinkedHashSet<>();
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split(String.valueOf((char) 10), -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!words.add(line)) {
                throw new IllegalStateException(
                        "停用词表有重复词「" + line + "」（" + RESOURCE_PATH + " 第 " + (i + 1) + " 行）；"
                                + "重复本身无害，但通常意味着编辑时漏删，故此处直接报错");
            }
        }
        if (words.isEmpty()) {
            throw new IllegalStateException("停用词表是空的：" + RESOURCE_PATH);
        }
        return new Loaded(sha256Hex(bytes), Collections.unmodifiableSet(words));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest(bytes)) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 是否为停用词。 */
    public static boolean contains(String term) {
        return LOADED.words().contains(term);
    }

    /** 停用词表内容的 sha256（十六进制小写），进档案、由数据库守卫校验。 */
    public static String sha256() {
        return LOADED.sha256();
    }

    /** 停用词条数。 */
    public static int count() {
        return LOADED.words().size();
    }

    /** 全部停用词（保序：文件顺序）。 */
    public static Set<String> words() {
        return LOADED.words();
    }
}
