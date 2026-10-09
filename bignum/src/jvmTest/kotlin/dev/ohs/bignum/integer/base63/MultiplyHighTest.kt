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

package dev.ohs.bignum.integer.base63

import dev.ohs.bignum.integer.base63.array.multiplyHigh
import dev.ohs.bignum.integer.base63.array.multiplyHighPortable
import java.math.BigInteger
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.Test
import kotlin.test.assertEquals

/** Both the platform and the portable high-word product must agree with java.math. */
class MultiplyHighTest {
  private fun check(first: ULong, second: ULong) {
    val expected =
      BigInteger(first.toString()).multiply(BigInteger(second.toString())).shiftRight(64).toLong()
    assertEquals(expected.toULong(), multiplyHigh(first, second), "intrinsic $first * $second")
    assertEquals(
      expected.toULong(),
      multiplyHighPortable(first, second),
      "portable $first * $second",
    )
  }

  @Test
  fun edgeWords() {
    val max = ULong.MAX_VALUE shr 1
    for (a in listOf(0UL, 1UL, 2UL, 0xFFFFFFFFUL, 0x100000000UL, max - 1UL, max)) {
      for (b in listOf(0UL, 1UL, 3UL, 0xFFFFFFFFUL, 0x100000000UL, max)) {
        check(a, b)
      }
    }
  }

  @Test
  fun randomWords() {
    val random = Random(7)
    repeat(200_000) { check(random.nextULong() shr 1, random.nextULong() shr 1) }
  }
}
