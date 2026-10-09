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
import dev.ohs.bignum.decimal.DecimalMode
import dev.ohs.bignum.integer.BigInteger
import dev.ohs.bignum.serialization.kotlinx.bigdecimal.BigDecimalArraySerializer
import dev.ohs.bignum.serialization.kotlinx.bigdecimal.BigDecimalHumanReadableSerializer
import dev.ohs.bignum.serialization.kotlinx.bigdecimal.DecimalModeSerializer
import dev.ohs.bignum.serialization.kotlinx.biginteger.BigIntegerArraySerializer
import dev.ohs.bignum.serialization.kotlinx.biginteger.BigIntegerHumanReadableSerializer
import kotlinx.serialization.modules.SerializersModule

/** Created by Ugljesa Jovanovic ugljesa.jovanovic@ionspin.com on 11-Jul-2021 */
val humanReadableSerializerModule = SerializersModule {
  contextual(BigInteger::class, BigIntegerHumanReadableSerializer)
  contextual(BigDecimal::class, BigDecimalHumanReadableSerializer)
  contextual(DecimalMode::class, DecimalModeSerializer)
}

val arrayBasedSerializerModule = SerializersModule {
  contextual(BigInteger::class, BigIntegerArraySerializer)
  contextual(BigDecimal::class, BigDecimalArraySerializer)
  contextual(DecimalMode::class, DecimalModeSerializer)
}
