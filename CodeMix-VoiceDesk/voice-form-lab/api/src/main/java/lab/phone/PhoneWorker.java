package lab.phone;

import lab.service.MlClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

@Component
public class PhoneWorker {
  private final PhoneStore store;
  private final RecordingMedia media;
  private final MlClient ml;
  private final PhoneSettings settings;

  public PhoneWorker(PhoneStore store, RecordingMedia media, MlClient ml, PhoneSettings settings) {
    this.store = store;
    this.media = media;
    this.ml = ml;
    this.settings = settings;
  }

  @Scheduled(
      initialDelayString = "${lab.phone.poll-ms:5000}",
      fixedDelayString = "${lab.phone.poll-ms:5000}")
  public void tick() {
    if (!settings.isEnabled()) return;
    try {
      store.recover();
      processOne();
      cleanup();
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(PhoneWorker.class)
          .error("event=phone_worker_error type={}", e.getClass().getSimpleName());
    }
  }

  public void processOne() {
    PhoneIntake p = store.claim();
    if (p == null) return;
    try {
      byte[] audio = media.download(p.recordingSid);
      var prediction = ml.transcribe(audio, "whisper", null, p.language);
      store.complete(p.id, p.attempts, prediction, audio);
    } catch (HttpClientErrorException e) {
      store.failed(
          p.id,
          p.attempts,
          e.getStatusCode().value() == 422 ? "NO_SPEECH_OR_INVALID_AUDIO" : "ML_REJECTED",
          e.getStatusCode().is4xxClientError());
    } catch (IllegalArgumentException e) {
      store.failed(p.id, p.attempts, "INVALID_RECORDING", true);
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      store.failed(p.id, p.attempts, "PROCESSING_UNAVAILABLE", false);
    }
  }

  public void cleanup() {
    for (var p : store.cleanupCandidates()) {
      try {
        media.delete(p.recordingSid);
        store.deleted(p.id);
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(PhoneWorker.class)
            .warn("event=phone_recording_cleanup_pending intakeId={}", p.id);
      }
    }
  }
}
