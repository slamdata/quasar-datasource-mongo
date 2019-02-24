/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.physical.mongo

import slamdata.Predef._

import monocle.Prism

import org.mongodb.scala._

import quasar.physical.mongo.{MongoExpression => E}
import quasar.{RenderTree, NonTerminal, Terminal}, RenderTree.ops._

trait Aggregator {
  def toDocument: Document
}

object Aggregator {
  final case class Project(obj: MongoExpression) extends Aggregator {
    def toDocument: Document = Document(s"$$project" -> obj.toBsonValue)
  }

  final case class Unwind(path: E.Projection, includeArrayIndex: String) extends Aggregator {
    def toDocument: Document =
      Document(s"$$unwind" -> Document(
        "path" -> E.String("$" ++ path.toKey).toBsonValue,
        "includeArrayIndex" -> includeArrayIndex,
        "preserveNullAndEmptyArrays" -> true))
  }

  final case class Filter(obj: E.Object) extends Aggregator {
    def toDocument: Document =
      Document("$match" -> obj.toBsonValue)
  }

  final case class NotNull(key: String) extends Aggregator {
    def toDocument: Document =
      Filter(E.Object(key -> E.Object("$ne" -> E.Null))).toDocument
  }

  def notNull: Prism[Aggregator, String] =
    Prism.partial[Aggregator, String] {
      case NotNull(k) => k
    } ( x => NotNull(x) )

  def filter: Prism[Aggregator, E.Object] =
    Prism.partial[Aggregator, E.Object] {
      case Filter(obj) => obj
    } ( x => Filter(x) )

  def unwind: Prism[Aggregator, (E.Projection, String)] =
    Prism.partial[Aggregator, (E.Projection, String)] {
      case Unwind(p, i) => (p, i)
    } { case (p, i) => Unwind(p, i) }

  def project: Prism[Aggregator, MongoExpression] =
    Prism.partial[Aggregator, MongoExpression] {
      case Project(obj) => obj
    } (x => Project(x))

  implicit val renderTreeAggregator: RenderTree[Aggregator] = RenderTree.make {
    case Project(obj) => NonTerminal(List("Project"), None, List((obj: MongoExpression).render))
    case Unwind(path, arrayIndex) => NonTerminal(List("Unwind"), Some(arrayIndex), List((path: MongoExpression).render))
    case Filter(obj) => NonTerminal(List("Project"), None, List((obj: MongoExpression).render))
    case NotNull(str) => Terminal(List("NotNull"), Some(str))
  }
}