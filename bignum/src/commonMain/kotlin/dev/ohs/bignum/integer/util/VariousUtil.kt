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

package dev.ohs.bignum.integer.util

/** Created by Ugljesa Jovanovic ugljesa.jovanovic@ionspin.com on 17-Jun-2020 */
fun Array<UByte>.hexColumsPrint(chunk: Int = 16) {
  val printout = this.map { it.toString(16).padStart(2, '0') }.chunked(chunk)
  printout.forEach { println(it.joinToString(separator = " ") { it.uppercase() }) }
}

fun UByteArray.hexColumsPrint(chunk: Int = 16) {
  val printout = this.map { it.toString(16).padStart(2, '0') }.chunked(chunk)
  printout.forEach { println(it.joinToString(separator = " ") { it.uppercase() }) }
}

infix operator fun Char.times(count: Int): String {
  val stringBuilder = StringBuilder()
  for (i in 0 until count) {
    stringBuilder.append(this)
  }
  return stringBuilder.toString()
}

infix operator fun Char.times(count: Long): String {
  val stringBuilder = StringBuilder()
  for (i in 0 until count) {
    stringBuilder.append(this)
  }
  return stringBuilder.toString()
}
