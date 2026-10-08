package com.boardgamemaster.ai.infrastructure;

import com.boardgamemaster.ai.*;
import com.boardgamemaster.voice.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class OpenAiSpeechClientTest {
    private HttpServer server;
    private byte[] body;
    private int status=200;
    private String contentType="application/json", request, authorization;
    private long delay;
    private final ObjectMapper mapper=new ObjectMapper();
    private final byte[] mp3="ID3 original audio fixture".getBytes(StandardCharsets.US_ASCII);
    private final AudioInput audio=new AudioValidator(new VoiceProperties(null)).validate("RIFF1234WAVE original test audio".getBytes(StandardCharsets.US_ASCII),"audio/wav");
    @BeforeEach void start() throws Exception {
        body="{\"text\":\"Where does my token go?\"}".getBytes(StandardCharsets.UTF_8);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/audio", exchange -> {
            request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            authorization=exchange.getRequestHeaders().getFirst("Authorization");
            try { if(delay>0)Thread.sleep(delay);exchange.getResponseHeaders().set("Content-Type",contentType);exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); } catch(java.io.IOException ignored) { /* Timed-out client closes its stream. */ }
            finally { exchange.close(); }
        }); server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    private URI endpoint(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/audio");}
    private OpenAiSpeechToTextClient stt(Duration timeout){return new OpenAiSpeechToTextClient(new SttProperties("openai","whisper-1","fixture-key",timeout),mapper,HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),endpoint());}
    private OpenAiTextToSpeechClient tts(Duration timeout){return new OpenAiTextToSpeechClient(new TtsProperties("openai","tts-1","fixture-key",timeout,"alloy"),mapper,HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),endpoint());}
    @Test void transcribesBoundedMultipartWithoutUserFilenameOrPrompt() {
        assertThat(stt(Duration.ofSeconds(3)).transcribe(audio)).isEqualTo("Where does my token go?");
        assertThat(authorization).isEqualTo("Bearer fixture-key");
        assertThat(request).contains("name=\"model\"", "whisper-1", "name=\"response_format\"", "json", "filename=\"question.wav\"", "RIFF1234WAVE").doesNotContain("name=\"prompt\"");
    }
    @Test void synthesizesMp3WithModelAndVoice() throws Exception {
        body=mp3;contentType="audio/mpeg";
        assertThat(tts(Duration.ofSeconds(3)).synthesize("Move one space.").bytes()).isEqualTo(mp3);
        var json=mapper.readTree(request);assertThat(json.path("model").asText()).isEqualTo("tts-1");
        assertThat(json.path("voice").asText()).isEqualTo("alloy");assertThat(json.path("response_format").asText()).isEqualTo("mp3");
        assertThat(json.path("input").asText()).isEqualTo("Move one space.");
    }
    @ParameterizedTest @CsvSource({"400,PROVIDER_UNAVAILABLE","401,PROVIDER_UNAVAILABLE","302,PROVIDER_UNAVAILABLE","429,RATE_LIMITED","500,PROVIDER_UNAVAILABLE"})
    void providerErrorsAreSafeAndNotRetried(int code,String suffix){
        status=code;body="fixture-key private upstream content".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->stt(Duration.ofSeconds(3)).transcribe(audio)).isInstanceOf(SpeechException.class).hasMessageNotContaining("fixture-key").hasMessageNotContaining("private upstream").extracting(e->((SpeechException)e).code().name()).isEqualTo("STT_"+suffix);
        assertThatThrownBy(()->tts(Duration.ofSeconds(3)).synthesize("Question")).extracting(e->((SpeechException)e).code().name()).isEqualTo("TTS_"+suffix);
    }
    @ParameterizedTest @ValueSource(strings={"not json","{}","{\"text\":12}","{\"text\":null}"})
    void malformedTranscriptionFailsClosed(String value){body=value.getBytes(StandardCharsets.UTF_8);assertThatThrownBy(()->stt(Duration.ofSeconds(3)).transcribe(audio)).extracting(e->((SpeechException)e).code()).isEqualTo(SpeechException.Code.STT_INVALID_RESPONSE);}
    @Test void emptyAndOversizedTranscriptionRejected(){
        body="{\"text\":\"  \"}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->stt(Duration.ofSeconds(3)).transcribe(audio)).extracting(e->((SpeechException)e).code()).isEqualTo(SpeechException.Code.EMPTY_TRANSCRIPTION);
        body=("{\"text\":\""+"x".repeat(4001)+"\"}").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->stt(Duration.ofSeconds(3)).transcribe(audio)).extracting(e->((SpeechException)e).code()).isEqualTo(SpeechException.Code.STT_INVALID_RESPONSE);
        body=new byte[65537];assertThatThrownBy(()->stt(Duration.ofSeconds(3)).transcribe(audio)).isInstanceOf(SpeechException.class);
    }
    @Test void incorrectOrOversizedSynthesizedAudioRejected(){
        assertThatThrownBy(()->tts(Duration.ofSeconds(3)).synthesize("Question")).extracting(e->((SpeechException)e).code()).isEqualTo(SpeechException.Code.TTS_INVALID_RESPONSE);
        contentType="audio/mpeg";body="Not audio".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->tts(Duration.ofSeconds(3)).synthesize("Question")).extracting(e->((SpeechException)e).code()).isEqualTo(SpeechException.Code.TTS_INVALID_RESPONSE);
        body=new byte[4194305];assertThatThrownBy(()->tts(Duration.ofSeconds(3)).synthesize("Question")).isInstanceOf(SpeechException.class);
        assertThatThrownBy(()->tts(Duration.ofSeconds(3)).synthesize("x".repeat(4097))).extracting(e->((SpeechException)e).code()).isEqualTo(SpeechException.Code.TTS_TEXT_TOO_LONG);
    }
    @Test void transcriptionTimeoutMappedSafely(){delay=1000;assertThatThrownBy(()->stt(Duration.ofMillis(100)).transcribe(audio)).extracting(e->((SpeechException)e).code()).isEqualTo(SpeechException.Code.STT_TIMEOUT);}
    @Test void synthesisTimeoutMappedSafely(){delay=1000;assertThatThrownBy(()->tts(Duration.ofMillis(100)).synthesize("Question")).extracting(e->((SpeechException)e).code()).isEqualTo(SpeechException.Code.TTS_TIMEOUT);}
    @Test void legacyBlankDefaultsAndPrivateConfiguration(){
        var config=new SttProperties("","","",null);assertThat(config.provider()).isEqualTo("disabled");assertThat(config.model()).isEqualTo("whisper-1");
        assertThat(new TtsProperties("","","",null,"").voice()).isEqualTo("alloy");
        assertThat(new SttProperties("openai","whisper-1","fixture-key",null).toString()).doesNotContain("fixture-key");
        assertThatThrownBy(()->new SttProperties("openai",null,"",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SttProperties("unknown",null,"",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TtsProperties("disabled",null,"",Duration.ZERO,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TtsProperties("disabled",null,"",null,"unknown")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void defaultDisabledClientsNeedNoKeys(){
        new ApplicationContextRunner().withUserConfiguration(SpeechConfiguration.class).withBean(ObjectMapper.class,ObjectMapper::new).run(context->{
            assertThat(context).hasNotFailed();
            assertThatThrownBy(()->context.getBean(SpeechToTextClient.class).transcribe(audio)).extracting(e->((SpeechException)e).code()).isEqualTo(SpeechException.Code.STT_NOT_CONFIGURED);
            assertThatThrownBy(()->context.getBean(TextToSpeechClient.class).synthesize("Question")).extracting(e->((SpeechException)e).code()).isEqualTo(SpeechException.Code.TTS_NOT_CONFIGURED);
        });
    }
}
