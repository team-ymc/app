---
type: Paper Section
title: 3.4 Embeddings and Softmax
---

### 3.4 Embeddings and Softmax

Similarly to other sequence transduction models, we use learned embeddings to convert the input tokens and output tokens to vectors of dimension  $ d_{model} $. We also use the usual learned linear transformation and softmax function to convert the decoder output to predicted next-token probabilities. In our model, we share the same weight matrix between the two embedding layers and the pre-softmax linear transformation, similar to [30]. In the embedding layers, we multiply those weights by  $ \sqrt{d_{model}} $.

<figure_title>Table 1: Maximum path lengths, per-layer complexity and minimum number of sequential operations for different layer types. n is the sequence length, d is the representation dimension, k is the kernel size of convolutions and r the size of the neighborhood in restricted self-attention.</figure_title>

<table data-asset-key="table_0"><tr><td>Layer Type</td><td>Complexity per Layer</td><td>Sequential Operations</td><td>Maximum Path Length</td></tr><tr><td>Self-Attention</td><td>$ O(n^{2} \cdot d) $</td><td>$ O(1) $</td><td>$ O(1) $</td></tr><tr><td>Recurrent</td><td>$ O(n \cdot d^{2}) $</td><td>$ O(n) $</td><td>$ O(n) $</td></tr><tr><td>Convolutional</td><td>$ O(k \cdot n \cdot d^{2}) $</td><td>$ O(1) $</td><td>$ O(\log_{k}(n)) $</td></tr><tr><td>Self-Attention (restricted)</td><td>$ O(r \cdot n \cdot d) $</td><td>$ O(1) $</td><td>$ O(n/r) $</td></tr></table>

<!-- followed-by
- [3.5 Positional Encoding](03-05-positional-encoding.md)
-->
