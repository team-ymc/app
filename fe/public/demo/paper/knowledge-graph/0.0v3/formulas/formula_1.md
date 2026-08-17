---
type: Formula
title: Multi-Head Attention Output
---

## Multi-Head Attention Output

$$
MultiHead(Q,K,V)=Concat(head_{1},...,head_{h})W^{O}
$$

## Description

This equation describes how the Transformer combines its parallel attention heads. Each $head_i$ is the result of running attention on a different learned projection of the same query, key, and value inputs. $Concat(head_1,\ldots,head_h)$ joins the $h$ head outputs, and the learned matrix $W^O$ projects that concatenated result back into the model’s output representation.

The paper uses $h=8$ heads in its base configuration. Each head has key and value dimensions $d_k=d_v=64$, equal to $d_{model}/h$. Reducing each head’s dimension keeps the total computational cost similar to full-dimensional single-head attention. The reason for composing several heads is not merely to repeat the same operation: the authors state that multi-head attention allows the model to attend jointly to information from different representation subspaces at different positions, whereas averaging within one head inhibits this.

<!-- cited-in
- [3.2.2 Multi-Head Attention](../source/03-model-architecture/03-02-attention/03-02-02-multi-head-attention.md)
-->
