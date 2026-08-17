---
type: Prerequisite Term
title: Convolutional Neural Network
---

## Convolutional Neural Network

A convolutional neural network applies learned operations across local neighborhoods of a sequence or other structured input. A kernel of width $k$ combines information from nearby positions. Because the same operation can be applied at many positions simultaneously, convolutional sequence models can compute hidden representations in parallel across positions.

Locality creates a different limitation: one layer with $k<n$ does not directly connect every pair of sequence positions. Information from distant positions must pass through stacked convolutional layers. The paper distinguishes contiguous kernels, which require $O(n/k)$ layers to connect arbitrary positions, from dilated convolutions, which can reduce the longest path to $O(\log_k(n))$.

The paper discusses Extended Neural GPU, ByteNet, and ConvS2S as convolutional approaches motivated partly by reducing sequential computation. Its comparison table assigns a standard convolutional layer complexity of $O(knd^2)$, $O(1)$ sequential operations, and a maximum path of $O(\log_k(n))$ for the listed case. It states that convolutional layers are generally more expensive than recurrent layers by a factor of $k$.

Separable convolutions reduce the complexity to $O(knd+nd^2)$. Even when $k=n$, the paper says this complexity equals the combination of a self-attention layer and a position-wise feed-forward layer used by the Transformer. The Transformer therefore removes convolution as well as recurrence, using full self-attention to connect all positions in one layer with a constant maximum path length.

<!-- grounded-in
- [Abstract](../../source/abstract.md)
- [2 Background](../../source/02-background.md)
- [3.3 Position-wise Feed-Forward Networks](../../source/03-model-architecture/03-03-position-wise-feed-forward-networks.md)
- [3.4 Embeddings and Softmax](../../source/03-model-architecture/03-04-embeddings-and-softmax.md)
- [3.5 Positional Encoding](../../source/03-model-architecture/03-05-positional-encoding.md)
- [4 Why Self-Attention](../../source/04-why-self-attention.md)
- [5.4 Regularization](../../source/05-training/05-04-regularization.md)
- [7 Conclusion](../../source/07-conclusion.md)
-->
