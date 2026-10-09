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
import dev.ohs.bignum.integer.toBigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class BigDecimalFactorialTest {

  @Test
  fun factorialZeroTest() {
    assertEquals(
      BigInteger.parseString("1"),
      0.toBigInteger().factorial(),
    )
  }

  @Test
  fun factorialOneTest() {
    assertEquals(
      BigInteger.parseString("1"),
      1.toBigInteger().factorial(),
    )
  }

  @Test
  fun factorialNegativeOneTest() {
    assertEquals(
      BigInteger.parseString("-1"),
      (-1).toBigInteger().factorial(),
    )
  }

  @Test
  fun factorialNegativeFiveTest() {
    assertEquals(
      BigInteger.parseString("-120"),
      (-5).toBigInteger().factorial(),
    )
  }

  @Test
  fun factorialTest() {
    assertEquals(
      BigInteger.parseString("2432902008176640000"),
      20.toBigInteger().factorial(),
    )
  }

  @Test
  fun factorialBigTest() {
    assertEquals(
      BigInteger.parseString("30414093201713378043612608166064768844377641568960512000000000000"),
      50.toBigInteger().factorial(),
    )
  }

  @Test
  fun factorialVeryBigTest() {
    assertEquals(
      BigInteger.parseString(
        "281710411438055027694947944226061159480056634330574206405101912752560026159795933451040286452340924018275123200000000000000000000"
      ),
      85.toBigInteger().factorial(),
    )
  }
}
