package com.rstms.tts;

import java.nio.file.Path;

/** Synthesises one piece of text to a WAV file on disk. */
public interface TtsService {

    /**
     * @param speakingRate 1.0 = natural pace, greater than 1.0 = faster, less = slower.
     * @return path to a WAV containing exactly this text.
     */
    Path synthesize(String text, String voice, double speakingRate, Path targetWav);

    /** Sample rate of the WAVs this voice produces. */
    int sampleRateHertz(String voice);

    /** Maps a friendly alias to a provider-specific voice identifier. */
    String resolveVoice(String alias);

    /** Human-readable name for logs. */
    String describe();
}
