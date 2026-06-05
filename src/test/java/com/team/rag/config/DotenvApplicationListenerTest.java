package com.team.rag.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DotenvApplicationListenerTest {

    @TempDir
    Path tempDir;

    /**
     * 验证 .env 解析能处理 BOM、export、引号和行内注释。
     */
    @Test
    void parseEnvHandlesBomCommentsAndQuotes() throws Exception {
        Path env = tempDir.resolve(".env");
        java.nio.file.Files.writeString(
                env,
                "\uFEFF# local env\n"
                        + "export LOCAL_MODEL_KEY=\"dummy-local-token\"\n"
                        + "PINECONE_INDEX=team-index # comment\n",
                StandardCharsets.UTF_8
        );

        var parsed = DotenvApplicationListener.parseEnv(env);

        assertThat(parsed).containsEntry("LOCAL_MODEL_KEY", "dummy-local-token");
        assertThat(parsed).containsEntry("PINECONE_INDEX", "team-index");
    }
}
