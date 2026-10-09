/*
 * Copyright 2019 Ugljesa Jovanovic
 * Modifications Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.ohs.bignum.integer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parsing and printing consume several digits per big-integer operation. These tests pin the
 * behaviour at and around the chunk boundaries (18 digits for base 10, 62 for base 2, 15 for base
 * 16, 12 for base 36) against a digit-at-a-time oracle.
 */
class ChunkedParsingAndToStringTest {

  /** Deterministic digit string of [length] digits with a non-zero leading digit. */
  private fun digits(length: Int, base: Int = 10): String {
    val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz".substring(0, base)
    return buildString(length) {
      for (i in 0 until length) {
        val digit = if (i == 0) 1 + (i * 7 + 3) % (base - 1) else (i * 7 + 3) % base
        append(alphabet[digit])
      }
    }
  }

  /** The pre-chunking algorithm: one multiply-add per digit. */
  private fun oracleParse(text: String, base: Int): BigInteger {
    var parsed = BigInteger.ZERO
    for (char in text) {
      parsed = parsed * base + char.digitToInt(base)
    }
    return parsed
  }

  @Test
  fun parsesAcrossBase10ChunkBoundaries() {
    for (length in listOf(1, 2, 17, 18, 19, 35, 36, 37, 54, 55, 100, 1000, 2000)) {
      val text = digits(length)
      val parsed = BigInteger.parseString(text, 10)
      assertEquals(oracleParse(text, 10), parsed, "parse of $length digits")
      assertEquals(text, parsed.toString(10), "print of $length digits")
    }
  }

  @Test
  fun parsesAcrossOtherBaseChunkBoundaries() {
    for ((base, lengths) in
      mapOf(
        2 to listOf(1, 61, 62, 63, 124, 125, 300),
        16 to listOf(1, 14, 15, 16, 31, 200),
        36 to listOf(1, 11, 12, 13, 25, 200),
      )) {
      for (length in lengths) {
        val text = digits(length, base)
        val parsed = BigInteger.parseString(text, base)
        assertEquals(oracleParse(text, base), parsed, "parse of $length base-$base digits")
        assertEquals(text, parsed.toString(base), "print of $length base-$base digits")
      }
    }
  }

  @Test
  fun printsZeroChunksWithPadding() {
    for (text in
      listOf(
        "1" + "0".repeat(18),
        "1" + "0".repeat(17) + "5",
        "1" + "0".repeat(36),
        "1" + "0".repeat(35) + "7",
        "9" + "0".repeat(40) + "1" + "0".repeat(18),
      )) {
      assertEquals(text, BigInteger.parseString(text, 10).toString(10))
    }
  }

  @Test
  fun parsesLeadingZerosAndSigns() {
    assertEquals(BigInteger.parseString("123", 10), BigInteger.parseString("000123", 10))
    assertEquals("0", BigInteger.ZERO.toString(10))
    assertEquals("0", BigInteger.parseString("0", 10).toString(10))
    assertEquals("0", BigInteger.parseString("0000000000000000000000", 10).toString(10))
    val text = digits(50)
    assertEquals("-$text", BigInteger.parseString("-$text", 10).toString(10))
    assertEquals(text, BigInteger.parseString("+$text", 10).toString(10))
  }

  @Test
  fun countsDecimalDigitsAroundPowersOfTen() {
    for (digits in (1..130) + listOf(300, 2000)) {
      val powerOfTen = BigInteger.parseString("1" + "0".repeat(digits - 1), 10)
      assertEquals(digits.toLong(), powerOfTen.numberOfDecimalDigits(), "10^${digits - 1}")
      assertEquals(
        digits.toLong(),
        (powerOfTen + BigInteger.ONE).numberOfDecimalDigits(),
        "10^${digits - 1} + 1",
      )
      if (digits > 1) {
        assertEquals(
          (digits - 1).toLong(),
          (powerOfTen - BigInteger.ONE).numberOfDecimalDigits(),
          "10^${digits - 1} - 1",
        )
      }
    }
    assertEquals(1L, BigInteger.ZERO.numberOfDecimalDigits())
    assertEquals(1L, BigInteger.parseString("-7", 10).numberOfDecimalDigits())
    assertEquals(20L, BigInteger.parseString("-12345678901234567890", 10).numberOfDecimalDigits())
  }

  @Test
  fun dividesBySmallWords() {
    val value = BigInteger.parseString(digits(400), 10)
    for (divisorText in
      listOf("1", "2", "3", "7", "10", "999999999", "1000000000", "4294967295", "4294967296")) {
      val divisor = BigInteger.parseString(divisorText, 10)
      val (quotient, remainder) = value.divideAndRemainder(divisor)
      assertEquals(value, quotient * divisor + remainder, "reconstruct for divisor $divisorText")
      assertTrue(remainder < divisor, "remainder below divisor $divisorText")
      assertTrue(remainder >= BigInteger.ZERO, "remainder non-negative for divisor $divisorText")
    }
    assertEquals(BigInteger.ZERO, BigInteger.ZERO / BigInteger.TEN)
    assertEquals(BigInteger.parseString("3", 10), BigInteger.parseString("30", 10) / BigInteger.TEN)
  }

  @Test
  fun parsesMixedCaseDigits() {
    assertEquals(
      BigInteger.parseString("deadbeefdeadbeefdeadbeef", 16),
      BigInteger.parseString("DeadBeefDEADBEEFdeadbeef", 16),
    )
    assertEquals(
      "deadbeefdeadbeefdeadbeef",
      BigInteger.parseString("DEADBEEFDEADBEEFDEADBEEF", 16).toString(16),
    )
  }
}
