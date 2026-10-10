package io.github.suppierk.shoostr;

import java.nio.charset.StandardCharsets;

class TestCodec implements Codec {
  @Override
  public <T> T read(byte[] source, Class<T> type) throws Exception {
    return type.cast(new String(source, StandardCharsets.UTF_8));
  }

  @Override
  public byte[] write(Object value) throws Exception {
    return ("\"" + value + "\"").getBytes(StandardCharsets.UTF_8);
  }
}
