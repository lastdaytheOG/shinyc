# Hinglish spelling model

Reads a word typed in Latin letters (`pehchan`) and guesses the spelling keys of the Hindi
words it could be (`pahchan`, the key the app stores for पहचान). The app looks those keys up
in the keyword engine next to the keys its rules make, so a page that says पहचान is found for
`pehchan`, `pehchaan` or `pahchan`.

The key of a Hindi word is `Hinglish.key` in the app: its romanised, folded form. `rules.py`
is the same function in Python; the training targets come from it, so the two must not drift.
`HinglishModelTest` checks the app's Kotlin model against `reference.tsv`, written by
`export.py` from the PyTorch model.

## Data

[Aksharantar](https://huggingface.co/datasets/ai4bharat/Aksharantar) (AI4Bharat), Hindi:
about 1.3 million word pairs. The hand-collected pairs are CC-BY 4.0, the mined pairs CC0.
Credit: Madhani et al., "Aksharantar: Towards building open transliteration tools for the next
billion users", arXiv:2205.03018. The pairs typed by people (Dakshina, AK-Freq) are counted
four times in training.

## Rebuild

```
python train.py --data D:/amar-translit/data/hin --out D:/amar-translit/runs/x
python evaluate.py D:/amar-translit/runs/x/final.pt --split valid
python export.py D:/amar-translit/runs/x/final.pt \
  --out ../../app/src/main/hinglish-assets/hinglish/translit.bin \
  --reference ../../app/src/test/resources/hinglish/reference.tsv
```

`evaluate.py` reports, per source, how often the right key is among the rule key and the
model's guesses above a chance, and how often a guess is the key of some other real Hindi
word (a wrong match the guess would allow).
