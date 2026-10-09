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
import dev.ohs.bignum.integer.Sign
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.modules.SerializersModule

/** Created by Ugljesa Jovanovic ugljesa.jovanovic@ionspin.com on 04-Jul-2021 */
@OptIn(ExperimentalSerializationApi::class, ExperimentalUnsignedTypes::class)
object BigIntegerArraySerializer : KSerializer<BigInteger> {

  private val longArraySerializer = ArraySerializer(Long.serializer())

  override val descriptor: SerialDescriptor =
    buildClassSerialDescriptor("BigIntegerArray") {
      element("magnitude", listSerialDescriptor(ULong.serializer().descriptor))
      element<String>("sign")
    }

  override fun serialize(encoder: Encoder, value: BigInteger) {
    val array = value.getBackingArrayCopy()
    encoder.encodeStructure(descriptor) {
      encodeSerializableElement(
        descriptor,
        0,
        longArraySerializer,
        array.asLongArray().toTypedArray(),
      )
      encodeStringElement(descriptor, 1, value.getSign().name)
    }
  }

  override fun deserialize(decoder: Decoder): BigInteger {
    return decoder.decodeStructure(descriptor) {
      var array = emptyArray<Long>()
      var signString = ""
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          0 -> array = decodeSerializableElement(descriptor, 0, longArraySerializer)
          1 -> signString = decodeStringElement(descriptor, 1)
          CompositeDecoder.DECODE_DONE -> break
          else -> error("Unexpected index: $index")
        }
      }
      BigInteger.createFromWordArray(array.toLongArray().asULongArray(), Sign.valueOf(signString))
    }
  }
}

val bigIntegerArraySerializerModule = SerializersModule {
  contextual(BigInteger::class, BigIntegerArraySerializer)
}
