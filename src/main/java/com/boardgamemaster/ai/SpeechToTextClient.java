package com.boardgamemaster.ai;

@FunctionalInterface
public interface SpeechToTextClient { String transcribe(AudioInput audio); }
