package com.boardgamemaster.ai;

@FunctionalInterface
public interface TextToSpeechClient { SpeechAudio synthesize(String text); }
