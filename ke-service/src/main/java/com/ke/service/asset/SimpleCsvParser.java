package com.ke.service.asset;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 手写极简 CSV 解析（MVP 不引第三方库）：按物理行切分，支持
 * - 双引号包裹字段：内部逗号不切分；
 * - "" 转义为字面引号；
 * - 空行（含纯空白行）跳过，但物理行号保留（导入错误按行号回报）；
 * - UTF-8 BOM 剥离；引号未闭合 / 引号后有多余字符 → IllegalArgumentException（行内致命错误）。
 * 不支持跨行字段（知识资产导入的字段均为单行短文本，够用）。
 */
final class SimpleCsvParser {

    record Row(int line, List<String> fields) {
    }

    private SimpleCsvParser() {
    }

    static List<Row> parse(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<Row> rows = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            if (line.isBlank()) {
                continue;
            }
            rows.add(new Row(i + 1, splitLine(i + 1, line)));
        }
        return rows;
    }

    private static List<String> splitLine(int lineNo, String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean quoteClosed = false; // 当前字段已结束引号包裹（其后只允许 , 或行尾）
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                        quoteClosed = true;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
                quoteClosed = false;
            } else if (c == '"') {
                if (current.isEmpty() && !quoteClosed) {
                    inQuotes = true;
                } else {
                    throw new IllegalArgumentException("第" + lineNo + "行: 引号位置非法");
                }
            } else if (quoteClosed) {
                throw new IllegalArgumentException("第" + lineNo + "行: 引号闭合后有多余字符");
            } else {
                current.append(c);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("第" + lineNo + "行: 引号未闭合");
        }
        fields.add(current.toString());
        return fields;
    }
}
