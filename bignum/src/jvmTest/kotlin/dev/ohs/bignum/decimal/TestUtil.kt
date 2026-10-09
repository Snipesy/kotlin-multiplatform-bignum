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

import kotlin.random.Random
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking

fun multipleTestLauncher(test: (random: Random) -> Unit) {
  val dispatcher = newFixedThreadPoolContext(32, "Division and rounds")
  val scope = CoroutineScope(dispatcher)
  val seed = 1
  val random = Random(seed)
  val jobList: MutableList<Job> = mutableListOf()
  val modes = java.math.RoundingMode.values().filterNot { it == java.math.RoundingMode.UNNECESSARY }
  for (i in 1..1_000_000) {
    jobList.add(
      scope.launch {
        test(random)
      }
    )
  }
  runBlocking {
    jobList.forEach {
      if (it.isCancelled) {
        fail("Some of the tests failed")
      }
      it.join()
    }
  }
}
