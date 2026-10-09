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

package dev.ohs.bignum.integer.base63.array

/**
 * The high 64 bits of the 128-bit product of two words that are both below 2^63, which every limb
 * of the 63-bit arithmetic is. The low 64 bits are the ordinary wrapping product. On the JVM this
 * is an intrinsic; elsewhere it falls back to [multiplyHighPortable].
 */
internal expect fun multiplyHigh(first: ULong, second: ULong): ULong

/** [multiplyHigh] built from four 32-bit products; valid for any inputs below 2^63. */
internal fun multiplyHighPortable(first: ULong, second: ULong): ULong {
  val firstLow = first and 0xFFFFFFFFUL
  val firstHigh = first shr 32
  val secondLow = second and 0xFFFFFFFFUL
  val secondHigh = second shr 32
  val lowProduct = firstLow * secondLow
  // Both cross products are below 2^63 and the carried term below 2^32, so this sum fits.
  val middle = firstHigh * secondLow + firstLow * secondHigh + (lowProduct shr 32)
  return firstHigh * secondHigh + (middle shr 32)
}
