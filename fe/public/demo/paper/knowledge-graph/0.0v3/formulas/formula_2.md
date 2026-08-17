---
type: Formula
title: Single Attention Head
---

## Single Attention Head

$$
where head_{i}=Attention(QW_{i}^{Q},KW_{i}^{K},VW_{i}^{V})
$$

## Description

This equation defines one head inside multi-head attention. Before attention is computed, the shared inputs $Q$, $K$, and $V$ are transformed by that head’s learned projection matrices $W_i^Q$, $W_i^K$, and $W_i^V$. Scaled dot-product attention is then applied to the three projected matrices. Because every head has its own projection parameters, the heads do not receive identical versions of the inputs.

The paper gives $W_i^Q$ and $W_i^K$ shapes of $d_{model}\times d_k$, $W_i^V$ a shape of $d_{model}\times d_v$, and uses $d_k=d_v=64$ for each of eight heads in the base model. Each head therefore produces a $d_v$-dimensional output. Those outputs are later concatenated and projected by $W^O$. The authors connect these separate learned projections to the benefit of attending to information from different representation subspaces and different positions.

<!-- cited-in
- [3.2.2 Multi-Head Attention](../source/03-model-architecture/03-02-attention/03-02-02-multi-head-attention.md)
-->
