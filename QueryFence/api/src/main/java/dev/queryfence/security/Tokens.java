package dev.queryfence.security;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class Tokens {
  private Tokens() {}

  public static String create() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean equal(String left, String right) {
    return left != null
        && right != null
        && MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }
}
