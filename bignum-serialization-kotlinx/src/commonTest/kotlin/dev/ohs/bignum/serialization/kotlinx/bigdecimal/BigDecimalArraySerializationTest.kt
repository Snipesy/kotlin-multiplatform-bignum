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

package dev.ohs.bignum.serialization.kotlinx.bigdecimal

import dev.ohs.bignum.decimal.BigDecimal
import dev.ohs.bignum.decimal.DecimalMode
import dev.ohs.bignum.decimal.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Created by Ugljesa Jovanovic ugljesa.jovanovic@ionspin.com on 04-Jul-2021 */
class BigDecimalArraySerializationTest {

  @Test
  fun testSerialization() {
    val testBigDecimal = BigDecimal.parseString("1.000000000020000000000300000000004")
    val json = Json {
      serializersModule = bigDecimalArraySerializerModule
    }
    val serialized = json.encodeToString(testBigDecimal)
    println(serialized)
    val deserialized = json.decodeFromString<BigDecimal>(serialized)
    assertEquals(testBigDecimal, deserialized)
  }

  @Test
  fun testSerializationWithDecimalMode() {
    val withoutMode = BigDecimal.parseStringWithMode("1.000000000020000000000300000000004")
    val testBigDecimal =
      BigDecimal.fromBigDecimal(withoutMode, DecimalMode(13, RoundingMode.ROUND_HALF_TO_ODD))
    val json = Json {
      serializersModule = bigDecimalArraySerializerModule
    }
    val serialized = json.encodeToString(testBigDecimal)
    println(serialized)
    val deserialized = json.decodeFromString<BigDecimal>(serialized)
    assertEquals(testBigDecimal, deserialized)
  }

  @Serializable
  data class BigDecimalArraySerializtionTestData(
    @Contextual val a: BigDecimal,
    @Contextual val b: BigDecimal,
  )

  @Test
  fun testSomething() {
    val a = BigDecimal.parseString("1.000000000020000000000300000000004")
    val b = BigDecimal.parseString("-1.000000000020000000000300000000004")
    val testObject = BigDecimalArraySerializtionTestData(a, b)
    val json = Json {
      serializersModule = bigDecimalArraySerializerModule
    }
    val serialized = json.encodeToString(testObject)
    println(serialized)
  }
}
