---
type: Core Term
title: Position-Wise Feed-Forward Network
---

## Position-Wise Feed-Forward Network

The position-wise feed-forward network is the non-attention transformation present in every encoder and decoder layer. It is applied separately and identically to each sequence position. Within this sub-layer, information does not move between positions; interaction across positions has already occurred in the attention sub-layer.

The paper defines $FFN(x)=\max(0,xW_1+b_1)W_2+b_2$. The first linear transformation maps the position representation into an inner dimension, $\max(0,\cdot)$ applies ReLU, and the second linear transformation maps it back to the model dimension. The same $W_1$, $b_1$, $W_2$, and $b_2$ are used at every position within one layer, but different Transformer layers have different feed-forward parameters. The paper also describes the operation as two convolutions with kernel size 1.

In the base model, the input and output dimension is $d_{model}=512$ and the inner dimension is $d_{ff}=2048$. Returning to $d_{model}$ allows the feed-forward output to be added to its sub-layer input through the residual connection before layer normalization. In an encoder layer, the feed-forward network follows multi-head self-attention. In a decoder layer, it follows both masked self-attention and encoder-decoder attention, making it the third and final sub-layer.

The paper’s efficiency comparison treats self-attention and the feed-forward network as complementary parts of a Transformer layer. It notes that even a separable convolution with kernel width equal to sequence length has complexity equal to the combination of one self-attention layer and one point-wise feed-forward layer. The model-variation table also changes $d_{ff}$ while retaining the base values elsewhere: reducing it to 1024 gives 25.4 development BLEU, while increasing it to 4096 gives 26.2, compared with 25.8 for the base $d_{ff}=2048$ configuration. These rows contribute to the authors’ broader observation that larger models perform better.

<!-- grounded-in
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [3.3 Position-wise Feed-Forward Networks](../../source/03-model-architecture/03-03-position-wise-feed-forward-networks.md)
- [4 Why Self-Attention](../../source/04-why-self-attention.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
-->
