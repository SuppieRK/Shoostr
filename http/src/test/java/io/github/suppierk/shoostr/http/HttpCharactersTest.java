package io.github.suppierk.shoostr.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpCharactersTest {
  @Test
  void acceptsLettersDigitsAndHttpTokenPunctuation() {
    assertTrue(
        HttpCharacters.isValidHttpToken(
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$%&'*+-.^_`|~"));
  }

  @Test
  void rejectsAnEmptyHttpToken() {
    assertFalse(HttpCharacters.isValidHttpToken(""));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "A", "Z", "a", "z", "0", "9", "!", "#", "$", "%", "&", "'", "*", "+", "-", ".", "^", "_",
        "`", "|", "~"
      })
  void acceptsSingleCharacterHttpTokens(String value) {
    assertTrue(HttpCharacters.isValidHttpToken(value));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        " ", "\t", "\r", "\n", "\0", "\u007f", ":", "/", "?", "(", ")", "[", "]", "{", "}", ",",
        ";", "\"", "\\", "é", "\u0080", "𝒜"
      })
  void rejectsNonTokenCharactersInEveryPosition(String character) {
    for (var value :
        new String[] {
          character, character + "valid", "valid" + character + "suffix", "valid" + character
        }) {
      assertFalse(HttpCharacters.isValidHttpToken(value), value);
    }
  }

  @Test
  @SuppressWarnings("NullAway")
  void rejectsNullHttpTokens() {
    assertThrows(NullPointerException.class, () -> HttpCharacters.isValidHttpToken(null));
  }
}
