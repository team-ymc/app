---
type: Paper Section
title: 3.2.1 Scaled Dot-Product Attention
---

#### 3.2.1 Scaled Dot-Product Attention

We call our particular attention "Scaled Dot-Product Attention" (Figure 2). The input consists of queries and keys of dimension  $ d_k $, and values of dimension  $ d_v $. We compute the dot products of the query with all keys, divide each by  $ \sqrt{d_k} $, and apply a softmax function to obtain the weights on the values.

In practice, we compute the attention function on a set of queries simultaneously, packed together into a matrix Q. The keys and values are also packed together into matrices K and V. We compute the matrix of outputs as:

<display_formula data-asset-key="formula_0">
$$
Attention(Q,K,V)=softmax(\frac{QK^{T}}{\sqrt{d_{k}}})V
$$
</display_formula>

The two most commonly used attention functions are additive attention [2], and dot-product (multiplicative) attention. Dot-product attention is identical to our algorithm, except for the scaling factor of  $ \frac{1}{\sqrt{d_x}} $. Additive attention computes the compatibility function using a feed-forward network with a single hidden layer. While the two are similar in theoretical complexity, dot-product attention is much faster and more space-efficient in practice, since it can be implemented using highly optimized matrix multiplication code.

While for small values of  $ d_k $ the two mechanisms perform similarly, additive attention outperforms dot product attention without scaling for larger values of  $ d_k $ [3]. We suspect that for large values of  $ d_k $, the dot products grow large in magnitude, pushing the softmax function into regions where it has extremely small gradients $ ^4 $. To counteract this effect, we scale the dot products by  $ \frac{1}{\sqrt{d_k}} $.

<!-- followed-by
- [3.2.2 Multi-Head Attention](03-02-02-multi-head-attention.md)
-->
