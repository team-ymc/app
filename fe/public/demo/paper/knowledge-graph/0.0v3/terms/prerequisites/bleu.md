---
type: Prerequisite Term
title: BLEU
---

## BLEU

BLEU is an automatic metric for machine translation. It compares sequences of words or tokens in a generated translation with one or more reference translations, using matching n-grams together with a penalty for outputs that are too short. Higher BLEU indicates greater measured overlap under the metric; it is a comparison score, not a probability assigned by the model.

BLEU can be used on a held-out development set to choose or compare configurations and on a test set to report final translation quality. Scores are meaningful only with their task, dataset, and evaluation setup. A difference within one reported benchmark is more informative than comparing numbers produced under unrelated conditions.

The paper uses BLEU as its main translation-quality measure. On WMT 2014 English-to-German newstest2014, Transformer (big) reaches 28.4 BLEU, more than 2 BLEU above the previously reported results including ensembles. The base model reaches 27.3, also above the earlier systems listed in the comparison table.

For WMT 2014 English-to-French, the results section states 41.0 for the big model, while the abstract and reproduced comparison table report 41.8. Keeping that source discrepancy visible is important when quoting the result. The model-variation experiments separately report development BLEU on English-to-German newstest2013, where the base configuration reaches 25.8 and the big configuration 26.4.

<!-- grounded-in
- [Abstract](../../source/abstract.md)
- [5.4 Regularization](../../source/05-training/05-04-regularization.md)
- [6.1 Machine Translation](../../source/06-results/06-01-machine-translation.md)
- [6.2 Model Variations](../../source/06-results/06-02-model-variations.md)
-->
