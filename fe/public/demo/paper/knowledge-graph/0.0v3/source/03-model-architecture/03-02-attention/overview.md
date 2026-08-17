---
type: Paper Section
title: 3.2 Attention
---

### 3.2 Attention

An attention function can be described as mapping a query and a set of key-value pairs to an output, where the query, keys, values, and output are all vectors. The output is computed as a weighted sum

<img data-asset-key="image_1" />

<figure_title>Figure 2: (left) Scaled Dot-Product Attention. (right) Multi-Head Attention consists of several attention layers running in parallel.</figure_title>

of the values, where the weight assigned to each value is computed by a compatibility function of the query with the corresponding key.

<!-- followed-by
- [3.2.1 Scaled Dot-Product Attention](03-02-01-scaled-dot-product-attention.md)
-->

<!-- subsections
- [3.2.1 Scaled Dot-Product Attention](03-02-01-scaled-dot-product-attention.md)
- [3.2.2 Multi-Head Attention](03-02-02-multi-head-attention.md)
- [3.2.3 Applications of Attention in our Model](03-02-03-applications.md)
-->
