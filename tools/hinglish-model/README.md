# Hinglish spelling model

Reads a word typed in Latin letters (`pehchan`) and writes the Hindi words it could be (पहचान,
पेहचान, ...), each with a chance. The app turns each into the spelling key it stores for Hindi
words (`Hinglish.key`, here `pahchan`) and looks those keys up in the keyword engine next to the
key its rules make, so a page that says पहचान is found for `pehchan`, `pehchaan` or `pahchan`.
With a vault loaded, only spellings whose key the vault has are kept. When a search shows it is
Hinglish (a Hinglish-only little word such as `ka` or `wala`, or a typed word found in Hindi in the
vault), the best spellings are also used to search photos a second time, in Hindi script.

`rules.py` is `Hinglish.key` in Python; the two must not drift. `HinglishModelTest` checks the
app's Kotlin model against `reference.tsv`, written by `export.py` from the PyTorch model.

## Data and teacher

[Aksharantar](https://huggingface.co/datasets/ai4bharat/Aksharantar) (AI4Bharat), Hindi: about
1.3 million word pairs. The hand-collected pairs are CC-BY 4.0, the mined pairs CC0. Credit:
Madhani et al., "Aksharantar: Towards building open transliteration tools for the next billion
users", arXiv:2205.03018. The pairs typed by people (Dakshina, AK-Freq) count four times.

[IndicXlit](https://github.com/AI4Bharat/IndicXlit) (AI4Bharat, MIT licence), the 11.5M-weight
English-to-Indic transformer, is the teacher: `teacher.py` runs its released checkpoint in plain
PyTorch (no fairseq), loaded with `weights_only`. `distill_data.py` writes its five best Hindi
spellings for every Latin word of the training split (validation and test words left out); the
small model learns from those beside the real pairs.

## Rebuild

```
python distill_data.py --shard 0 --shards 4   (and shards 1-3 side by side)
python train.py --target hindi --distill D:/amar-translit/distill --emb 32 --enc 96 --dec 192 --feed 0 \
  --out D:/amar-translit/runs/x
python evaluate.py D:/amar-translit/runs/x/final.pt --split valid
python compare.py
python export.py D:/amar-translit/runs/x/final.pt --width 8 \
  --out ../../app/src/main/hinglish-assets/hinglish/translit.bin \
  --reference ../../app/src/test/resources/hinglish/reference.tsv
```

`evaluate.py` and `compare.py` report, on words typed by people, how often the right Hindi word's
key is among the rule key and the guesses kept, and how often a kept guess is the key of some
other real Hindi word (a wrong match it would allow).
