---
type: Table
title: Layer Complexity and Path Length
---

## Layer Complexity and Path Length

<table><tr><td>Layer Type</td><td>Complexity per Layer</td><td>Sequential Operations</td><td>Maximum Path Length</td></tr><tr><td>Self-Attention</td><td>$ O(n^{2} \cdot d) $</td><td>$ O(1) $</td><td>$ O(1) $</td></tr><tr><td>Recurrent</td><td>$ O(n \cdot d^{2}) $</td><td>$ O(n) $</td><td>$ O(n) $</td></tr><tr><td>Convolutional</td><td>$ O(k \cdot n \cdot d^{2}) $</td><td>$ O(1) $</td><td>$ O(\log_{k}(n)) $</td></tr><tr><td>Self-Attention (restricted)</td><td>$ O(r \cdot n \cdot d) $</td><td>$ O(1) $</td><td>$ O(n/r) $</td></tr></table>

## Description

This table compares four sequence-processing layer types using the three criteria the paper uses to motivate self-attention: computational complexity per layer, the minimum number of sequential operations, and the maximum path length between positions. Here, $n$ is sequence length, $d$ is representation dimension, $k$ is convolution kernel size, and $r$ is the neighborhood size for restricted self-attention.

Full self-attention has complexity $O(n^2d)$ but requires only $O(1)$ sequential operations and connects any two positions with an $O(1)$ path. A recurrent layer has complexity $O(nd^2)$ and requires both $O(n)$ sequential operations and an $O(n)$ maximum path. A convolutional layer permits $O(1)$ sequential operations but has complexity $O(knd^2)$ and a maximum path of $O(\log_k(n))$ in the listed case. Restricted self-attention reduces complexity to $O(rnd)$ while increasing the maximum path to $O(n/r)$.

The paper’s central comparison is that self-attention is more parallelizable than recurrence and gives signals shorter paths across a sequence. It is also faster than recurrence when $n<d$, which the authors state is usually true for the sentence representations used by the machine-translation models they discuss. Restricted attention is presented as a possible tradeoff for very long sequences rather than as the configuration evaluated in this paper.

<!-- cited-in
- [4 Why Self-Attention](../source/04-why-self-attention.md)
-->
