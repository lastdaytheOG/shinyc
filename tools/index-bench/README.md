# Indexing speed bench

The indexing engine timed whole, as the app runs it, and what came out of it compared, so that a
change can be judged on being faster and on storing the same. It only ever touches a build that has
its own package and data: nothing here can reach a phone's own vault.

```
# A build that installs beside the real app, as com.amar.vault.bench
gradlew assemblePhone assemblePhoneAndroidTest -PtestPhoneBuild -PbenchApp -PphotoSearchModels -PphoneApk

# One scan of a gallery folder, from an empty vault; results in results/<label>.json and .digest.jsonl
python run.py <serial> com.amar.vault.bench <label> scan <folder> [--expect N] [--keep]

# Two builds in alternating order. Only runs made like this compare: the same build was seen 30%
# slower an hour later, and several times slower on a laptop running on battery. run.py records
# whether the laptop was on mains and flags a run that was not.
python ab.py <serial> <old package> <new package> <prefix> <folder> <count> [--rounds 2]

# Whether two runs stored the same, column by column
python compare_digests.py results/<a>.digest.jsonl results/<b>.digest.jsonl

# That a scan which skips what has been read ends with the same vault: run again, killed part way,
# a new picture, a copy, a picture changed in place
python validate_scan.py <serial> com.amar.vault.bench <folder>

# Reads: what each one costs and finds, against typed or drawn truth
adb shell am instrument -w -e class com.amar.vault.PictureOcrMeasureDeviceTest -e ocr draw ...
adb shell am instrument -w -e class com.amar.vault.PictureOcrMeasureDeviceTest -e ocr reads ...
python reads_score.py <reads.jsonl> <truth folder>
READS_FILE=<reads.jsonl> gradlew testDebugUnitTest --tests '*ScreenReadsReplayTest*'
```

The digest and the results hold timings, counts, file names and hashes, no text read off anything.
`reads.jsonl` does hold the text it read: it stays on the machine it was made on.

A test picture set is made from public images with `make_corpus.py`.
