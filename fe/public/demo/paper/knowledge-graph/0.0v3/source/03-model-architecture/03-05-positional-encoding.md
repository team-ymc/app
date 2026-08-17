---
type: Paper Section
title: 3.5 Positional Encoding
---

### 3.5 Positional Encoding

Since our model contains no recurrence and no convolution, in order for the model to make use of the order of the sequence, we must inject some information about the relative or absolute position of the tokens in the sequence. To this end, we add "positional encodings" to the input embeddings at the bottoms of the encoder and decoder stacks. The positional encodings have the same dimension  $ d_{model} $ as the embeddings, so that the two can be summed. There are many choices of positional encodings, learned and fixed [9].

In this work, we use sine and cosine functions of different frequencies:

<display_formula data-asset-key="formula_4">
$$
P E_{(p o s,2i)}=s i n(p o s/10000^{2i/d_{\mathrm{m o d e l}}})
$$
</display_formula>

<display_formula data-asset-key="formula_5">
$$
P E_{(p o s,2i+1)}=c o s(p o s/10000^{2i/d_{m o d e l}})
$$
</display_formula>

where $pos$ is the position and $i$ is the dimension. That is, each dimension of the positional encoding corresponds to a sinusoid. The wavelengths form a geometric progression from $2\pi$ to $10000 \cdot 2\pi$. We chose this function because we hypothesized it would allow the model to easily learn to attend by relative positions, since for any fixed offset $k$, $PE_{pos+k}$ can be represented as a linear function of $PE_{pos}$.

We also experimented with using learned positional embeddings [9] instead, and found that the two versions produced nearly identical results (see Table 3 row (E)). We chose the sinusoidal version because it may allow the model to extrapolate to sequence lengths longer than the ones encountered during training.

<!-- followed-by
- [4 Why Self-Attention](../04-why-self-attention.md)
-->
