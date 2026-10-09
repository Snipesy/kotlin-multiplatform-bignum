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

import kotlin.test.assertEquals
import org.junit.Test

/** Created by Ugljesa Jovanovic ugljesa.jovanovic@ionspin.com on 09-May-2021 */
class ReportedIssuesTest {

  @Test
  fun testMod2() {

    val a = "15.5".toBigDecimal()
    val b = "360".toBigDecimal()

    assertEquals("15.5", ((b * 5 + a) % b).toStringExpanded())

    assertEquals("${-15.5 % 360.0}", (-a % b).toStringExpanded())
  }

  @Test
  fun multiplicationPrecisionLoss() {
    val a =
      "5.61"
        .toBigDecimal()
        .roundToDigitPositionAfterDecimalPoint(2, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)
    val b =
      "2.95"
        .toBigDecimal()
        .roundToDigitPositionAfterDecimalPoint(2, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)
    val aJava = a.toJavaBigDecimal()
    val bJava = b.toJavaBigDecimal()
    val javaRes = aJava.multiply(bJava).setScale(2, java.math.RoundingMode.HALF_UP)
    val res =
      a.multiply(b).roundToDigitPositionAfterDecimalPoint(2, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)
    assertEquals(res.toStringExpanded(), javaRes.toPlainString())
  }
}
