---
type: Prerequisite Term
title: Beam Search
---

## Beam Search

Beam search is an approximate procedure for decoding an autoregressive model. At each output step, the model assigns probabilities to possible next symbols for every retained partial sequence. Beam search expands those candidates and keeps only a fixed number of the most promising continuations. That number is the beam size.

A beam of size 1 commits to one continuation at each step. A larger beam preserves several competing prefixes, but it still explores only a small subset of all possible output sequences. Search ends when candidates produce an end symbol or reach the allowed output length. The chosen final sequence depends on how candidate scores and lengths are compared.

The paper uses a length penalty controlled by $\alpha$ when ranking translation and parsing candidates. For WMT machine translation, it uses beam size 4 and $\alpha=0.6$. These values were selected on the development set. The maximum output length is input length plus 50, with early termination when possible.

English constituency parsing uses longer outputs and a different search setting. The paper raises the maximum to input length plus 300 and uses beam size 21 with $\alpha=0.3$. The contrast illustrates that beam size, length penalty, and output-length limit are decoding choices adapted to the task; they do not change the Transformer’s learned encoder or decoder architecture.

<!-- grounded-in
- [6.1 Machine Translation](../../source/06-results/06-01-machine-translation.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
- [6.3 English Constituency Parsing](../../source/06-results/06-03-english-constituency-parsing.md)
-->
