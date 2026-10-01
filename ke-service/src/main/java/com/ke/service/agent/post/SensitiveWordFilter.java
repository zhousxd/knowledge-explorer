package com.ke.service.agent.post;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * 敏感词过滤器（FR-S10）：启动时整表加载 classpath:sensitive-words.txt（每行一词，# 注释），
 * 命中词替换为等长 '*'。
 *
 * <p>MVP 词表规模小（20 词），逐词 contains 扫描足够（每次调用 O(词数×文本长)）；
 * 词表增长后二期升级 AC 自动机（占位注释）。
 */
@Component
public class SensitiveWordFilter {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordFilter.class);
    private static final String RESOURCE = "sensitive-words.txt";

    /** text 为替换后的文本（null 入参原样返回），hits 为命中替换的总次数 */
    public record FilterResult(String text, int hits) {
    }

    private final List<String> words;

    public SensitiveWordFilter() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(RESOURCE),
                        "classpath 缺少 " + RESOURCE), StandardCharsets.UTF_8))) {
            words = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("敏感词表加载失败: " + RESOURCE, e);
        }
        log.info("敏感词表加载 {} 词", words.size());
    }

    public FilterResult filter(String text) {
        if (text == null || text.isEmpty()) {
            return new FilterResult(text, 0);
        }
        String result = text;
        int hits = 0;
        for (String word : words) {
            if (!result.contains(word)) {
                continue;
            }
            int index = 0;
            while ((index = result.indexOf(word, index)) >= 0) {
                hits++;
                index += word.length();
            }
            result = result.replace(word, "*".repeat(word.length()));
        }
        return new FilterResult(result, hits);
    }
}
