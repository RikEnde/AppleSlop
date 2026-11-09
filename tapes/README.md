# Tapes

Put local PCM WAV cassette images and recordings here. No tapes are bundled yet.
This directory is ignored except for this README; add an explicit exception in
`.gitignore` for each tape intended to be shared.

To load a tape:

```sh
./run.sh --no-disk --tape tapes/program.wav --type 'LOAD\nRUN\n'
```

In the window, use **Tape > Insert Tape**, type `LOAD`, then select **Play**.
To record, select **New Blank Tape**, then **Record**, and type `SAVE`.
When the BASIC prompt returns, select **Stop** and **Save Tape As**.
