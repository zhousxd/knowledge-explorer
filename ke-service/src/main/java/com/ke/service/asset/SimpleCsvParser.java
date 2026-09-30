package com.ke.service.asset;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 手写极简 CSV 解析（MVP 不引第三方库）：按物理行切分，支持
 * - 双引号包裹字段：内部逗号不切分；
 * - "" 转义为字面引号；
 * - 空行（含纯空白行）跳过，但物理行号保留（导入错误按行号回报）；
 * - UTF-8 BOM 剥离。
 * 结构坏行（引号未闭合 / 引号位置非法 / 闭合后多余字符）只作废该行：
 * 以 {@link Row#error()} 非空的 Row 返回，由调用方按行号收集为导入错误后继续后续行。
 * 不支持跨行字段（知识资产导入的字段均为单行短文本，够用）。
 */
final class SimpleCsvParser {

    /** 单行解析结果：error 非空 = 结构坏行（fields 为空，仅保留行号与原因） */
    record Row(int line, List<String> fields, String error) {
        static Row of(int line, List<String> fields) {
            return new Row(line, fields, null);
        }

        static Row bad(int line, String reason) {
            return new Row(line, List.of(), "第" + line + "行: " + reason);
        }

        boolean failed() {
            return error != null;
        }
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
            try {
                rows.add(Row.of(i + 1, splitLine(line)));
            } catch (IllegalArgumentException e) {
                rows.add(Row.bad(i + 1, e.getMessage()));
            }
        }
        return rows;
    }

    private static List<String> splitLine(String line) {
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
                    throw new IllegalArgumentException("引号位置非法");
                }
            } else if (quoteClosed) {
                throw new IllegalArgumentException("引号闭合后有多余字符");
            } else {
                current.append(c);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("引号未闭合");
        }
        fields.add(current.toString());
        return fields;
    }
}
