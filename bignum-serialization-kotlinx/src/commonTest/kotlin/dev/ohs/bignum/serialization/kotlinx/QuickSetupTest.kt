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

package dev.ohs.bignum.serialization.kotlinx

import dev.ohs.bignum.decimal.BigDecimal
import dev.ohs.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Created by Ugljesa Jovanovic ugljesa.jovanovic@ionspin.com on 11-Jul-2021 */
class QuickSetupTest {

  val json = Json {
    serializersModule = arrayBasedSerializerModule // or humanReadableSerializerModule
  }

  @Serializable
  data class SomeDataHolder(
    @Contextual val bigInteger: BigInteger,
    @Contextual val bigDecimal: BigDecimal,
  )

  @Test
  fun serializeAndDeserialize() {
    val bigInt = BigInteger.parseString("12345678901234567890")
    val bigDecimal = BigDecimal.parseString("1.234E-200")
    val someData = SomeDataHolder(bigInt, bigDecimal)
    val serialized = json.encodeToString(someData)
    println(serialized)
    val deserialized = json.decodeFromString<SomeDataHolder>(serialized)
    assertEquals(someData, deserialized)
  }
}
