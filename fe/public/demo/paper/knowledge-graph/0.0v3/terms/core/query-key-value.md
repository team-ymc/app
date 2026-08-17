---
type: Core Term
title: Queries, Keys, and Values
---

## Queries, Keys, and Values

Queries, keys, and values are the three inputs through which the paper describes every attention operation. An attention function receives a query and a set of key-value pairs. It compares the query with each key to determine the weight assigned to the corresponding value, then returns a weighted sum of the values. The query, keys, values, and resulting output are all vectors.

In scaled dot-product attention, queries and keys have dimension $d_k$, while values have dimension $d_v$. The implementation processes sets of them simultaneously: queries are packed into a matrix $Q$, keys into $K$, and values into $V$. The compatibility matrix $QK^T$ contains query-key dot products. After division by $\sqrt{d_k}$ and softmax, the resulting weights multiply $V$, giving $Attention(Q,K,V)=softmax(QK^T/\sqrt{d_k})V$.

Multi-head attention does not send the same full-dimensional matrices directly through every head. Head $i$ first computes $QW_i^Q$, $KW_i^K$, and $VW_i^V$ using separate learned projection matrices. The paper gives $W_i^Q$ and $W_i^K$ shapes of $d_{model}\times d_k$, $W_i^V$ a shape of $d_{model}\times d_v$, and uses $d_k=d_v=64$ for each of eight heads in the base model. This gives each head a different projected version of the queries, keys, and values before attention is computed.

Where $Q$, $K$, and $V$ come from determines which of the Transformer’s three attention applications is being performed. In encoder self-attention, all three come from the output of the preceding encoder layer, and every position can attend to every encoder position. In masked decoder self-attention, all three come from the decoder representation, but future connections are prohibited. In encoder-decoder attention, queries come from the preceding decoder layer while keys and values come from the encoder output, allowing each decoder position to attend over every input position.

<!-- grounded-in
- [3.2 Attention](../../source/03-model-architecture/03-02-attention/overview.md)
- [3.2.1 Scaled Dot-Product Attention](../../source/03-model-architecture/03-02-attention/03-02-01-scaled-dot-product-attention.md)
- [3.2.2 Multi-Head Attention](../../source/03-model-architecture/03-02-attention/03-02-02-multi-head-attention.md)
- [3.2.3 Applications of Attention in our Model](../../source/03-model-architecture/03-02-attention/03-02-03-applications.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
-->
