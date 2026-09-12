package com.sqxdl.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输入历史（CLI 与 GUI 共用）。
 * GUI 用它配合 ↑/↓ 键翻阅历史输入；CLI 交互模式的方向键由 JLine 提供，
 * 而管道/重定向输入（无 TTY）无法使用方向键，用 bash 风格的
 * history 命令与 !! / !N 重新执行等效替代。
 */
public class CommandHistory {

    /** !! / !N 重新执行命令的形式（!N 编号从 1 起，与 history 列表一致） */
    private static final Pattern BANG = Pattern.compile("^(!{2}|!(\\d+))$");

    private final List<String> entries = new ArrayList<>();

    /** 记录一条输入：忽略空串；与上一条相同则不重复记录（类似 bash 去重）。 */
    public void add(String input) {
        if (input == null || input.isBlank()) {
            return;
        }
        if (entries.isEmpty() || !entries.get(entries.size() - 1).equals(input)) {
            entries.add(input);
        }
    }

    public int size() {
        return entries.size();
    }

    /** 按下标取历史条目（0 起，越界由调用方保证）。 */
    public String get(int index) {
        return entries.get(index);
    }

    /** 是否为重新执行命令（!! 或 !N 形式）。 */
    public boolean isBang(String input) {
        return BANG.matcher(input.trim()).matches();
    }

    /**
     * 解析 !! / !N 为对应的历史输入。
     *
     * @return 对应历史条目；不是该形式、历史为空或编号越界时返回 null
     */
    public String resolve(String input) {
        Matcher matcher = BANG.matcher(input.trim());
        if (!matcher.matches() || entries.isEmpty()) {
            return null;
        }
        // !! 取上一条；!N 取第 N 条（转为 0 起下标）
        int index = matcher.group(2) == null
                ? entries.size() - 1
                : Integer.parseInt(matcher.group(2)) - 1;
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    /** 带编号的历史清单（1 起，供 CLI history 命令输出）。 */
    public List<String> numbered() {
        List<String> lines = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            lines.add((i + 1) + "  " + entries.get(i));
        }
        return lines;
    }
}
