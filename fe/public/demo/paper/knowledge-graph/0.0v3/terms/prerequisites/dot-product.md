---
type: Prerequisite Term
title: Dot Product
---

## Dot Product

The dot product combines two vectors of the same length by multiplying corresponding components and summing the results: $q\cdot k=\sum_j q_jk_j$. It produces one scalar from two vectors. In attention, that scalar is used as a compatibility score between a query $q$ and a key $k$; different keys give different scores for the same query.

The Transformer computes many dot products at once with matrix multiplication. If queries are rows of $Q$ and keys are rows of $K$, then $QK^T$ contains the dot product of every query with every key. Transposing $K$ aligns each query row with each key row. Softmax is later applied across these scores, and the resulting weights combine the value matrix $V$.

The paper’s “scaled dot-product” operation divides every score by $\sqrt{d_k}$, where $d_k$ is the query and key dimension. The authors suspect that unscaled dot products grow large in magnitude when $d_k$ is large, pushing softmax into regions with extremely small gradients. Scaling counteracts that effect. The complete operation is $Attention(Q,K,V)=softmax(QK^T/\sqrt{d_k})V$.

The paper contrasts dot-product attention with additive attention, which computes compatibility through a feed-forward network. It states that the two have similar theoretical complexity, but dot products are faster and more space-efficient in practice because they can use optimized matrix multiplication. In the model variations, reducing $d_k$ from 64 to 32 or 16 lowers development BLEU, which the authors interpret as evidence that determining compatibility is not easy.

<!-- grounded-in
- [3.2 Attention](../../source/03-model-architecture/03-02-attention/overview.md)
- [3.2.1 Scaled Dot-Product Attention](../../source/03-model-architecture/03-02-attention/03-02-01-scaled-dot-product-attention.md)
- [3.2.2 Multi-Head Attention](../../source/03-model-architecture/03-02-attention/03-02-02-multi-head-attention.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
-->
