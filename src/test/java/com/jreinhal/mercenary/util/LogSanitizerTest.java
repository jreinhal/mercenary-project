package com.jreinhal.mercenary.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LogSanitizerTest {

    @Nested
    @DisplayName("querySummary()")
    class QuerySummaryTest {
        @Test
        @DisplayName("Should return len=0 and id=none for null query")
        void shouldHandleNull() {
            assertThat(LogSanitizer.querySummary(null)).isEqualTo("[len=0,id=none]");
        }

        @Test
        @DisplayName("Should return length and hash for non-null query")
        void shouldReturnLengthAndHash() {
            String result = LogSanitizer.querySummary("test query");
            assertThat(result).startsWith("[len=10,id=");
            assertThat(result).endsWith("]");
        }

        @Test
        @DisplayName("Should return consistent hash for same input")
        void shouldBeConsistent() {
            assertThat(LogSanitizer.querySummary("hello"))
                    .isEqualTo(LogSanitizer.querySummary("hello"));
        }

        @Test
        @DisplayName("Should handle empty string")
        void shouldHandleEmptyString() {
            assertThat(LogSanitizer.querySummary("")).isEqualTo("[len=0,id=0]");
        }
    }

    @Nested
    @DisplayName("sanitize()")
    class SanitizeTest {
        @Test
        @DisplayName("Should return empty string for null input")
        void shouldHandleNull() {
            assertThat(LogSanitizer.sanitize(null)).isEmpty();
        }

        @Test
        @DisplayName("Should pass through normal text unchanged")
        void shouldPassThroughNormalText() {
            assertThat(LogSanitizer.sanitize("normal text")).isEqualTo("normal text");
        }

        @Test
        @DisplayName("Should strip newlines (replace with space)")
        void shouldReplaceNewlines() {
            assertThat(LogSanitizer.sanitize("line1\nline2")).isEqualTo("line1 line2");
        }

        @Test
        @DisplayName("Should strip carriage returns")
        void shouldStripCarriageReturns() {
            assertThat(LogSanitizer.sanitize("line1\r\nline2")).isEqualTo("line1 line2");
        }

        @Test
        @DisplayName("Should strip control characters (log injection prevention)")
        void shouldStripControlChars() {
            assertThat(LogSanitizer.sanitize("inject\u0000ed")).isEqualTo("injected");
            assertThat(LogSanitizer.sanitize("null\u0008byte")).isEqualTo("nullbyte");
        }

        @Test
        @DisplayName("Should handle mixed control characters and newlines")
        void shouldHandleMixedInput() {
            String input = "user\u0007\ninput\r\nwith\u001Bcontrol";
            String result = LogSanitizer.sanitize(input);
            assertThat(result).doesNotContain("\n", "\r", "\u0007", "\u001B");
        }
    }
}
