---
type: Formula
title: Cosine Positional Encoding
---

## Cosine Positional Encoding

$$
P E_{(p o s,2i+1)}=c o s(p o s/10000^{2i/d_{m o d e l}})
$$

## Description

This equation assigns the cosine component of the Transformer’s fixed positional encoding. $pos$ is the position of a token, $i$ indexes dimensions, and $PE_{(pos,2i+1)}$ is the value placed in odd dimension $2i+1$. Its denominator $10000^{2i/d_{model}}$ matches the corresponding even-dimension sine component, so each sine-cosine pair uses the same frequency.

The paired sine and cosine equations create a positional vector with dimension $d_{model}$. That vector is added to token embeddings at the bottoms of both the encoder and decoder stacks, supplying sequence-order information to a model that contains neither recurrence nor convolution. Across dimensions, the paper states that the sinusoidal wavelengths progress geometrically from $2\pi$ to $10000\cdot2\pi$. The authors hypothesized that this construction would help the model learn relative-position relationships, and selected it over learned positional embeddings because it might generalize to sequence lengths beyond those encountered during training.

<!-- cited-in
- [3.5 Positional Encoding](../source/03-model-architecture/03-05-positional-encoding.md)
-->
