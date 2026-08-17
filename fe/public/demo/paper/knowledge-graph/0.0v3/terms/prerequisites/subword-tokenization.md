---
type: Prerequisite Term
title: Subword Tokenization
---

## Subword Tokenization

Subword tokenization represents text using units that may be complete words or smaller pieces of words. A fixed vocabulary contains the available pieces, and a sentence is converted into a sequence of their token identifiers before it reaches the embedding layer. This allows a model to use a limited vocabulary while still composing words that are not stored as single vocabulary entries.

The paper uses two subword schemes. Its WMT 2014 English-German data contains about 4.5 million sentence pairs and is encoded with byte-pair encoding using a shared source-target vocabulary of about 37,000 tokens. Its larger English-French data contains 36 million sentence pairs and uses a 32,000 word-piece vocabulary.

Tokenization affects the sequence length $n$ seen by the Transformer and determines which discrete items receive learned embeddings. The paper batches sentence pairs by approximate sequence length, with about 25,000 source tokens and 25,000 target tokens per training batch. These token counts refer to the produced subword units rather than necessarily to space-separated words.

Tokenization also matters when interpreting evaluation values. The model-variation table reports perplexities per wordpiece under the paper’s byte-pair encoding and explicitly warns that they should not be compared with per-word perplexities. BLEU is reported for the final translations, while perplexity depends directly on the token sequence over which next-token probabilities are evaluated.

<!-- grounded-in
- [4 Why Self-Attention](../../source/04-why-self-attention.md)
- [5.1 Training Data and Batching](../../source/05-training/05-01-training-data-and-batching.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
-->
