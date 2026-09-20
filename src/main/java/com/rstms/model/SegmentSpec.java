package com.rstms.model;

/** One window of the source video to re-voice in English. Everything outside a segment keeps the original audio. */
public class SegmentSpec {
    private double start;
    private double end;
    private String text;
    /** Optional per-segment voice override; null falls back to the job's default voice. */
    private String voice;

    public double getStart() { return start; }
    public void setStart(double v) { this.start = v; }
    public double getEnd() { return end; }
    public void setEnd(double v) { this.end = v; }
    public String getText() { return text; }
    public void setText(String v) { this.text = v; }
    public String getVoice() { return voice; }
    public void setVoice(String v) { this.voice = v; }
}
