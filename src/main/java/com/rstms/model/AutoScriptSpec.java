package com.rstms.model;

/** Requests automatic transcription (real timestamps) + translation of the source video's audio into English. */
public class AutoScriptSpec {
    /** ISO 639-1 code of the spoken language in the source video, e.g. "ar", "fr", "es". */
    private String sourceLanguage;
    /** "argos" (default, free/offline) or "claude" (paid API, better quality - needs ANTHROPIC_API_KEY). Null = app default. */
    private String translationEngine;
    /** Optional one-line hint for the Claude engine, e.g. "football commentary, energetic tone". */
    private String context;

    public String getSourceLanguage() { return sourceLanguage; }
    public void setSourceLanguage(String v) { this.sourceLanguage = v; }
    public String getTranslationEngine() { return translationEngine; }
    public void setTranslationEngine(String v) { this.translationEngine = v; }
    public String getContext() { return context; }
    public void setContext(String v) { this.context = v; }
}
