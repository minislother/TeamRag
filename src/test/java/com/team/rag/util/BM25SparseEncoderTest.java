package com.team.rag.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BM25SparseEncoderTest {

    /**
     * 验证 sparse 编码能同时保留中文关键词和英文代码 token。
     */
    @Test
    void encodeKeepsChineseAndCodeTermsSearchable() {
        BM25SparseEncoder encoder = new BM25SparseEncoder();

        var sparse = encoder.encode("Redis 缓存穿透 SETNX pipeline");

        assertThat(sparse.getIndices()).isNotEmpty();
        assertThat(sparse.getIndices()).hasSameSizeAs(sparse.getValues());
        assertThat(encoder.tokenize("缓存穿透")).contains("缓存", "穿透");
        assertThat(encoder.tokenize("SETNX pipeline")).contains("setnx", "pipeline");
    }
}
