---
type: Prerequisite Term
title: Encoder-Decoder Architecture
---

## Encoder-Decoder Architecture

An encoder-decoder architecture divides a transduction model into two coordinated parts. The encoder reads the input sequence and converts it into an internal representation. The decoder uses that representation, together with the output symbols already available to it, to construct the output sequence. This separation allows input processing and output generation to use different internal operations while remaining connected through the encoder representation.

The paper writes the encoder mapping as $(x_1,\ldots,x_n)\rightarrow\mathbf{z}=(z_1,\ldots,z_n)$. Given $\mathbf{z}$, the decoder produces $(y_1,\ldots,y_m)$ one symbol at a time. Earlier competitive sequence transduction systems commonly used this overall structure with recurrent or convolutional layers and an attention mechanism connecting the two sides.

The Transformer preserves the division of responsibility but replaces the sequence layers. Its encoder is a stack of self-attention and position-wise feed-forward sub-layers. Its decoder contains masked self-attention and a feed-forward sub-layer, plus encoder-decoder attention that uses decoder queries with keys and values from the encoder output. That attention connection gives every decoder position access to all encoded input positions.

For a student, “encoder-decoder” describes the large-scale information flow, not a specific neural layer. Self-attention, multi-head attention, residual connections, positional encodings, and feed-forward networks describe how the Transformer implements the two sides. Autoregressive generation describes how the decoder uses the output prefix over time.

<!-- grounded-in
- [Abstract](../../source/abstract.md)
- [1 Introduction](../../source/01-introduction.md)
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
- [7 Conclusion](../../source/07-conclusion.md)
-->
