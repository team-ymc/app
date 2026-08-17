---
type: Core Term
title: Encoder-Decoder Attention
---

## Encoder-Decoder Attention

Encoder-decoder attention is the middle sub-layer in every Transformer decoder layer. It connects the two stacks by combining queries from the decoder with keys and values from the encoder. The paper calls the encoder keys and values “memory,” because they are taken from the complete output of the encoder stack and made available throughout decoding.

More precisely, the queries come from the preceding decoder layer, while the memory keys and values come from the encoder output. This lets every decoder position attend over every position in the input sequence. It differs from encoder self-attention, where queries, keys, and values all come from the encoder, and from masked decoder self-attention, where all three come from the decoder and future positions are blocked.

The Transformer implements this connection with multi-head attention. Each head separately projects the decoder queries and encoder keys and values, executes scaled dot-product attention, and produces a head output. The outputs are concatenated and projected back to $d_{model}$. In the base configuration, the same eight-head, $d_k=d_v=64$ construction is used for multi-head attention sub-layers.

Within each decoder layer, encoder-decoder attention follows masked decoder self-attention and precedes the position-wise feed-forward network. Its sub-layer is surrounded by a residual connection and followed by layer normalization. The operation retains the encoder-decoder attention role used in earlier sequence-to-sequence systems, but the Transformer places it inside an architecture without recurrent layers.

<!-- grounded-in
- [Abstract](../../source/abstract.md)
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
-->
