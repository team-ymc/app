---
type: Core Term
title: Encoder Stack
---

## Encoder Stack

The encoder stack maps an input sequence of symbol representations $(x_1,\ldots,x_n)$ to continuous representations $\mathbf{z}=(z_1,\ldots,z_n)$. In the paper’s base Transformer, it consists of $N=6$ identical layers. The stack is the left half of the architecture diagram and produces the representation that every decoder layer can attend over.

Before the first encoder layer, input tokens are converted into learned embeddings of dimension $d_{model}$. The embedding weights are multiplied by $\sqrt{d_{model}}$, and positional encodings of the same dimension are added so the model can use sequence order despite having neither recurrence nor convolution. Dropout is applied to the sum during training. The base configuration uses $d_{model}=512$.

Every encoder layer contains two sub-layers in a fixed order. Multi-head self-attention comes first: its queries, keys, and values all come from the output of the preceding encoder layer, allowing every position to attend to every encoder position. A position-wise feed-forward network comes second and applies the same two linear transformations with a ReLU independently at each position. Its input and output dimension is 512 and its inner dimension is $d_{ff}=2048$ in the base model.

Each sub-layer is surrounded by a residual connection and followed by layer normalization, giving $LayerNorm(x+Sublayer(x))$. All sub-layers and embeddings produce the same $d_{model}$-dimensional representation so these residual additions are possible. The output of the sixth encoder layer becomes the memory used in encoder-decoder attention: each decoder layer takes its keys and values from this encoder output while supplying its own queries.

The appendix attention figures inspect heads from encoder self-attention in layer 5 of 6. They show attention from “making” across a distant dependency, sharp patterns from “its,” and different head patterns over the same sentence. These figures are examples of behavior inside the encoder stack rather than additional layers or separate processing stages.

<!-- grounded-in
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
- [3.3 Position-wise Feed-Forward Networks](../../source/03-model-architecture/03-03-position-wise-feed-forward-networks.md)
- [3.4 Embeddings and Softmax](../../source/03-model-architecture/03-04-embeddings-and-softmax.md)
- [3.5 Positional Encoding](../../source/03-model-architecture/03-05-positional-encoding.md)
- [5.4 Regularization](../../source/05-training/05-04-regularization.md)
- [Attention Visualizations](../../source/attention-visualizations.md)
-->
