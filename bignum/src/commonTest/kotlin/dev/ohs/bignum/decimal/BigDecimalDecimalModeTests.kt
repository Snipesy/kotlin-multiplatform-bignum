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
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class BigDecimalDecimalModeTests {

  @Test
  fun testInvalidPrecision() {
    assertFailsWith(ArithmeticException::class) {
      1.toBigDecimal(decimalMode = DecimalMode(-1))
    }
  }

  @Test
  fun testModePreservation() = runTest {
    run {
      val a =
        BigDecimal.fromInt(1, DecimalMode(scale = 5, roundingMode = RoundingMode.AWAY_FROM_ZERO))
      val negated = -a
      assertEquals(a.decimalMode, negated.decimalMode)
    }

    run {
      val a =
        BigDecimal.fromInt(1, DecimalMode(scale = 5, roundingMode = RoundingMode.AWAY_FROM_ZERO))
      val absolute = a.abs()
      assertEquals(a.decimalMode, absolute.decimalMode)
    }

    run {
      val a =
        BigDecimal.fromInt(1, DecimalMode(scale = 5, roundingMode = RoundingMode.AWAY_FROM_ZERO))
      val negated = a.negate()
      assertEquals(a.decimalMode, negated.decimalMode)
    }
  }
}
