---
type: Prerequisite Term
title: Softmax
---

## Softmax

Softmax converts a vector of real-valued scores into non-negative values that sum to one. For scores $s_1,\ldots,s_m$, each output is $\exp(s_j)/\sum_l\exp(s_l)$. Larger input scores receive larger shares of the total, so the outputs can be interpreted as a distribution of relative weights or probabilities rather than unbounded raw scores.

The Transformer uses softmax inside attention. The query-key matrix $QK^T$ contains compatibility scores. After division by $\sqrt{d_k}$, softmax turns each query’s scores into weights over the values, and multiplication by $V$ produces their weighted combination. The scale matters because the authors suspect that large dot products can push softmax into regions with extremely small gradients.

Decoder masking also operates at the softmax input. Scores for prohibited future connections are set to $-\infty$ before softmax. Their resulting weight is zero, so a decoder position cannot use later output positions. The remaining permitted scores are normalized together.

Softmax appears a second time at the output of the decoder. A learned linear transformation produces a score for every possible next token, and softmax converts those scores into predicted next-token probabilities. The model shares the pre-softmax weight matrix with the input and output embedding matrices. Attention softmax distributes weight across sequence positions; output softmax distributes probability across vocabulary items.

<!-- grounded-in
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.2.1 Scaled Dot-Product Attention](../../source/03-model-architecture/03-02-attention/03-02-01-scaled-dot-product-attention.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
- [3.4 Embeddings and Softmax](../../source/03-model-architecture/03-04-embeddings-and-softmax.md)
-->
