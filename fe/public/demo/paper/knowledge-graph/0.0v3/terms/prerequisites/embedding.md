---
type: Prerequisite Term
title: Token Embedding
---

## Token Embedding

A token embedding converts a discrete vocabulary item into a continuous vector. A token begins as an identity—an index selecting one item from the vocabulary—while the embedding is a learned list of numerical features that neural-network layers can transform. Tokens using the same embedding table are represented in a common vector space with a fixed dimensionality.

The Transformer has learned embeddings for both input and output tokens. Each embedding has dimension $d_{model}$, which is 512 in the base model. This matches the dimension used by every sub-layer, allowing representations to move through attention, feed-forward, and residual operations without changing the outer width of the model.

The paper shares one weight matrix across the input embedding, output embedding, and learned linear transformation before the final softmax. In the embedding layers, those weights are multiplied by $\sqrt{d_{model}}$. The final decoder transformation uses the shared weights to produce scores over possible next tokens before softmax converts them to probabilities.

An embedding alone identifies token content but does not supply sequence order to this non-recurrent, non-convolutional model. Positional encodings of the same $d_{model}$ dimension are added to the embeddings at the bottoms of the encoder and decoder stacks. During training, dropout is applied to these embedding-plus-position sums. The actual vocabulary items come from the paper’s subword tokenization: about 37,000 shared English-German tokens and 32,000 English-French word pieces.

<!-- grounded-in
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [3.4 Embeddings and Softmax](../../source/03-model-architecture/03-04-embeddings-and-softmax.md)
- [3.5 Positional Encoding](../../source/03-model-architecture/03-05-positional-encoding.md)
- [5.1 Training Data and Batching](../../source/05-training/05-01-training-data-and-batching.md)
- [5.4 Regularization](../../source/05-training/05-04-regularization.md)
-->
