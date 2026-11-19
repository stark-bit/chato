# Benchmark clips

16 kHz mono 16-bit WAVs used by `make bench`. Add your own recordings here to test with your voice.

The included clips are cut from `samples/jfk.wav` in [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT):
- `clip_03s.wav`: the first 3.2 s;
- `clip_11s.wav`: the whole 11 s sample;
- `clip_30s.wav`: the sample looped to 30 s.

Made with ffmpeg, for example `ffmpeg -i jfk.wav -t 3.2 -ar 16000 -ac 1 -c:a pcm_s16le clip_03s.wav`.
