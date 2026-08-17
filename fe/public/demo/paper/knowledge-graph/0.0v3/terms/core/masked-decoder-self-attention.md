---
type: Core Term
title: Masked Decoder Self-Attention
---

## Masked Decoder Self-Attention

Masked decoder self-attention is the first sub-layer in every Transformer decoder layer. Like other self-attention, its queries, keys, and values all come from the same sequence representation. Unlike encoder self-attention, it does not permit every position to attend everywhere: a decoder position may attend only to positions up to and including itself.

The restriction preserves auto-regressive generation. The decoder produces one output symbol at a time, so the prediction for position $i$ must not depend on symbols at later positions. The paper combines two safeguards. Output embeddings are shifted one position to the right, and the self-attention computation blocks connections to subsequent positions. Together, these ensure that the prediction at position $i$ depends only on known outputs at positions less than $i$.

The mask is applied inside scaled dot-product attention before softmax. Values in the softmax input that correspond to illegal future connections are set to $-\infty$. Those positions therefore cannot contribute to the weighted combination of values. The remaining allowed positions include the current position and all earlier decoder positions.

After masked self-attention, the decoder layer performs encoder-decoder attention and then the position-wise feed-forward network. The first sub-layer models the permitted output-side context; the second uses queries from the decoder with keys and values from the encoder, bringing in the input sequence. Residual connections and layer normalization surround masked self-attention just as they surround the other decoder sub-layers.

<!-- grounded-in
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
-->
