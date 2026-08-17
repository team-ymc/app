---
type: Prerequisite Term
title: Residual Connection
---

## Residual Connection

A residual connection adds a transformation’s input directly to its output. Instead of returning only $F(x)$, a residual sub-layer returns a representation based on $x+F(x)$. The unchanged input therefore has a direct route around the transformation while the sub-layer contributes an update to it. Addition requires the two representations to have the same shape.

The Transformer places a residual connection around every encoder and decoder sub-layer. If $\mathrm{Sublayer}(x)$ denotes self-attention, encoder-decoder attention, or a position-wise feed-forward network, the paper writes the complete post-normalized result as $\mathrm{LayerNorm}(x+\mathrm{Sublayer}(x))$.

Consistent dimensionality makes this pattern possible. Every embedding and sub-layer output has dimension $d_{model}=512$ in the base configuration. Multi-head attention concatenates its heads and projects the result back to $d_{model}$; the feed-forward network expands to $d_{ff}=2048$ internally but returns to $d_{model}$. Each result can therefore be added to the sub-layer input.

Regularization changes the order slightly without removing the connection. The paper applies dropout to each sub-layer output before it is added to the input and normalized. In the architecture diagram, the looping arrows around attention and feed-forward blocks depict these residual paths, while the “Add & Norm” blocks depict the addition followed by layer normalization.

<!-- grounded-in
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [5.4 Regularization](../../source/05-training/05-04-regularization.md)
-->
