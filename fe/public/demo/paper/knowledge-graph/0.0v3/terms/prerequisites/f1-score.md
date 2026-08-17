---
type: Prerequisite Term
title: F1 Score
---

## F1 Score

F1 combines precision and recall into one evaluation score. Precision measures how many predicted items are correct, while recall measures how many reference items were recovered. Their harmonic mean is $F1=2\cdot precision\cdot recall/(precision+recall)$. A high F1 requires both measures to be high rather than allowing one to compensate completely for the other.

In constituency parsing, F1 evaluates the constituents predicted for a sentence against the reference parse. The paper reports results on Section 23 of the Wall Street Journal portion of the Penn Treebank and places the score beside each parser’s training regime, because systems trained with WSJ data alone, semi-supervised data, multi-task learning, or generative objectives use different amounts and kinds of supervision.

The four-layer Transformer reaches 91.3 F1 with WSJ-only discriminative training on about 40,000 sentences. This exceeds the listed Petrov et al. and Zhu et al. WSJ-only results of 90.4 and is below the listed Dyer et al. result of 91.7. With semi-supervised training on corpora totaling approximately 17 million sentences, the Transformer reaches 92.7, above the other semi-supervised results shown, which range from 91.3 to 92.1.

The remaining table entries include 93.0 for a multi-task system and 93.3 for a generative system. The paper uses the Transformer results to argue that the architecture generalizes beyond translation, including to parsing outputs that have strong structural constraints and are significantly longer than their inputs.

<!-- grounded-in
- [6.3 English Constituency Parsing](../../source/06-results/06-03-english-constituency-parsing.md)
-->
