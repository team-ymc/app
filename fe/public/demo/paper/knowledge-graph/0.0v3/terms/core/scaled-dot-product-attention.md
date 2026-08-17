---
type: Core Term
title: Scaled Dot-Product Attention
---

## Scaled Dot-Product Attention

Scaled dot-product attention is the specific attention function executed inside the Transformer’s attention heads. Its inputs are queries and keys of dimension $d_k$ and values of dimension $d_v$. For one query, the operation computes its dot product with every key, divides those compatibility scores by $\sqrt{d_k}$, applies softmax to obtain weights, and returns the weighted combination of the values.

The paper evaluates many queries at once by packing them into a matrix $Q$, with keys in $K$ and values in $V$. The complete operation is $Attention(Q,K,V)=softmax(QK^T/\sqrt{d_k})V$. Reading the expression from left to right, $QK^T$ creates the query-key score matrix, $1/\sqrt{d_k}$ scales it, softmax converts it into weights, and multiplication by $V$ produces the attention outputs.

The scaling factor distinguishes the operation from ordinary dot-product attention. The authors suspect that, for large $d_k$, unscaled dot products grow in magnitude and push softmax into regions with extremely small gradients. Dividing by $\sqrt{d_k}$ counters this effect. They report that additive and unscaled dot-product attention perform similarly for small $d_k$, while additive attention performs better at larger $d_k$ when the dot products are not scaled. Although additive and dot-product attention have similar theoretical complexity, the paper states that dot-product attention is faster and more space-efficient in practice because it can use optimized matrix multiplication.

Scaled dot-product attention also provides the point at which decoder masking is applied. Before softmax, scores for prohibited future connections are set to $-\infty$. This prevents a decoder position from attending to later output positions and preserves the auto-regressive property. The unmasked core operation is otherwise the same across encoder self-attention, decoder self-attention, and encoder-decoder attention.

The model-variation results add evidence about the compatibility computation. Reducing attention key size $d_k$ from the base value of 64 to 32 or 16 lowers development BLEU from 25.8 to 25.4 or 25.1. The authors interpret this as suggesting that determining compatibility is not easy and that a more sophisticated compatibility function than dot product might be beneficial.

<!-- grounded-in
- [3.2 Attention](../../source/03-model-architecture/03-02-attention/overview.md)
- [3.2.1 Scaled Dot-Product Attention](../../source/03-model-architecture/03-02-attention/03-02-01-scaled-dot-product-attention.md)
- [3.2.2 Multi-Head Attention](../../source/03-model-architecture/03-02-attention/03-02-02-multi-head-attention.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
-->
