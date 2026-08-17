---
type: Core Term
title: Positional Encoding
---

## Positional Encoding

Positional encoding supplies sequence-order information to the Transformer. Because the model contains neither recurrence nor convolution, position is not introduced through a recurrent step or a convolutional neighborhood. Instead, positional encodings are added to token embeddings at the bottoms of both the encoder and decoder stacks, giving the model information about relative or absolute token position.

Each positional encoding has dimension $d_{model}$, exactly matching the learned token embedding, so the two vectors can be summed. In the embedding layers, the shared embedding weights are multiplied by $\sqrt{d_{model}}$ before positional information is added. During training, dropout is applied to the embedding-plus-position sum in both stacks.

The reported Transformer uses a fixed sinusoidal construction. For position $pos$ and dimension index $i$, even dimensions use $PE_{(pos,2i)}=\sin(pos/10000^{2i/d_{model}})$ and odd dimensions use $PE_{(pos,2i+1)}=\cos(pos/10000^{2i/d_{model}})$. Each dimension is a sinusoid, and the wavelengths form a geometric progression from $2\pi$ to $10000\cdot2\pi$. The paired sine and cosine values therefore create a full $d_{model}$-dimensional positional vector for every sequence position.

The authors chose this function because they hypothesized that it would make relative positions easy to learn: for any fixed offset $k$, $PE_{pos+k}$ can be represented as a linear function of $PE_{pos}$. They also experimented with learned positional embeddings. In the model-variation table, learned embeddings give development perplexity 4.92 and BLEU 25.7, nearly identical to the base sinusoidal model’s 4.92 and 25.8. The authors retained the sinusoidal version because it may allow extrapolation to sequence lengths longer than those encountered during training.

<!-- grounded-in
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.4 Embeddings and Softmax](../../source/03-model-architecture/03-04-embeddings-and-softmax.md)
- [3.5 Positional Encoding](../../source/03-model-architecture/03-05-positional-encoding.md)
- [5.4 Regularization](../../source/05-training/05-04-regularization.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
-->
