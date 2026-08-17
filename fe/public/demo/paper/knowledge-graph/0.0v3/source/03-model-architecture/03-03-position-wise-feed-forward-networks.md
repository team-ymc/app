---
type: Paper Section
title: 3.3 Position-wise Feed-Forward Networks
---

### 3.3 Position-wise Feed-Forward Networks

In addition to attention sub-layers, each of the layers in our encoder and decoder contains a fully connected feed-forward network, which is applied to each position separately and identically. This consists of two linear transformations with a ReLU activation in between.

<display_formula data-asset-key="formula_3">
$$
F F N(x)=\max(0,x W_{1}+b_{1})W_{2}+b_{2}
$$
</display_formula>

While the linear transformations are the same across different positions, they use different parameters from layer to layer. Another way of describing this is as two convolutions with kernel size 1. The dimensionality of input and output is  $ d_{model} = 512 $, and the inner-layer has dimensionality  $ d_{ff} = 2048 $.

<!-- followed-by
- [3.4 Embeddings and Softmax](03-04-embeddings-and-softmax.md)
-->
