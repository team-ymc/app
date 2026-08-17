---
type: Formula
title: Sine Positional Encoding
---

## Sine Positional Encoding

$$
P E_{(p o s,2i)}=s i n(p o s/10000^{2i/d_{\mathrm{m o d e l}}})
$$

## Description

This equation assigns the sine component of the Transformer’s fixed positional encoding. $pos$ is a token’s position in the sequence, $i$ indexes dimensions, and $PE_{(pos,2i)}$ is the value placed in even dimension $2i$. Dividing $pos$ by $10000^{2i/d_{model}}$ gives different dimensions sine waves with different frequencies.

This sine equation is paired with a cosine equation for odd dimensions. Together, they create a $d_{model}$-dimensional positional vector that can be added directly to each input or output embedding because both have the same dimension. The paper states that the sinusoidal wavelengths form a geometric progression from $2\pi$ to $10000\cdot2\pi$. The authors chose this form because they hypothesized that, for any fixed offset $k$, $PE_{pos+k}$ could be represented as a linear function of $PE_{pos}$, making relative-position attention easier to learn. Learned positional embeddings produced nearly identical results, but the sinusoidal form might extrapolate to longer sequences.

<!-- cited-in
- [3.5 Positional Encoding](../source/03-model-architecture/03-05-positional-encoding.md)
-->
