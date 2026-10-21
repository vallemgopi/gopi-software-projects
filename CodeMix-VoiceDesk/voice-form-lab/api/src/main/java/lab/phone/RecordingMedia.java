package lab.phone;

public interface RecordingMedia {
  byte[] download(String recordingSid) throws Exception;

  void delete(String recordingSid) throws Exception;
}
