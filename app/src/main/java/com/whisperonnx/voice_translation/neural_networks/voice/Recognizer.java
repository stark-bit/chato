/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.whisperonnx.voice_translation.neural_networks.voice;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.whisperonnx.voice_translation.neural_networks.NeuralNetworkApi;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.extensions.OrtxPackage;

public class Recognizer extends NeuralNetworkApi {
    private static final int MAX_TOKENS_PER_SECOND = 30;
    private static final int MAX_TOKENS = 445;   //if we generate more than this quantity of tokens for a transcription we have an error
    public static final String UNDEFINED_TEXT = "[(und)]";
    private ArrayList<RecognizerListener> callbacks = new ArrayList<>();
    private boolean recognizing = false;
    private ArrayDeque<DataContainer> dataToRecognize = new ArrayDeque<>();
    private final Object lock = new Object();

    public static final Action ACTION_TRANSCRIBE = Action.TRANSCRIBE;
    public static final Action ACTION_TRANSLATE = Action.TRANSLATE;

    public enum Action {
        TRANSLATE, TRANSCRIBE
    }

    public static final String[] LANGUAGES = {
            "en",
            "zh",
            "de",
            "es",
            "ru",
            "ko",
            "fr",
            "ja",
            "pt",
            "tr",
            "pl",
            "ca",
            "nl",
            "ar",
            "sv",
            "it",
            "id",
            "hi",
            "fi",
            "vi",
            "he",
            "uk",
            "el",
            "ms",
            "cs",
            "ro",
            "da",
            "hu",
            "ta",
            "no",
            "th",
            "ur",
            "hr",
            "bg",
            "lt",
            "la",
            "mi",
            "ml",
            "cy",
            "sk",
            "te",
            "fa",
            "lv",
            "bn",
            "sr",
            "az",
            "sl",
            "kn",
            "et",
            "mk",
            "br",
            "eu",
            "is",
            "hy",
            "ne",
            "mn",
            "bs",
            "kk",
            "sq",
            "sw",
            "gl",
            "mr",
            "pa",
            "si",
            "km",
            "sn",
            "yo",
            "so",
            "af",
            "oc",
            "ka",
            "be",
            "tg",
            "sd",
            "gu",
            "am",
            "yi",
            "lo",
            "uz",
            "fo",
            "ht",
            "ps",
            "tk",
            "nn",
            "mt",
            "sa",
            "lb",
            "my",
            "bo",
            "tl",
            "mg",
            "as",
            "tt",
            "haw",
            "ln",
            "ha",
            "ba",
            "jw",
            "su",
            "yue"
    };

    private final int START_TOKEN_ID = 50258;
    private final int TRANSLATE_TOKEN_ID = 50358;
    private final int TRANSCRIBE_TOKEN_ID = 50359;
    private final int NO_TIMESTAMPS_TOKEN_ID = 50363;

    private OrtSession session;
    private OrtSession initSession;
    private OrtSession encoderSession;
    private OrtSession cacheInitSession;
    private OrtSession decoderSession;
    private OrtSession detokenizerSession;
    private OrtEnvironment onnxEnv;

    public Recognizer(Context context, final boolean returnResultOnlyAtTheEnd, final NeuralNetworkApi.InitListener initListener) {
        //onnxEnv = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE);
        onnxEnv = OrtEnvironment.getEnvironment();

        String modelInitPath = context.getExternalFilesDir(null).getPath() + "/Whisper_initializer.onnx";
        String encoderPath = context.getExternalFilesDir(null).getPath() + "/Whisper_encoder.onnx";
        String decoderPath = context.getExternalFilesDir(null).getPath() + "/Whisper_decoder.onnx";
        String cacheInitPath = context.getExternalFilesDir(null).getPath() + "/Whisper_cache_initializer.onnx";
        String detokenizerPath = context.getExternalFilesDir(null).getPath() + "/Whisper_detokenizer.onnx";

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OrtSession.SessionOptions initSessionOptions = new OrtSession.SessionOptions();
                    initSessionOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
                    initSessionOptions.setCPUArenaAllocator(false);
                    initSessionOptions.setMemoryPatternOptimization(false);
                    initSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    initSession = onnxEnv.createSession(modelInitPath, initSessionOptions);

                    OrtSession.SessionOptions encoderSessionOptions = new OrtSession.SessionOptions();
                    encoderSessionOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());

                    ActivityManager actManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                    ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
                    actManager.getMemoryInfo(memInfo);
                    long totalMemory = memInfo.totalMem / 1000000L;

                    if(totalMemory <= 7000){
                        encoderSessionOptions.setCPUArenaAllocator(false);
                        encoderSessionOptions.setMemoryPatternOptimization(false);
                    }else {
                        encoderSessionOptions.setCPUArenaAllocator(true);
                        encoderSessionOptions.setMemoryPatternOptimization(true);
                    }
                    encoderSessionOptions.setSymbolicDimensionValue("batch_size", 1);
                    encoderSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    encoderSession = onnxEnv.createSession(encoderPath, encoderSessionOptions);

                    OrtSession.SessionOptions cacheSessionOptions = new OrtSession.SessionOptions();
                    cacheSessionOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
                    cacheSessionOptions.setCPUArenaAllocator(false);
                    cacheSessionOptions.setMemoryPatternOptimization(false);
                    cacheSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    cacheInitSession = onnxEnv.createSession(cacheInitPath, cacheSessionOptions);

                    OrtSession.SessionOptions decoderSessionOptions = new OrtSession.SessionOptions();
                    decoderSessionOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
                    decoderSessionOptions.setCPUArenaAllocator(false);
                    decoderSessionOptions.setMemoryPatternOptimization(false);
                    decoderSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    decoderSession = onnxEnv.createSession(decoderPath, decoderSessionOptions);

                    OrtSession.SessionOptions detokenizerSessionOptions = new OrtSession.SessionOptions();
                    detokenizerSessionOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
                    detokenizerSessionOptions.setCPUArenaAllocator(false);
                    detokenizerSessionOptions.setMemoryPatternOptimization(false);
                    //detokenizerSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    detokenizerSession = onnxEnv.createSession(detokenizerPath, detokenizerSessionOptions);

                    initListener.onInitializationFinished();
                } catch (OrtException e) {
                    e.printStackTrace();
                    initListener.onError(new int[]{ErrorCodes.ERROR_LOADING_MODEL},0);
                }
            }
        }).start();
    }

    /**
     * Recognizes the speech audio. This method should be called every time a chunk of byte buffer
     * is returned from onVoice.
     *
     * @param data The audio data.
     */
    public void recognize(final float[] data, int beamSize, final String languageCode, final Action action) {
        new Thread("recognizer"){
            @Override
            public void run() {
                super.run();
                synchronized (lock) {
                    Log.e("recognizer","recognizingCalled");
                    if (data != null) {
                        dataToRecognize.addLast(new DataContainer(data, beamSize, languageCode, action));
                        if (dataToRecognize.size() >= 1 && !recognizing) {
                            recognize();
                        }
                    }
                }
            }
        }.start();
    }

    private void recognize() {
        recognizing = true;
        DataContainer data = dataToRecognize.pollFirst();
        if (initSession != null && encoderSession != null && cacheInitSession != null && decoderSession != null && detokenizerSession != null) {
            if (data != null) {
                //we convert data in un audioTensor and start the transcription
                try {
                    FloatBuffer floatAudioDataBuffer = FloatBuffer.wrap(data.data);
                    OnnxTensor audioTensor = OnnxTensor.createTensor(onnxEnv, floatAudioDataBuffer, TensorUtils.tensorShape(1L, (long) data.data.length));

                    // if we generate more than this number of tokens it means that we have an infinite loop due to the fact that the sound cannot be transcribed with the language selected
                    int maxTokens = (data.data.length / 16000) * MAX_TOKENS_PER_SECOND;
                    if(maxTokens > MAX_TOKENS){
                        maxTokens = MAX_TOKENS;
                    }
                    boolean executionHitMaxLength = false;

                    //execution of pre ops
                    long time = System.currentTimeMillis();
                    long startTimeInMs = SystemClock.elapsedRealtime();
                    Map initInputs = (Map) (new LinkedHashMap());
                    initInputs.put("audio_pcm", audioTensor);
                    OrtSession.Result outputsInit = initSession.run(initInputs);
                    OnnxTensor outputInit = (OnnxTensor) outputsInit.get(0);
                    //Log.i("performance", "pre ops done in: " + (System.currentTimeMillis()-time) + "ms");

                    //execution of the encoder
                    Map inputs = (Map) (new LinkedHashMap());
                    inputs.put("input_features", outputInit);
                    OrtSession.Result outputs = encoderSession.run(inputs);
                    OnnxTensor outputEncoder = (OnnxTensor) outputs.get(0);
                    //Log.i("performance", "Encoder done in: " + (SystemClock.elapsedRealtime() - startTimeInMs) + "ms");

                    //execution of decoder (single sequence: one language, batch size 1)
                    final int eos = 50257;
                    ArrayList<Integer> completeOutput = new ArrayList<Integer>();
                    double outputProbability = 0;

                    OnnxTensor inputIDsTensor = null;
                    OnnxTensor decoderOutput;
                    Map<String, OnnxTensor> decoderInput;
                    float[] outputValues;

                    //We prepare the cache initializer input and execute it
                    Map<String, OnnxTensor> initInput = new HashMap<String, OnnxTensor>();
                    initInput.put("encoder_hidden_states", outputEncoder);
                    OrtSession.Result initResult = cacheInitSession.run(initInput);

                    //We start the iterative execution of the decoder
                    OrtSession.Result result = null;
                    OrtSession.Result oldResult;
                    int max = -1;
                    boolean isFirstIteration = true;  //It is used to avoid closing initialResult
                    int j = 1;
                    //first we run the decoder with the first 4 fixed values (START_TOKEN_ID, languageID, TRANSCRIBE/TRANSLATE_TOKEN_ID, NO_TIMESTAMPS_TOKEN_ID)
                    int languageID = getLanguageID(data.languageCode);
                    int[] decoderInitialInputIDs = {START_TOKEN_ID, languageID, data.action == ACTION_TRANSLATE ? TRANSLATE_TOKEN_ID : TRANSCRIBE_TOKEN_ID, NO_TIMESTAMPS_TOKEN_ID};

                    while (max != eos) {
                        if (j <= 4) {
                            if (languageID == -1 && j == 2) {  //"auto": let the model pick the language token
                                Log.d("decoderInitialInputIDs", "language auto dectect");
                            } else {
                                inputIDsTensor = TensorUtils.convertIntArrayToTensor(onnxEnv, new int[]{decoderInitialInputIDs[j-1]});
                            }
                        }
                        //We prepare the decoder input
                        decoderInput = new HashMap<String, OnnxTensor>();
                        decoderInput.put("input_ids", inputIDsTensor);
                        if (isFirstIteration) {
                            long[] shape = {1, 12, 0, 64};
                            OnnxTensor decoderPastTensor = TensorUtils.createFloatTensorWithSingleValue(onnxEnv, 0, shape);
                            for (int i = 0; i < 12; i++) {
                                decoderInput.put("past_key_values." + i + ".decoder.key", decoderPastTensor);
                                decoderInput.put("past_key_values." + i + ".decoder.value", decoderPastTensor);
                                decoderInput.put("past_key_values." + i + ".encoder.key", (OnnxTensor) initResult.get("present." + i + ".encoder.key").get());
                                decoderInput.put("past_key_values." + i + ".encoder.value", (OnnxTensor) initResult.get("present." + i + ".encoder.value").get());
                            }
                            isFirstIteration = false;
                        } else {
                            for (int i = 0; i < 12; i++) {
                                decoderInput.put("past_key_values." + i + ".decoder.key", (OnnxTensor) result.get("present." + i + ".decoder.key").get());
                                decoderInput.put("past_key_values." + i + ".decoder.value", (OnnxTensor) result.get("present." + i + ".decoder.value").get());
                                decoderInput.put("past_key_values." + i + ".encoder.key", (OnnxTensor) initResult.get("present." + i + ".encoder.key").get());
                                decoderInput.put("past_key_values." + i + ".encoder.value", (OnnxTensor) initResult.get("present." + i + ".encoder.value").get());
                            }
                        }
                        oldResult = result;
                        //execution of decoder (with cache)
                        result = decoderSession.run(decoderInput);
                        if (oldResult != null) {
                            oldResult.close(); //release the previous result's memory (it would otherwise accumulate)
                        }
                        //we extract the logits and the highest value
                        decoderOutput = (OnnxTensor) result.get("logits").get();
                        outputValues = ((float[][][]) decoderOutput.getValue())[0][0];
                        max = Utils.getIndexOfLargest(outputValues);
                        completeOutput.add(max);
                        //We prepare the inputs for the next iteration
                        inputIDsTensor = TensorUtils.convertIntArrayToTensor(onnxEnv, new int[]{max});

                        if (j >= maxTokens) {
                            executionHitMaxLength = true;
                            max = eos;
                        }
                        j++;
                    }

                    //execution of the detokenizer
                    Map detokenizerInputs = (Map) (new LinkedHashMap());
                    String language = data.languageCode;
                    String finalText = UNDEFINED_TEXT;
                    if (!executionHitMaxLength) {
                        int[] sequences = completeOutput.stream().mapToInt(i -> i).toArray();
                        if (language.equals("auto")) language = getLanguageCode(sequences);
                        detokenizerInputs.put("sequences", TensorUtils.createInt32Tensor(onnxEnv, sequences, new long[]{1, 1, sequences.length}));
                        OrtSession.Result detokenizerOutputs = this.detokenizerSession.run(detokenizerInputs);
                        Object finalTextResult = detokenizerOutputs.get(0).getValue();
                        finalText = ((String[][]) finalTextResult)[0][0];
                        detokenizerOutputs.close();
                    }

                    outputs.close();
                    notifyResult(correctText(finalText), language, outputProbability, true);
                    //closing all results
                    outputs.close();
                    outputInit.close();
                    initResult.close();

                    Log.i("performance", "SPEECH RECOGNITION DONE IN: " + (SystemClock.elapsedRealtime() - startTimeInMs) + "ms");

                } catch (OrtException e) {
                    e.printStackTrace();
                    notifyError(new int[]{ErrorCodes.ERROR_EXECUTING_MODEL}, 0);
                }
            }
        }
        if (!dataToRecognize.isEmpty()){
            recognize();
        }else {
            recognizing = false;
        }
    }

    private String correctText(String text){
        String correctedText = text;

        //sometimes, even if timestamps are deactivated, Whisper insert those anyway (es. <|0.00|>), so we remove eventual timestamps
        String regex = "<\\|[^>]*\\|> ";    //with this regex we remove all substrings of the form "<|something|> "
        correctedText = correctedText.replaceAll(regex, "");

        //we remove eventual white space from both ends of the text
        correctedText = correctedText.trim();

        if(correctedText.length() >= 2) {
            //if the correctedText start with a lower case letter we make it upper case
            char firstChar = correctedText.charAt(0);
            if (Character.isLowerCase(firstChar)) {
                StringBuilder sb = new StringBuilder(correctedText);
                sb.setCharAt(0, Character.toUpperCase(firstChar));
                correctedText = sb.toString();
            }
            //if the correctedText contains a "..." we remove it
            correctedText = correctedText.replace("...", "");
        }
        return correctedText;
    }

    public void destroy() {
        //eventually if in the future I decide to load Whisper only for WalkieTalkie and Conversation then all the resources will be released here
    }

    public int getLanguageID(String language){
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i].equals(language)) {
                return START_TOKEN_ID + i + 1;
            }
        }
        if (!language.equals("auto")) Log.e("error", "Error Converting Language code " + language + " to Whisper code");
        return -1;
    }

    public String getLanguageCode(int[] sequence){
        int langToken = sequence[0];
        int index = langToken - START_TOKEN_ID - 1;
        if (index >= 0 && index < LANGUAGES.length) {  //if sequence starts with language token return it
            return LANGUAGES[index];
        } else {
            Log.e("error", "Error detecting language");
            return "??";  //otherwise return "??"
        }
    }

    public void addCallback(final RecognizerListener callback) {
        callbacks.add(callback);
    }

    private void notifyResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).onSpeechRecognizedResult(text, languageCode, confidenceScore, isFinal);
        }
    }

    private void notifyError(int[] reasons, long value) {
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).onError(reasons, value);
        }
    }

    private static class DataContainer{
        private float[] data;
        private String languageCode;
        private int beamSize;
        private Action action;

        private DataContainer(float[] data, int beamSize, String languageCode, Action action){
            this.data = data;
            this.beamSize = beamSize;
            this.languageCode = languageCode;
            this.action = action;
        }
    }
}
