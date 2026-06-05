package com.team.rag.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Pinecone hybrid 检索使用的稀疏向量。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SparseVector {

    private List<Integer> indices = new ArrayList<>();
    private List<Double> values = new ArrayList<>();
}
