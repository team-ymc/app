---
type: Paper Section
title: 3 Model Architecture
---

## 3 Model Architecture

Most competitive neural sequence transduction models have an encoder-decoder structure [5, 2, 35]. Here, the encoder maps an input sequence of symbol representations  $ (x_1, ..., x_n) $ to a sequence of continuous representations  $ \mathbf{z} = (z_1, ..., z_n) $. Given  $ \mathbf{z} $, the decoder then generates an output sequence  $ (y_1, ..., y_m) $ of symbols one element at a time. At each step the model is auto-regressive [10], consuming the previously generated symbols as additional input when generating the next.

<img data-asset-key="image_0" />

<figure_title>Figure 1: The Transformer - model architecture.</figure_title>

The Transformer follows this overall architecture using stacked self-attention and point-wise, fully connected layers for both the encoder and decoder, shown in the left and right halves of Figure 1, respectively.

<!-- followed-by
- [3.1 Encoder and Decoder Stacks](03-01-encoder-decoder-stacks.md)
-->

<!-- subsections
- [3.1 Encoder and Decoder Stacks](03-01-encoder-decoder-stacks.md)
- [3.2 Attention](03-02-attention/overview.md)
- [3.3 Position-wise Feed-Forward Networks](03-03-position-wise-feed-forward-networks.md)
- [3.4 Embeddings and Softmax](03-04-embeddings-and-softmax.md)
- [3.5 Positional Encoding](03-05-positional-encoding.md)
-->
