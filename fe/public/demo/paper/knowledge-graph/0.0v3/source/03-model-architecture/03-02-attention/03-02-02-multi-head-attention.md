---
type: Paper Section
title: 3.2.2 Multi-Head Attention
---

#### 3.2.2 Multi-Head Attention

Instead of performing a single attention function with  $ d_{model} $-dimensional keys, values and queries, we found it beneficial to linearly project the queries, keys and values h times with different, learned linear projections to  $ d_{k} $,  $ d_{k} $ and  $ d_{v} $ dimensions, respectively. On each of these projected versions of queries, keys and values we then perform the attention function in parallel, yielding  $ d_{v} $-dimensional

output values. These are concatenated and once again projected, resulting in the final values, as depicted in Figure 2.

Multi-head attention allows the model to jointly attend to information from different representation subspaces at different positions. With a single attention head, averaging inhibits this.

<display_formula data-asset-key="formula_1">
$$
MultiHead(Q,K,V)=Concat(head_{1},...,head_{h})W^{O}
$$
</display_formula>

<display_formula data-asset-key="formula_2">
$$
where head_{i}=Attention(QW_{i}^{Q},KW_{i}^{K},VW_{i}^{V})
$$
</display_formula>

Where the projections are parameter matrices  $ W_i^Q \in \mathbb{R}^{d_{\text{model}} \times d_k} $,  $ W_i^K \in \mathbb{R}^{d_{\text{model}} \times d_k} $,  $ W_i^V \in \mathbb{R}^{d_{\text{model}} \times d_v} $ and  $ W^O \in \mathbb{R}^{hd_v \times d_{\text{model}}} $.

In this work we employ $h = 8$ parallel attention layers, or heads. For each of these we use $d_{k} = d_{v} = d_{\mathrm{model}}/h = 64$. Due to the reduced dimension of each head, the total computational cost is similar to that of single-head attention with full dimensionality.

<!-- followed-by
- [3.2.3 Applications of Attention in our Model](03-02-03-applications.md)
-->
