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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Comparison decides on sign and exponent before doing any arithmetic, so values whose exponents
 * differ by hundreds of millions compare without materialising 10^difference.
 */
class BigDecimalExtremeExponentComparisonTest {
  private val huge = BigDecimal.parseString("1E999999999")
  private val tiny = BigDecimal.parseString("1E-999999999")

  @Test
  fun hugeExponentsCompareAgainstOrdinaryValues() {
    assertTrue(huge > BigDecimal.ONE)
    assertTrue(BigDecimal.ONE < huge)
    assertFalse(huge == BigDecimal.ONE)
    assertTrue(tiny < BigDecimal.ONE)
    assertTrue(tiny > BigDecimal.ZERO)
    assertTrue(huge > tiny)
    assertEquals(1, huge.compareTo(BigDecimal.parseString("1E999999998")))
    assertEquals(-1, huge.compareTo(BigDecimal.parseString("2E999999999")))
  }

  @Test
  fun negativeHugeExponentsOrderByMagnitude() {
    val negativeHuge = -huge
    assertTrue(negativeHuge < BigDecimal.parseString("-1E5"))
    assertTrue(negativeHuge < -tiny)
    assertTrue(-tiny < BigDecimal.ZERO)
    assertTrue(negativeHuge < huge)
    assertEquals(-1, negativeHuge.compareTo(BigDecimal.parseString("-1E999999998")))
  }

  @Test
  fun equalValuesWithDifferentPrecisionAtHugeExponentsAreEqual() {
    val a = BigDecimal.parseString("2E1000000")
    val b = BigDecimal.parseString("20E999999")
    val c = BigDecimal.parseString("2.000E1000000")
    assertEquals(0, a.compareTo(b))
    assertTrue(a == b)
    assertTrue(a == c)
    assertEquals(a.hashCode(), b.hashCode())
    assertEquals(a.hashCode(), c.hashCode())
  }

  @Test
  fun sameExponentDifferentPrecisionOrdersBySignificand() {
    assertTrue(BigDecimal.parseString("1.5E3") > BigDecimal.parseString("1.49E3"))
    assertTrue(BigDecimal.parseString("1.49E3") < BigDecimal.parseString("1.5E3"))
    assertTrue(BigDecimal.parseString("-1.5E3") < BigDecimal.parseString("-1.49E3"))
    assertTrue(BigDecimal.parseString("1.0E5") == BigDecimal.parseString("1.00E5"))
    assertTrue(BigDecimal.parseString("123.456") > BigDecimal.parseString("123.4559"))
  }

  @Test
  fun zeroComparesBelowPositiveAndAboveNegative() {
    assertEquals(0, BigDecimal.ZERO.compareTo(BigDecimal.fromIntWithExponent(0, 41)))
    assertTrue(BigDecimal.ZERO < tiny)
    assertTrue(BigDecimal.ZERO > -tiny)
    assertTrue(BigDecimal.ZERO < huge)
    assertTrue(BigDecimal.ZERO > -huge)
  }
}
