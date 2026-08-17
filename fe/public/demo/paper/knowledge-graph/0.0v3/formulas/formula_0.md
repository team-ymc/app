---
type: Formula
title: Scaled Dot-Product Attention
---

## Scaled Dot-Product Attention

$$
Attention(Q,K,V)=softmax(\frac{QK^{T}}{\sqrt{d_{k}}})V
$$

## Description

This equation defines the paper’s scaled dot-product attention. $Q$ contains a set of queries, $K$ contains keys, and $V$ contains values. The product $QK^T$ computes every query-key dot product at once. Dividing by $\sqrt{d_k}$ scales those compatibility scores according to the key dimension $d_k$. Softmax converts each query’s scaled scores into weights, and the final multiplication by $V$ produces a weighted combination of the values.

The authors introduce the $1/\sqrt{d_k}$ factor because they suspect that large key dimensions produce dot products large enough to push softmax into regions with extremely small gradients. The equation shows the unmasked core computation. When it is used for decoder self-attention, the model masks illegal connections before softmax by assigning them $-\infty$, preventing a position from attending to subsequent output positions. In the paper’s base model, attention is applied to matrices of queries, keys, and values simultaneously rather than processing one query at a time.

<!-- cited-in
- [3.2.1 Scaled Dot-Product Attention](../source/03-model-architecture/03-02-attention/03-02-01-scaled-dot-product-attention.md)
-->
