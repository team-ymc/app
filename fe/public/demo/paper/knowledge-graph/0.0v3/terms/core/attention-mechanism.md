---
type: Core Term
title: Attention Mechanism
---

## Attention Mechanism

An attention function maps a query and a set of key-value pairs to an output. The query, keys, values, and output are vectors. A compatibility function compares the query with each key to determine a weight for the corresponding value, and the output is the weighted sum of those values. This lets a model connect positions without requiring the number of operations between them to grow with their distance in the sequence.

Attention mechanisms were already used in sequence modeling before the Transformer, usually together with a recurrent network. The paper’s contribution is to make attention the foundation of the complete transduction architecture: the Transformer uses attention to compute representations on both the encoder and decoder sides without sequence-aligned recurrence or convolution. The authors describe it as drawing global dependencies between input and output while allowing substantially more parallelization.

The Transformer implements attention through scaled dot products. For matrices of queries $Q$, keys $K$, and values $V$, it computes $Attention(Q,K,V)=softmax(QK^T/\sqrt{d_k})V$. The scaling factor counteracts large dot products at larger key dimensions. Multi-head attention repeats the operation on different learned projections of $Q$, $K$, and $V$, concatenates the head outputs, and projects them again. The paper uses eight heads in the base model so attention can operate across different representation subspaces and positions instead of averaging everything within one full-dimensional head.

The architecture applies attention in three distinct ways. Encoder self-attention obtains queries, keys, and values from the previous encoder layer, allowing every encoder position to attend to every position. Masked decoder self-attention obtains all three from the decoder but blocks later output positions. Encoder-decoder attention obtains queries from the previous decoder layer and keys and values from the encoder output, allowing every decoder position to attend across the input sequence.

The paper also uses attention distributions as evidence about learned behavior. Appendix examples show heads attending from “making” to the distant phrase “more difficult,” sharp patterns from “its” that the authors describe as apparently involved in anaphora resolution, and two heads with different patterns that seem related to sentence structure. The authors present these examples cautiously as indications that individual heads learn different tasks, not as fixed rules assigned to every attention head.

<!-- grounded-in
- [Abstract](../../source/abstract.md)
- [1 Introduction](../../source/01-introduction.md)
- [2 Background](../../source/02-background.md)
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [3.2 Attention](../../source/03-model-architecture/03-02-attention/overview.md)
- [3.2.1 Scaled Dot-Product Attention](../../source/03-model-architecture/03-02-attention/03-02-01-scaled-dot-product-attention.md)
- [3.2.2 Multi-Head Attention](../../source/03-model-architecture/03-02-attention/03-02-02-multi-head-attention.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
- [3.3 Position-wise Feed-Forward Networks](../../source/03-model-architecture/03-03-position-wise-feed-forward-networks.md)
- [3.4 Embeddings and Softmax](../../source/03-model-architecture/03-04-embeddings-and-softmax.md)
- [4 Why Self-Attention](../../source/04-why-self-attention.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
- [6.3 English Constituency Parsing](../../source/06-results/06-03-english-constituency-parsing.md)
- [7 Conclusion](../../source/07-conclusion.md)
- [Attention Visualizations](../../source/attention-visualizations.md)
-->
