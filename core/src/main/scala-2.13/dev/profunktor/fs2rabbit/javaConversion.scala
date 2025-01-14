/*
 * Copyright 2017-2019 ProfunKtor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.profunktor.fs2rabbit

import scala.collection.convert.{AsJavaExtensions, AsScalaExtensions}

// This exists purely for compatibility between Scala 2.13 and 2.12 since the
// Java conversions have been moved into a different package between the two,
// allowing us to have a single, consistent import everywhere else in this
// codebase across both 2.13 and 2.12.
object javaConversion extends AsJavaExtensions with AsScalaExtensions
