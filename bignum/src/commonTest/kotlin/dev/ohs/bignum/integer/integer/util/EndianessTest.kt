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

package dev.ohs.bignum.integer.integer.util

import dev.ohs.bignum.integer.util.mirrorBytes
import kotlin.test.Test
import kotlin.test.assertTrue

/** Created by Ugljesa Jovanovic ugljesa.jovanovic@ionspin.com on 14-Jul-2020 */
class EndianessTest {

  @Test
  fun testBigToLittle() {
    assertTrue {
      val source =
        ubyteArrayOf(
          0x00U,
          0x11U,
          0x22U,
          0x33U,
          0x44U,
          0x55U,
          0x66U,
          0x77U,
          0x88U,
          0x99U,
          0xAAU,
          0xBBU,
          0xCCU,
          0xDDU,
          0xEEU,
          0xFFU,
        )
      val expected =
        ubyteArrayOf(
          0x33U,
          0x22U,
          0x11U,
          0x00U,
          0x44U,
          0x55U,
          0x66U,
          0x77U,
          0x88U,
          0x99U,
          0xAAU,
          0xBBU,
          0xCCU,
          0xDDU,
          0xEEU,
          0xFFU,
        )
      val target = source.copyOf()
      mirrorBytes(source, 0, 4, target, 0)
      expected.contentEquals(target)
    }

    assertTrue {
      val source =
        ubyteArrayOf(
          0x00U,
          0x11U,
          0x22U,
          0x33U,
          0x44U,
          0x55U,
          0x66U,
          0x77U,
          0x88U,
          0x99U,
          0xAAU,
          0xBBU,
          0xCCU,
          0xDDU,
          0xEEU,
          0xFFU,
        )
      val expected =
        ubyteArrayOf(
          0x00U,
          0x11U,
          0x22U,
          0x33U,
          0xBBU,
          0xAAU,
          0x99U,
          0x88U,
          0x77U,
          0x66U,
          0x55U,
          0x44U,
          0xCCU,
          0xDDU,
          0xEEU,
          0xFFU,
        )
      val target = source.copyOf()
      mirrorBytes(source, 4, 12, target, 4)
      expected.contentEquals(target)
    }

    assertTrue {
      val source =
        ubyteArrayOf(
          0x00U,
          0x11U,
          0x22U,
          0x33U,
          0x44U,
          0x55U,
          0x66U,
          0x77U,
          0x88U,
          0x99U,
          0xAAU,
          0xBBU,
          0xCCU,
          0xDDU,
          0xEEU,
          0xFFU,
        )
      val expected =
        ubyteArrayOf(
          0xBBU,
          0xAAU,
          0x99U,
          0x88U,
          0x77U,
          0x66U,
          0x55U,
          0x44U,
          0x00U,
          0x00U,
          0x00U,
          0x00U,
          0x00U,
          0x00U,
          0x00U,
          0x00U,
        )
      val target = UByteArray(16)
      mirrorBytes(source, 4, 12, target, 0)
      expected.contentEquals(target)
    }
  }

  @Test
  fun testLittleToBig() {
    assertTrue {
      val source =
        ubyteArrayOf(
          0x33U,
          0x22U,
          0x11U,
          0x00U,
          0x44U,
          0x55U,
          0x66U,
          0x77U,
          0x88U,
          0x99U,
          0xAAU,
          0xBBU,
          0xCCU,
          0xDDU,
          0xEEU,
          0xFFU,
        )
      val expected =
        ubyteArrayOf(
          0x00U,
          0x11U,
          0x22U,
          0x33U,
          0x44U,
          0x55U,
          0x66U,
          0x77U,
          0x88U,
          0x99U,
          0xAAU,
          0xBBU,
          0xCCU,
          0xDDU,
          0xEEU,
          0xFFU,
        )
      val target = source.copyOf()
      mirrorBytes(source, 0, 4, target, 0)
      expected.contentEquals(target)
    }

    assertTrue {
      val source =
        ubyteArrayOf(
          0x00U,
          0x11U,
          0x22U,
          0x33U,
          0xBBU,
          0xAAU,
          0x99U,
          0x88U,
          0x77U,
          0x66U,
          0x55U,
          0x44U,
          0xCCU,
          0xDDU,
          0xEEU,
          0xFFU,
        )
      val expected =
        ubyteArrayOf(
          0x00U,
          0x11U,
          0x22U,
          0x33U,
          0x44U,
          0x55U,
          0x66U,
          0x77U,
          0x88U,
          0x99U,
          0xAAU,
          0xBBU,
          0xCCU,
          0xDDU,
          0xEEU,
          0xFFU,
        )
      val target = source.copyOf()
      mirrorBytes(source, 4, 12, target, 4)
      expected.contentEquals(target)
    }

    assertTrue {
      val source =
        ubyteArrayOf(
          0x00U,
          0x11U,
          0x22U,
          0x33U,
          0x44U,
          0x55U,
          0x66U,
          0x77U,
          0x88U,
          0x99U,
          0xAAU,
          0xBBU,
          0xCCU,
          0xDDU,
          0xEEU,
          0xFFU,
        )
      val expected =
        ubyteArrayOf(
          0xBBU,
          0xAAU,
          0x99U,
          0x88U,
          0x77U,
          0x66U,
          0x55U,
          0x44U,
          0x00U,
          0x00U,
          0x00U,
          0x00U,
          0x00U,
          0x00U,
          0x00U,
          0x00U,
        )
      val target = UByteArray(16)
      mirrorBytes(source, 4, 12, target, 0)
      expected.contentEquals(target)
    }
  }
}
