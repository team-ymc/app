---
type: Prerequisite Term
title: Layer Normalization
---

## Layer Normalization

Layer normalization normalizes the features of a representation within an individual example. For one position’s feature vector, it uses that vector’s mean and variance to rescale the activations, followed by learned scale and shift parameters. Unlike a normalization based on statistics collected across a batch, the operation can be applied using the features available within that example.

In the Transformer, layer normalization is paired with residual connections. Every encoder and decoder sub-layer is surrounded by a residual path, and normalization is applied after the transformed output has been added back to the input. The paper writes the result as $\mathrm{LayerNorm}(x+\mathrm{Sublayer}(x))$.

The same pattern is repeated around all major sub-layers. Each encoder layer normalizes after multi-head self-attention and again after its position-wise feed-forward network. Each decoder layer also normalizes after masked self-attention, after encoder-decoder attention, and after its feed-forward network. With six layers in each stack, normalization occurs throughout the architecture rather than only at its input or output.

In the architecture figure, this ordering appears as “Add & Norm.” The residual addition combines the original and transformed representations, and layer normalization processes that combined result before it becomes the input to the next sub-layer. The paper uses this post-addition arrangement consistently across the model it describes.

<!-- grounded-in
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
-->
