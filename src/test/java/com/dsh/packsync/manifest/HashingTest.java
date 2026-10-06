package com.dsh.packsync.manifest;

import com.dsh.packsync.core.util.Hashing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 哈希层验证。重点是 <b>CurseForge murmur 必须与官方算法逐位一致</b> ——
 * 差一位就意味着永远匹配不到任何公共文件，而它不会报错，只会静默地"查无此文件"。
 *
 * <p>参照实现沿用既有的
 * {@code getCurseforgeMurmurHash} 算法（它已在真实环境中验证过能被
 * CurseForge 接受）。本测试把「流式两遍」的实现与「一次性读入内存」的参照实现对比。
 */
class HashingTest {

    // ── 参照实现：既有逻辑 ────────────────────────────────

    private static String referenceMurmur(byte[] data) {
        ByteArrayOutputStream filtered = new ByteArrayOutputStream();
        long length = 0L;
        for (byte raw : data) {
            int b = raw & 0xFF;
            if (b == 9 || b == 10 || b == 13 || b == 32) {
                continue;
            }
            filtered.write(b);
            length++;
        }
        byte[] fb = filtered.toByteArray();

        long k = 0L;
        int shift = 0;
        long h = 1L ^ length; // seed = 1
        for (byte byteVal : fb) {
            char b = (char) (byteVal & 0xFF);
            k |= (long) b << shift;
            if ((shift += 8) != 32) {
                continue;
            }
            h = 0xFFFFFFFFL & h;
            k *= 1540483477L;
            k = 0xFFFFFFFFL & k;
            k ^= k >> 24;
            k = 0xFFFFFFFFL & k;
            k *= 1540483477L;
            k = 0xFFFFFFFFL & k;
            h *= 1540483477L;
            h = 0xFFFFFFFFL & h;
            h ^= k;
            h = 0xFFFFFFFFL & h;
            k = 0L;
            shift = 0;
        }
        if (shift > 0) {
            h ^= k;
            h = 0xFFFFFFFFL & h;
            h *= 1540483477L;
            h = 0xFFFFFFFFL & h;
        }
        h ^= h >> 13;
        h = 0xFFFFFFFFL & h;
        h *= 1540483477L;
        h = 0xFFFFFFFFL & h;
        h ^= h >> 15;
        h = 0xFFFFFFFFL & h;
        return String.valueOf(h);
    }

    // ── 对比测试 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("murmur 与参照实现在各种长度下逐位一致（含 0/1/3/4/5 字节边界）")
    void murmurMatchesReferenceAcrossLengths(@TempDir Path dir) throws IOException {
        // 覆盖 4 字节块的边界：0、1、2、3、4、5、7、8、9、16、17…
        int[] lengths = {0, 1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 63, 64, 65, 1000, 4097};
        for (int len : lengths) {
            byte[] data = new byte[len];
            new Random(1234 + len).nextBytes(data);
            Path f = dir.resolve("case-" + len + ".bin");
            Files.write(f, data);

            String expected = referenceMurmur(data);
            String actual = Hashing.curseforgeMurmur(f);
            assertEquals(expected, actual, "长度 " + len + " 字节时 murmur 不一致");
        }
    }

    @Test
    @DisplayName("murmur 会剔除 \\t \\n \\r 空格（与官方语义一致），但不剔除其他字节")
    void murmurIgnoresWhitespaceOnly(@TempDir Path dir) throws IOException {
        byte[] base = "PK\u0003\u0004some-jar-content".getBytes(StandardCharsets.ISO_8859_1);
        // 不手写转义：直接在 base 的每个字节之间插入四种空白字节，
        // 由构造过程本身保证"过滤后与 base 完全相同"，避免手工排布出错。
        ByteArrayOutputStream ws = new ByteArrayOutputStream();
        byte[] whitespace = {'\r', '\n', '\t', ' '};
        int w = 0;
        for (byte b : base) {
            ws.write(whitespace[w++ % whitespace.length]);
            ws.write(b);
        }
        byte[] withWs = ws.toByteArray();

        // 参照实现算出的两个值应当相等；我们的实现也必须给出同一个值。
        assertEquals(referenceMurmur(base), referenceMurmur(withWs),
                "前提检查：参照实现应把空白剔除后视为同一内容");

        Path a = dir.resolve("a.bin");
        Path b = dir.resolve("b.bin");
        Files.write(a, base);
        Files.write(b, withWs);
        assertEquals(Hashing.curseforgeMurmur(a), Hashing.curseforgeMurmur(b),
                "空白字节不应影响 murmur");

        // 反过来：非空白字节必须影响结果，否则算法就"过度过滤"了
        byte[] different = "PK\u0003\u0004some-jar-contenT".getBytes(StandardCharsets.ISO_8859_1);
        Path c = dir.resolve("c.bin");
        Files.write(c, different);
        assertNotEquals(Hashing.curseforgeMurmur(a), Hashing.curseforgeMurmur(c));
    }

    @Test
    @DisplayName("murmur 与参照实现在较大的随机文件上也一致（跨多个读缓冲）")
    void murmurMatchesReferenceOnLargeFile(@TempDir Path dir) throws IOException {
        byte[] data = new byte[300_000];
        new Random(99).nextBytes(data);
        // 掺入大量空白，确保过滤逻辑在跨缓冲边界时也正确
        for (int i = 0; i < data.length; i += 7) {
            data[i] = (byte) '\n';
        }
        Path f = dir.resolve("large.bin");
        Files.write(f, data);

        assertEquals(referenceMurmur(data), Hashing.curseforgeMurmur(f));
    }

    @Test
    @DisplayName("murmur 返回无符号十进制（不出现负数）")
    void murmurIsUnsignedDecimal(@TempDir Path dir) throws IOException {
        // 多试几个种子，确保总能撞到高位为 1 的情况
        for (int seed = 0; seed < 200; seed++) {
            byte[] data = new byte[64];
            new Random(seed).nextBytes(data);
            Path f = dir.resolve("s" + seed + ".bin");
            Files.write(f, data);
            String murmur = Hashing.curseforgeMurmur(f);
            assertNotNull(murmur);
            assertNotEquals('-', murmur.charAt(0), "murmur 不应是负数：" + murmur);
        }
    }

    // ── SHA-1 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SHA-1 符合公开测试向量")
    void sha1KnownVectors(@TempDir Path dir) throws IOException {
        Path empty = dir.resolve("empty.bin");
        Files.write(empty, new byte[0]);
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Hashing.sha1(empty));

        Path abc = dir.resolve("abc.bin");
        Files.write(abc, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Hashing.sha1(abc));
    }

    @Test
    @DisplayName("不存在的文件返回 null 而不是抛异常")
    void missingFileIsNull(@TempDir Path dir) {
        assertEquals(null, Hashing.sha1(dir.resolve("nope.bin")));
        assertEquals(null, Hashing.curseforgeMurmur(dir.resolve("nope.bin")));
    }
}
