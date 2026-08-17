---
type: Core Term
title: Multi-Head Attention
---

## Multi-Head Attention

Multi-head attention replaces one full-dimensional attention operation with several reduced-dimensional attention operations running in parallel. The Transformer linearly projects $Q$, $K$, and $V$ $h$ times using different learned parameters. Each projected triple passes through scaled dot-product attention and produces one head output. The head outputs are concatenated and passed through a final learned projection to return to the model dimension.

The paper defines $MultiHead(Q,K,V)=Concat(head_1,\ldots,head_h)W^O$, with $head_i=Attention(QW_i^Q,KW_i^K,VW_i^V)$. The query and key projection matrices have shape $d_{model}\times d_k$, the value projections have shape $d_{model}\times d_v$, and $W^O$ has shape $hd_v\times d_{model}$. In the base model, $h=8$ and $d_k=d_v=d_{model}/h=64$. Because each head uses a reduced dimension, the total computational cost remains similar to single-head attention operating at full dimensionality.

The motivation is to preserve multiple attention patterns rather than average them into one result. The paper states that several heads allow the model to attend jointly to information from different representation subspaces at different positions, while averaging within a single head inhibits this. In the background discussion, multi-head attention is also presented as the response to reduced effective resolution caused by averaging attention-weighted positions.

The same multi-head construction is used in three places with different sources for $Q$, $K$, and $V$. Encoder self-attention uses the preceding encoder layer for all three. Masked decoder self-attention uses the decoder representation and blocks future positions. Encoder-decoder attention uses decoder queries with encoder keys and values. Thus “multi-head” describes how attention is computed in parallel, while “self-attention,” “masked self-attention,” and “encoder-decoder attention” describe where the inputs come from and which connections are permitted.

The model-variation experiments vary the number of heads while keeping computation constant. Single-head attention records 24.9 development BLEU, 0.9 below the best listed value of 25.8. Four heads reach 25.5, sixteen heads also reach 25.8, and thirty-two heads fall to 25.4; the authors conclude that quality drops with both a single head and too many heads. Appendix visualizations provide qualitative evidence of head diversity: different heads attend across a long dependency, show sharp patterns around “its,” and produce different structures over the same sentence.

<!-- grounded-in
- [2 Background](../../source/02-background.md)
- [3 Model Architecture](../../source/03-model-architecture/overview.md)
- [3.1 Encoder and Decoder Stacks](../../source/03-model-architecture/03-01-encoder-decoder-stacks.md)
- [3.2 Attention](../../source/03-model-architecture/03-02-attention/overview.md)
- [3.2.2 Multi-Head Attention](../../source/03-model-architecture/03-02-attention/03-02-02-multi-head-attention.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
- [4 Why Self-Attention](../../source/04-why-self-attention.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
- [7 Conclusion](../../source/07-conclusion.md)
- [Attention Visualizations](../../source/attention-visualizations.md)
-->
