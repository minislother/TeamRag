package com.team.rag.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量 .env 加载器。
 *
 * <p>项目需要复用 RepoPilot 原来的 .env，所以这里在 Spring 解析配置前把项目根目录
 * 的 .env 注入 Environment。解析器会处理 UTF-8 BOM、注释、export 前缀和简单引号。</p>
 */
public class DotenvApplicationListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final String SOURCE_NAME = "team-rag-dotenv";

    /**
     * 在 Spring 环境准备阶段读取项目根目录 .env，并插入最高优先级配置源。
     */
    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Path envPath = Path.of(System.getProperty("user.dir")).resolve(".env");
        if (!Files.isRegularFile(envPath)) {
            return;
        }
        try {
            Map<String, Object> values = parseEnv(envPath);
            if (!values.isEmpty()) {
                event.getEnvironment().getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, values));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("读取 .env 失败: " + envPath, ex);
        }
    }

    /**
     * 解析 .env 文件中的键值对，支持 BOM、export 前缀、引号和行内注释。
     */
    static Map<String, Object> parseEnv(Path envPath) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String rawLine : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
            String line = stripBom(rawLine).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            String value = stripInlineComment(line.substring(equals + 1).trim());
            values.put(key, unquote(value));
        }
        return values;
    }

    /**
     * 移除 UTF-8 BOM，避免首个配置键名前带隐藏字符。
     */
    private static String stripBom(String line) {
        if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
            return line.substring(1);
        }
        return line;
    }

    /**
     * 移除未加引号值中的行内注释，保留引号内的原始内容。
     */
    private static String stripInlineComment(String value) {
        boolean quoted = value.startsWith("\"") || value.startsWith("'");
        if (quoted) {
            return value;
        }
        int comment = value.indexOf(" #");
        return comment >= 0 ? value.substring(0, comment).trim() : value;
    }

    /**
     * 去掉简单成对引号，得到实际环境变量值。
     */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
