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

package dev.ohs.bignum.serialization.kotlinx.biginteger

import dev.ohs.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Created by Ugljesa Jovanovic ugljesa.jovanovic@ionspin.com on 04-Jul-2021 */
class ArraySerializationTest {

  @Test
  fun testSerialization() {
    run {
      val testBigInteger =
        BigInteger.parseString("1000000000000000000000000000002000000000000000000000000000003")
      val json = Json {
        serializersModule = bigIntegerArraySerializerModule
      }
      val serialized = json.encodeToString(testBigInteger)
      println(serialized)
      val deserialized = json.decodeFromString<BigInteger>(serialized)
      assertEquals(testBigInteger, deserialized)
    }
    run {
      val testBigInteger =
        BigInteger.parseString("-1000000000000000000000000000002000000000000000000000000000003")
      val json = Json {
        serializersModule = bigIntegerArraySerializerModule
      }
      val serialized = json.encodeToString(testBigInteger)
      println(serialized)
      val deserialized = json.decodeFromString<BigInteger>(serialized)
      assertEquals(testBigInteger, deserialized)
    }
  }

  @Serializable
  data class BigIntegerArraySerializtionTestData(
    @Contextual val a: BigInteger,
    @Contextual val b: BigInteger,
  )

  @Test
  fun testSomething() {
    val a = BigInteger.parseString("-1000000000000000000000000000002000000000000000000000000000003")
    val b = BigInteger.parseString("1000000000000000000000000000002000000000000000000000000000003")
    val testObject = BigIntegerArraySerializtionTestData(a, b)
    val json = Json {
      serializersModule = bigIntegerArraySerializerModule
    }
    val serialized = json.encodeToString(testObject)
    println(serialized)
  }
}
