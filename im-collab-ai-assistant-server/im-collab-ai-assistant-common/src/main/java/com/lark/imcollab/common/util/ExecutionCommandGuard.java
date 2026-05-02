package com.lark.imcollab.common.util;

import java.util.Locale;

public final class ExecutionCommandGuard {

    private ExecutionCommandGuard() {
    }

    public static boolean isExplicitExecutionRequest(String input) {
        String normalized = normalize(input);
        if (normalized.isBlank() || looksLikeMetaQuestion(normalized)) {
            return false;
        }
        String compact = compact(normalized);
        if (compact.contains("回复开始执行") || compact.contains("回复确认执行")) {
            return false;
        }
        return compact.equals("执行")
                || compact.equals("开始执行")
                || compact.equals("确认执行")
                || compact.equals("执行吧")
                || compact.equals("开始吧")
                || compact.equals("重试")
                || compact.equals("重试一下")
                || compact.equals("再试一次")
                || compact.equals("继续执行")
                || compact.equals("重新执行")
                || compact.contains("没问题执行")
                || compact.contains("可以执行")
                || compact.contains("好的执行")
                || compact.contains("按这个执行")
                || compact.contains("按计划执行")
                || compact.contains("就按这个执行")
                || normalized.contains("confirm execution")
                || normalized.contains("execute plan")
                || "execute".equals(normalized)
                || "confirm".equals(normalized);
    }

    private static boolean looksLikeMetaQuestion(String normalized) {
        return normalized.contains("为什么")
                || normalized.contains("怎么")
                || normalized.contains("如何")
                || normalized.contains("什么意思")
                || normalized.contains("完整计划")
                || normalized.contains("详细计划")
                || normalized.contains("计划给我")
                || normalized.contains("进度")
                || normalized.contains("状态");
    }

    private static String normalize(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }

    private static String compact(String input) {
        return input == null ? "" : input
                .replaceAll("\\s+", "")
                .replace("“", "")
                .replace("”", "")
                .replace("\"", "")
                .replace("'", "")
                .replace("？", "")
                .replace("?", "")
                .replace("。", "")
                .replace(".", "")
                .replace("，", "")
                .replace(",", "")
                .replace("！", "")
                .replace("!", "");
    }
}
