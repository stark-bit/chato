package com.whisperonnx.backend;

import com.whisperonnx.thirdparty.rtranslator.voice.Recognizer;




public class WhisperResult {
    private final String result;
    private final String language;
    private final Recognizer.Action task;

    public WhisperResult(String result, String language, Recognizer.Action task){
        this.result = result;
        this.language = language;
        this.task = task;
    }

    public String getResult() {
        return result;
    }

    public String getLanguage() {
        return language;
    }

    public Recognizer.Action getTask() {
        return task;
    }
}
