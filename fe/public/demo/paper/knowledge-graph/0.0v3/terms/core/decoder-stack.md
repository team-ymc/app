---
type: Core Term
title: Decoder Stack
---

## Decoder Stack

The decoder stack generates the output sequence $(y_1,\ldots,y_m)$ one symbol at a time from the encoder representations and previously generated output symbols. The paper’s base model uses $N=6$ identical decoder layers. Each layer has three sub-layers, one more than an encoder layer, because the decoder must model its existing output context and also read from the encoder.

At the bottom of the stack, output tokens shifted one position to the right are converted into learned $d_{model}$-dimensional embeddings. The embedding weights are multiplied by $\sqrt{d_{model}}$, positional encodings are added, and dropout is applied to the sum during training. The shift is part of the auto-regressive constraint: the representation aligned with a prediction contains only earlier output symbols.

The first sub-layer in each decoder layer is masked multi-head self-attention. Its queries, keys, and values come from the decoder representation, but scores for subsequent positions are blocked. The second sub-layer is multi-head encoder-decoder attention. The paper describes its queries as coming from the previous decoder layer, while its keys and values come from the final encoder output, allowing every decoder position to attend to all input positions. The third sub-layer is the position-wise feed-forward network, applied independently at every decoder position.

Residual connections surround all three sub-layers and are followed by layer normalization. With $d_{model}=512$ throughout the base model, each sub-layer returns a representation that can be added to its input. The masking, encoder connection, feed-forward transformation, residual addition, and normalization are repeated in every one of the six layers.

After the final decoder layer, a learned linear transformation followed by softmax converts the decoder output to predicted next-token probabilities. The paper shares one weight matrix between the input embedding, output embedding, and pre-softmax linear transformation. The decoder stack therefore joins three kinds of information before prediction: previously known outputs through masked self-attention, the encoded input through encoder-decoder attention, and the position-wise transformation applied at each layer.

<!-- grounded-in
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
- [3.3 Position-wise Feed-Forward Networks](../../source/03-model-architecture/03-03-position-wise-feed-forward-networks.md)
- [3.4 Embeddings and Softmax](../../source/03-model-architecture/03-04-embeddings-and-softmax.md)
- [3.5 Positional Encoding](../../source/03-model-architecture/03-05-positional-encoding.md)
- [5.4 Regularization](../../source/05-training/05-04-regularization.md)
-->
