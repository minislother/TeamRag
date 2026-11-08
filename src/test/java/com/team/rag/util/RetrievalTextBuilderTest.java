package com.team.rag.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalTextBuilderTest {

    @Test
    void build_AddsSearchableMetadataWithoutChangingBody() {
        String result = RetrievalTextBuilder.build("提前三个工作日提交。", Map.of(
                "filename", "社团管理制度.pdf",
                "docType", "制度",
                "headingPath", "第三章 场地申请",
                "project", "internal"
        ));

        assertThat(result).contains("filename: 社团管理制度.pdf")
                .contains("docType: 制度")
                .contains("headingPath: 第三章 场地申请")
                .doesNotContain("project: internal")
                .endsWith("content:\n提前三个工作日提交。");
    }
}
