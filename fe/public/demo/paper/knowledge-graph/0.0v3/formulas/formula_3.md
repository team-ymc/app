---
type: Formula
title: Position-Wise Feed-Forward Network
---

## Position-Wise Feed-Forward Network

$$
F F N(x)=\max(0,x W_{1}+b_{1})W_{2}+b_{2}
$$

## Description

This equation defines the feed-forward sub-layer used in every encoder and decoder layer. The first affine transformation, $xW_1+b_1$, maps the input into an inner representation. $\max(0,\cdot)$ applies the ReLU activation, and the second affine transformation, using $W_2$ and $b_2$, maps the result back to the model dimension.

The same two-transform operation is applied separately and identically to every position in a sequence: positions do not interact with one another inside this sub-layer. Its parameters are shared across positions within a layer, but different Transformer layers use different feed-forward parameters. The paper also describes the operation as two convolutions with kernel size 1. In the base model, the input and output dimension is $d_{model}=512$, while the inner dimension is $d_{ff}=2048$. This feed-forward network follows the attention sub-layer in each encoder layer and appears after the attention sub-layers in each decoder layer.

<!-- cited-in
- [3.3 Position-wise Feed-Forward Networks](../source/03-model-architecture/03-03-position-wise-feed-forward-networks.md)
-->
