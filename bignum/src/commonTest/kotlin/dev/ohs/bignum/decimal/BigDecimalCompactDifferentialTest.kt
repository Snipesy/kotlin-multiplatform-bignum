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

package dev.ohs.bignum.decimal

import dev.ohs.bignum.integer.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks that every compact (`Long`) fast path produces exactly what the `BigInteger` path
 * produces. Each case runs the same operation on compact operands, on forcibly inflated operands,
 * and on mixed pairs, and requires identical results including exponent, precision, mode, string
 * forms and hash code. Exceptions must match by type and message.
 */
class BigDecimalCompactDifferentialTest {

  private val random = Random(20261009)

  private val roundingModes =
    listOf(
      RoundingMode.FLOOR,
      RoundingMode.CEILING,
      RoundingMode.AWAY_FROM_ZERO,
      RoundingMode.TOWARDS_ZERO,
      RoundingMode.ROUND_HALF_AWAY_FROM_ZERO,
      RoundingMode.ROUND_HALF_TOWARDS_ZERO,
      RoundingMode.ROUND_HALF_CEILING,
      RoundingMode.ROUND_HALF_FLOOR,
      RoundingMode.ROUND_HALF_TO_EVEN,
      RoundingMode.ROUND_HALF_TO_ODD,
    )

  private val modes: List<DecimalMode?> =
    listOf(
      null,
      DecimalMode(),
      DecimalMode(1, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO),
      DecimalMode(3, RoundingMode.CEILING),
      DecimalMode(5, RoundingMode.ROUND_HALF_TO_EVEN),
      DecimalMode(10, RoundingMode.FLOOR),
      DecimalMode(18, RoundingMode.ROUND_HALF_TO_ODD),
      DecimalMode(19, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO),
      DecimalMode(20, RoundingMode.TOWARDS_ZERO),
      DecimalMode(30, RoundingMode.AWAY_FROM_ZERO),
      DecimalMode.US_CURRENCY,
      DecimalMode(12, RoundingMode.ROUND_HALF_TO_EVEN, 0),
      DecimalMode(8, RoundingMode.ROUND_HALF_FLOOR, 3),
      DecimalMode(0, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO, 2),
    )

  private val specialSignificands =
    listOf(
      0L,
      1L,
      -1L,
      5L,
      -5L,
      9L,
      10L,
      15L,
      25L,
      50L,
      99L,
      100L,
      101L,
      999_999_999L,
      1_000_000_000L,
      999_999_999_999_999_999L,
      1_000_000_000_000_000_000L,
      Long.MAX_VALUE,
      -Long.MAX_VALUE,
      Long.MAX_VALUE - 1,
      4_611_686_018_427_387_904L,
    )

  private fun randomSignificand(): Long {
    if (random.nextInt(6) == 0) {
      return specialSignificands.random(random)
    }
    val digits = random.nextInt(1, 20)
    var value = 0L
    repeat(digits) {
      val next = value * 10 + random.nextInt(10)
      if (next < value) return value // overflow guard
      value = next
    }
    if (value == Long.MIN_VALUE) {
      value = Long.MAX_VALUE
    }
    return if (random.nextBoolean()) value else -value
  }

  /**
   * Exponents stay modest here because the BigInteger paths align operands by materialising powers
   * of ten; extreme exponents are covered separately by [extremeExponentsMatch].
   */
  private fun randomExponent(): Long {
    return when (random.nextInt(10)) {
      0 -> random.nextLong(-60, 60)
      else -> random.nextLong(-22, 22)
    }
  }

  private fun build(significand: Long, exponent: Long, mode: DecimalMode?): BigDecimal =
    BigDecimal.fromLongWithExponent(significand, exponent, mode)

  private fun assertSameValue(expected: BigDecimal, actual: BigDecimal, context: () -> String) {
    assertEquals(expected.exponent, actual.exponent, "exponent: ${context()}")
    assertEquals(expected.significand, actual.significand, "significand: ${context()}")
    assertEquals(expected.precision, actual.precision, "precision: ${context()}")
    assertEquals(expected.decimalMode, actual.decimalMode, "decimalMode: ${context()}")
    assertEquals(expected.toString(), actual.toString(), "toString: ${context()}")
    if (expected.exponent in -10_000L..10_000L) {
      assertEquals(
        expected.toStringExpanded(),
        actual.toStringExpanded(),
        "toStringExpanded: ${context()}",
      )
    }
    assertEquals(expected.hashCode(), actual.hashCode(), "hashCode: ${context()}")
    assertEquals(0, expected.compareTo(actual), "compareTo: ${context()}")
  }

  private sealed class Outcome {
    data class Value(val value: Any?) : Outcome()

    data class Failure(val type: String, val message: String?) : Outcome()
  }

  private inline fun outcomeOf(block: () -> Any?): Outcome {
    return try {
      Outcome.Value(block())
    } catch (e: Throwable) {
      Outcome.Failure(e::class.simpleName ?: "?", e.message)
    }
  }

  private fun assertSameOutcome(expected: Outcome, actual: Outcome, context: () -> String) {
    when (expected) {
      is Outcome.Failure -> assertEquals(expected, actual, "exception: ${context()}")
      is Outcome.Value -> {
        if (actual !is Outcome.Value) {
          fail("expected value ${expected.value} but got $actual: ${context()}")
        }
        val e = expected.value
        val a = actual.value
        when (e) {
          is BigDecimal -> assertSameValue(e, a as BigDecimal, context)
          is Pair<*, *> -> {
            val ap = a as Pair<*, *>
            assertSameValue(e.first as BigDecimal, ap.first as BigDecimal) { "first: ${context()}" }
            assertSameValue(e.second as BigDecimal, ap.second as BigDecimal) {
              "second: ${context()}"
            }
          }
          else -> assertEquals(e, a, context())
        }
      }
    }
  }

  /** Runs [op] on every combination of compact and inflated operands and compares outcomes. */
  private fun checkBinary(
    first: BigDecimal,
    second: BigDecimal,
    name: String,
    op: (BigDecimal, BigDecimal) -> Any?,
  ) {
    val firstInflated = first.inflatedForTesting()
    val secondInflated = second.inflatedForTesting()
    val expected = outcomeOf { op(firstInflated, secondInflated) }
    val context = { "$name($first [${first.decimalMode}], $second [${second.decimalMode}])" }
    assertSameOutcome(expected, outcomeOf { op(first, second) }, context)
    assertSameOutcome(expected, outcomeOf { op(first, secondInflated) }, context)
    assertSameOutcome(expected, outcomeOf { op(firstInflated, second) }, context)
  }

  private fun checkUnary(value: BigDecimal, name: String, op: (BigDecimal) -> Any?) {
    val inflated = value.inflatedForTesting()
    val expected = outcomeOf { op(inflated) }
    assertSameOutcome(expected, outcomeOf { op(value) }) { "$name($value [${value.decimalMode}])" }
  }

  @Test
  fun constructionMatchesInflatedForm() {
    repeat(2_000) {
      val significand = randomSignificand()
      val exponent = randomExponent()
      val mode = modes.random(random)
      val outcome = outcomeOf { build(significand, exponent, mode) }
      if (outcome is Outcome.Value) {
        val value = outcome.value as BigDecimal
        val inflated = value.inflatedForTesting()
        assertSameValue(inflated, value) { "build($significand, $exponent, $mode)" }
      }
    }
  }

  @Test
  fun smallValuesAreCompact() {
    assertTrue(BigDecimal.ZERO.isCompactForTesting())
    assertTrue(BigDecimal.TEN.isCompactForTesting())
    assertTrue(BigDecimal.parseString("1234.56").isCompactForTesting())
    assertTrue(BigDecimal.parseString("-0.000001234").isCompactForTesting())
    assertTrue(BigDecimal.parseString("1.5E+300").isCompactForTesting())
    assertTrue(BigDecimal.fromLong(Long.MAX_VALUE).isCompactForTesting())
    assertTrue(BigDecimal.fromLong(Long.MIN_VALUE).isCompactForTesting().not())
    assertTrue(
      (BigDecimal.parseString("1234.56") * BigDecimal.parseString("78.901")).isCompactForTesting()
    )
    assertTrue(BigDecimal.parseString("12345678901234567890").isCompactForTesting().not())
    assertTrue(
      BigDecimal.parseStringWithMode("1234.56", DecimalMode.US_CURRENCY).isCompactForTesting()
    )
  }

  @Test
  fun inflatedResultsDeflateWhenTheyFit() {
    val big = BigDecimal.parseString("1E+30")
    val small =
      BigDecimal.parseString("1E+30") - BigDecimal.parseString("999999999999999999999999999999")
    assertEquals(BigDecimal.ONE, small)
    assertTrue(small.isCompactForTesting())
    assertTrue((big / big).isCompactForTesting())
  }

  @Test
  fun binaryOperationsMatchInflatedForm() {
    repeat(500) {
      val mode = modes.random(random)
      val first =
        (outcomeOf { build(randomSignificand(), randomExponent(), mode) } as? Outcome.Value)?.value
          as? BigDecimal ?: return@repeat
      val second =
        (outcomeOf { build(randomSignificand(), randomExponent(), mode) } as? Outcome.Value)?.value
          as? BigDecimal ?: return@repeat
      val opMode = modes.random(random)
      checkBinary(first, second, "plus") { a, b -> a + b }
      checkBinary(first, second, "minus") { a, b -> a - b }
      checkBinary(first, second, "times") { a, b -> a * b }
      checkBinary(first, second, "div") { a, b -> a / b }
      checkBinary(first, second, "add[$opMode]") { a, b -> a.add(b, opMode) }
      checkBinary(first, second, "subtract[$opMode]") { a, b -> a.subtract(b, opMode) }
      checkBinary(first, second, "multiply[$opMode]") { a, b -> a.multiply(b, opMode) }
      checkBinary(first, second, "divide[$opMode]") { a, b -> a.divide(b, opMode) }
      checkBinary(first, second, "compareTo") { a, b -> a.compareTo(b) }
      checkBinary(first, second, "equals") { a, b -> a == b }
      checkBinary(first, second, "divrem") { a, b -> a divrem b }
      checkBinary(first, second, "rem") { a, b -> a % b }
    }
  }

  @Test
  fun unaryOperationsMatchInflatedForm() {
    repeat(500) {
      val mode = modes.random(random)
      val value =
        (outcomeOf { build(randomSignificand(), randomExponent(), mode) } as? Outcome.Value)?.value
          as? BigDecimal ?: return@repeat
      val roundingMode = roundingModes.random(random)
      val digits = random.nextLong(1, 25)
      val afterPoint = random.nextLong(0, 25)
      val roundMode = modes.random(random)
      checkUnary(value, "negate") { it.negate() }
      checkUnary(value, "unaryMinus") { -it }
      checkUnary(value, "abs") { it.abs() }
      checkUnary(value, "signum") { it.signum() }
      checkUnary(value, "isZero") { it.isZero() }
      checkUnary(value, "floor") { it.floor() }
      checkUnary(value, "ceil") { it.ceil() }
      checkUnary(value, "inc") { it.inc() }
      checkUnary(value, "dec") { it.dec() }
      checkUnary(value, "toBigInteger") { it.toBigInteger() }
      checkUnary(value, "isWholeNumber") { it.isWholeNumber() }
      checkUnary(value, "longValue") { it.longValue(false) }
      checkUnary(value, "longValueExact") { it.longValue(true) }
      checkUnary(value, "intValue") { it.intValue(false) }
      checkUnary(value, "intValueExact") { it.intValue(true) }
      checkUnary(value, "shortValue") { it.shortValue(false) }
      checkUnary(value, "shortValueExact") { it.shortValue(true) }
      checkUnary(value, "byteValue") { it.byteValue(false) }
      checkUnary(value, "byteValueExact") { it.byteValue(true) }
      checkUnary(value, "ulongValue") { it.ulongValue(false) }
      checkUnary(value, "ulongValueExact") { it.ulongValue(true) }
      checkUnary(value, "uintValue") { it.uintValue(false) }
      checkUnary(value, "uintValueExact") { it.uintValue(true) }
      checkUnary(value, "ushortValue") { it.ushortValue(false) }
      checkUnary(value, "ushortValueExact") { it.ushortValue(true) }
      checkUnary(value, "ubyteValue") { it.ubyteValue(false) }
      checkUnary(value, "ubyteValueExact") { it.ubyteValue(true) }
      checkUnary(value, "doubleValue") { it.doubleValue(false) }
      checkUnary(value, "floatValue") { it.floatValue(false) }
      checkUnary(value, "numberOfDecimalDigits") { it.numberOfDecimalDigits() }
      checkUnary(value, "toPlainString") { it.toPlainString() }
      checkUnary(value, "toStringExpanded") { it.toStringExpanded() }
      checkUnary(value, "toString") { it.toString() }
      checkUnary(value, "hashCode") { it.hashCode() }
      checkUnary(value, "roundSignificand[$roundMode]") { it.roundSignificand(roundMode) }
      checkUnary(value, "roundToDigitPosition[$digits,$roundingMode]") {
        it.roundToDigitPosition(digits, roundingMode)
      }
      checkUnary(value, "roundToDigitPositionAfterDecimalPoint[$afterPoint,$roundingMode]") {
        it.roundToDigitPositionAfterDecimalPoint(afterPoint, roundingMode)
      }
      checkUnary(value, "scale(2)") { it.scale(2) }
      checkUnary(value, "scale(0)") { it.scale(0) }
      checkUnary(value, "removeScale") { it.removeScale() }
      checkUnary(value, "moveDecimalPoint") { it.moveDecimalPoint(3) }
      checkUnary(value, "pow(3)") { it.pow(3) }
      checkUnary(value, "fromBigDecimal[$roundMode]") {
        BigDecimal.fromBigDecimal(it, roundMode)
      }
      checkUnary(value, "roundTrip") { BigDecimal.parseString(it.toString()) }
      checkUnary(value, "roundTripExpanded") { BigDecimal.parseString(it.toStringExpanded()) }
    }
  }

  @Test
  fun extremeExponentsMatch() {
    repeat(500) {
      val exponent = random.nextLong(-1_000_000_000L, 1_000_000_000L)
      val value = build(randomSignificand(), exponent, null)
      val other = build(randomSignificand(), exponent + random.nextLong(-1, 2), null)
      checkUnary(value, "negate") { it.negate() }
      checkUnary(value, "abs") { it.abs() }
      checkUnary(value, "signum") { it.signum() }
      checkUnary(value, "toString") { it.toString() }
      checkUnary(value, "hashCode") { it.hashCode() }
      checkUnary(value, "numberOfDecimalDigits") { it.numberOfDecimalDigits() }
      checkUnary(value, "moveDecimalPoint") { it.moveDecimalPoint(-7) }
      checkBinary(value, other, "compareTo") { a, b -> a.compareTo(b) }
      checkBinary(value, other, "equals") { a, b -> a == b }
      checkBinary(value, other, "times") { a, b -> a * b }
    }
  }

  private fun randomDecimalText(): String {
    val sb = StringBuilder()
    when (random.nextInt(4)) {
      0 -> sb.append('-')
      1 -> sb.append('+')
      else -> {}
    }
    val leftDigits = random.nextInt(0, 12)
    repeat(leftDigits) { sb.append(if (random.nextInt(3) == 0) '0' else '0' + random.nextInt(10)) }
    if (random.nextInt(5) != 0) {
      sb.append('.')
      val rightDigits = random.nextInt(0, 12)
      repeat(rightDigits) {
        sb.append(if (random.nextInt(3) == 0) '0' else '0' + random.nextInt(10))
      }
    }
    if (random.nextInt(25) == 0) {
      // Occasionally produce something the fast parser must hand back to the general one.
      sb.insert(random.nextInt(sb.length + 1), listOf('.', 'x', ' ').random(random))
    }
    return sb.toString()
  }

  @Test
  fun singlePassParserMatchesGeneralParser() {
    val fixed =
      listOf(
        "0",
        "-0",
        "+0",
        "0.0",
        "00",
        "000.000",
        ".5",
        "-.5",
        "5.",
        "0.",
        ".",
        "-",
        "+",
        "",
        "007",
        "-007.0500",
        "123456789012345678",
        "1234567890123456789",
        "0.000000000000000001",
        "100",
        "100.",
        "100.00",
        "0.100",
        "-0.0",
        "1.2.3",
        "12a",
        " 12",
        "12 ",
        "1E5",
        "+-1",
      )
    val inputs = fixed + List(3_000) { randomDecimalText() }
    for (text in inputs) {
      for (mode in modes) {
        BigDecimal.compactParseEnabledForTesting = false
        val expected =
          try {
            outcomeOf { BigDecimal.parseStringWithMode(text, mode) }
          } finally {
            BigDecimal.compactParseEnabledForTesting = true
          }
        val actual = outcomeOf { BigDecimal.parseStringWithMode(text, mode) }
        assertSameOutcome(expected, actual) { "parse(\"$text\", $mode)" }
      }
    }
  }

  @Test
  fun factoriesMatchBigIntegerFactories() {
    repeat(2_000) {
      val significand = randomSignificand()
      val mode = modes.random(random)
      val viaBigInteger = outcomeOf {
        BigDecimal.fromBigInteger(BigInteger.fromLong(significand), mode)
      }
      val viaLong = outcomeOf { BigDecimal.fromLong(significand, mode) }
      assertSameOutcome(viaBigInteger, viaLong) { "fromLong($significand, $mode)" }
      val int = significand.toInt()
      assertSameOutcome(
        outcomeOf { BigDecimal.fromBigInteger(BigInteger.fromInt(int), mode) },
        outcomeOf { BigDecimal.fromInt(int, mode) },
      ) {
        "fromInt($int, $mode)"
      }
      val exponent = randomExponent()
      assertSameOutcome(
        outcomeOf {
          BigDecimal.fromBigIntegerWithExponent(BigInteger.fromLong(significand), exponent, mode)
        },
        outcomeOf { BigDecimal.fromLongWithExponent(significand, exponent, mode) },
      ) {
        "fromLongWithExponent($significand, $exponent, $mode)"
      }
    }
  }

  @Test
  fun parsingMatchesAcrossCompactBoundary() {
    // The same numeric value written with 18 digits (compact parse) and 19+ digits (BigInteger
    // parse) must compare equal and print identically in expanded form.
    val cases =
      listOf(
        "123456789012345678" to "0123456789012345678",
        "1.23456789012345678" to "1.234567890123456780",
        "-0.000123456789012345678" to "-0.0001234567890123456780",
        "9.99999999999999999E+5" to "999999999999999999E-12",
        "1E+25" to "10000000000000000000000000",
      )
    for ((compactText, longText) in cases) {
      val compact = BigDecimal.parseString(compactText)
      val long = BigDecimal.parseString(longText)
      assertEquals(0, compact.compareTo(long), "$compactText vs $longText")
      assertEquals(compact.toStringExpanded(), long.toStringExpanded(), "$compactText vs $longText")
      assertEquals(compact.hashCode(), long.hashCode(), "hash $compactText vs $longText")
    }
  }
}
