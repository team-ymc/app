---
type: Core Term
title: Transformer
---

## Transformer

The Transformer is the paper’s sequence transduction architecture based entirely on attention. It removes the sequence-aligned recurrent and convolutional layers used by earlier encoder-decoder systems while retaining the overall encoder-decoder organization. For an input sequence $(x_1,\ldots,x_n)$, the encoder produces continuous representations $\mathbf{z}=(z_1,\ldots,z_n)$. The decoder then generates an output sequence $(y_1,\ldots,y_m)$ one symbol at a time, consuming previously generated symbols when predicting the next.

In the base model, the encoder and decoder each contain $N=6$ layers and use $d_{model}=512$. Every encoder layer has multi-head self-attention followed by a position-wise feed-forward network. Every decoder layer has masked multi-head self-attention, multi-head encoder-decoder attention over the encoder output, and a position-wise feed-forward network. Residual connections surround every sub-layer and are followed by layer normalization. Masking and output embeddings shifted by one position prevent decoder predictions from using later output symbols.

Learned input and output embeddings are combined with positional encodings because the architecture contains neither recurrence nor convolution to convey sequence order. The final decoder representation passes through a learned linear transformation and softmax to produce next-token probabilities. The same weight matrix is shared by the input embedding, output embedding, and pre-softmax transformation; embedding weights are multiplied by $\sqrt{d_{model}}$.

The central design objective is to replace recurrently ordered computation with attention over positions. Self-attention connects all positions with $O(1)$ sequential operations and an $O(1)$ maximum path length, enabling substantially more parallelization than recurrence. Its per-layer complexity is $O(n^2d)$, and the paper states that it is faster than recurrence when sequence length $n$ is smaller than representation dimension $d$, as is commonly the case for the sentence representations considered. Multi-head attention counteracts the loss of effective resolution that can result from averaging attention-weighted positions.

The reported results connect this architecture to both efficiency and quality. The base translation models trained for 100,000 steps, about 12 hours on eight P100 GPUs, while the big models trained for 300,000 steps, about 3.5 days. Transformer (big) reaches 28.4 BLEU on WMT 2014 English-to-German, more than 2 BLEU above the previously reported results including ensembles, and the paper reports 41.8 BLEU for English-to-French in the abstract and comparison table. A four-layer Transformer also reaches 91.3 F1 with WSJ-only training and 92.7 F1 with semi-supervised training on English constituency parsing, supporting the authors’ claim that the architecture generalizes beyond translation.

<!-- grounded-in
- [Abstract](../../source/abstract.md)
- [1 Introduction](../../source/01-introduction.md)
- [2 Background](../../source/02-background.md)
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
- [3.3 Position-wise Feed-Forward Networks](../../source/03-model-architecture/03-03-position-wise-feed-forward-networks.md)
- [3.4 Embeddings and Softmax](../../source/03-model-architecture/03-04-embeddings-and-softmax.md)
- [3.5 Positional Encoding](../../source/03-model-architecture/03-05-positional-encoding.md)
- [4 Why Self-Attention](../../source/04-why-self-attention.md)
- [5.2 Hardware and Schedule](../../source/05-training/05-02-hardware-and-schedule.md)
- [5.4 Regularization](../../source/05-training/05-04-regularization.md)
- [6.1 Machine Translation](../../source/06-results/06-01-machine-translation.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
- [6.3 English Constituency Parsing](../../source/06-results/06-03-english-constituency-parsing.md)
- [7 Conclusion](../../source/07-conclusion.md)
-->
