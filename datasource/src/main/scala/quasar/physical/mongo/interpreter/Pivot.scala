/*
 * Copyright 2020 Precog Data
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

package quasar.physical.mongo.interpreter

import slamdata.Predef._

import quasar.{ScalarStage, IdStatus}
import quasar.api.ColumnType
import quasar.contrib.iota._
import quasar.physical.mongo.Interpreter
import quasar.physical.mongo.expression._

import cats._
import cats.implicits._
import cats.mtl.MonadState
import cats.mtl.implicits._
import higherkindness.droste.Basis

object Pivot {
  def apply[F[_]: Monad: MonadInState, U: Basis[Expr, ?]]: Interpreter[F, U, ScalarStage.Pivot] =
    new Interpreter[F, U, ScalarStage.Pivot] {
      def apply(s: ScalarStage.Pivot): F[List[Pipeline[U]]] = s match { case ScalarStage.Pivot(status, vectorType) =>
        for {
          state <- MonadState[F, InterpretationState].get
          res = mkPipes(status, vectorType, state)
          _ <- focus[F]
        } yield res.map(_.map(Compiler.mapProjection[Expr, U, U](state.mapper.projection)))
      }
      def ensureArray(vectorType: ColumnType.Vector, key: String, undefined: U): Pipeline[U] ={
        val proj = o.projection(Projection(List()))
        Pipeline.Project(Map(key -> (vectorType match {
          case ColumnType.Object =>
            o.cond(
              o.or(List(o.not(o.eqx(List(o.typ(proj), o.str("object")))), o.eqx(List(proj, o.obj(Map()))))),
              o.array(List(o.obj(Map("k" -> undefined, "v" -> undefined)))),
              o.objectToArray(o.projection(Projection(List()))))
          case ColumnType.Array =>
            o.cond(
              o.or(List(o.not(o.eqx(List(o.typ(proj), o.str("array")))), o.eqx(List(proj, o.array(List()))))),
              o.array(List(undefined)),
              proj)
        })))
      }

      def mkValue(status: IdStatus, vectorType: ColumnType.Vector, unwinded: String, index: String, undefined: U): U = {
        val indexString = o.str("$".concat(index))
        val unwindString = o.str("$".concat(unwinded))
        val kString = o.str("$".concat(unwinded).concat(".k"))
        val vString = o.str("$".concat(unwinded).concat(".v"))
        vectorType match {
          case ColumnType.Array => status match {
            case IdStatus.IdOnly =>
              o.cond(
                o.eqx(List(unwindString, undefined)),
                undefined,
                indexString)
            case IdStatus.ExcludeId =>
              unwindString
            case IdStatus.IncludeId =>
              o.cond(
                o.eqx(List(unwindString, undefined)),
                undefined,
                o.array(List(indexString, unwindString)))
          }
          case ColumnType.Object => status match {
            case IdStatus.IdOnly => kString
            case IdStatus.ExcludeId => vString
            case IdStatus.IncludeId =>
              o.cond(
                o.eqx(List(vString, undefined)),
                undefined,
                o.array(List(kString, vString)))
          }
        }
      }
      def mkPipes(status: IdStatus, vectorType: ColumnType.Vector, state: InterpretationState): List[Pipeline[U]] = {
        val unwindKey = state.uniqueKey.concat("_unwind")
        val indexKey = state.uniqueKey.concat("_unwind_index")
        List(
          ensureArray(vectorType, unwindKey, missing[Expr, U](state.uniqueKey)),
          Pipeline.Unwind(unwindKey, indexKey),
          Pipeline.Project(Map(
            "_id" -> o.int(0),
            state.uniqueKey -> mkValue(status, vectorType, unwindKey, indexKey, missing[Expr, U](state.uniqueKey)))),
          Pipeline.Presented)
      }
    }
}


/*
object Pivot {
  def ensureArray(vectorType: ColumnType.Vector, key: String, undefined: Expr): Pipe = {
    val proj = O.steps(List())
    Pipeline.$project(Map(key -> (vectorType match {
      // if proj === {} or its type isn't "object" this emits [{k: missing, v: missing}]
      case ColumnType.Object =>
        O.$cond(
          O.$or(List(O.$not(O.$eq(List(O.$type(proj), O.string("object")))), O.$eq(List(proj, O.obj(Map()))))),
          O.array(List(O.obj(Map("k" -> undefined, "v" -> undefined)))),
          O.$objectToArray(O.steps(List())))
      // if proj === [] or its type isn't "array" this emits [missing]
      case ColumnType.Array =>
        O.$cond(
          O.$or(List(O.$not(O.$eq(List(O.$type(proj), O.string("array")))), O.$eq(List(proj, O.array(List()))))),
          O.array(List(undefined)),
          proj)
    })))
  }

  def mkValue(
      status: IdStatus,
      vectorType: ColumnType.Vector,
      unwinded: String,
      index: String,
      undefined: Expr)
      : Expr = {
    val indexString = O.string("$" concat index)
    val unwindString = O.string("$" concat unwinded)
    val kString = O.string("$" concat unwinded concat ".k")
    val vString = O.string("$" concat unwinded concat ".v")
    vectorType match {
      case ColumnType.Array => status match {
        // if we have `missing` in values then index has no sense, it's `missing` too
        case IdStatus.IdOnly =>
          O.$cond(
            O.$eq(List(unwindString, undefined)),
            undefined,
            indexString)
        case IdStatus.ExcludeId =>
          unwindString
        // the same as above, but we erase whole [id, val] instead of elements
        case IdStatus.IncludeId =>
          O.$cond(
            O.$eq(List(unwindString, undefined)),
            undefined,
            O.array(List(indexString, unwindString)))
      }
      case ColumnType.Object => status match {
        // no handling in kString, vString, they could be defined as missing in `ensureArray`
        case IdStatus.IdOnly => kString
        case IdStatus.ExcludeId => vString
        case IdStatus.IncludeId =>
          // if value is `missing` whole [id, val] is `missing`
          O.$cond(
            O.$eq(List(vString, undefined)),
            undefined,
            O.array(List(kString, vString)))
      }
    }
  }

  def mkPipes(
      status: IdStatus,
      vectorType: ColumnType.Vector,
      state: InterpretationState)
      : List[Pipe] = {
    val unwindKey = state.uniqueKey concat "_unwind"
    val indexKey = state.uniqueKey concat "_unwind_index"

    List(
      ensureArray(vectorType, unwindKey, missing(state.uniqueKey)),
      Pipeline.$unwind(unwindKey, indexKey),
      Pipeline.$project(Map(
        "_id" -> O.int(0),
        state.uniqueKey -> mkValue(status, vectorType, unwindKey, indexKey, missing(state.uniqueKey)))),
      Pipeline.Presented)
  }


  def apply[F[_]: MonadInState](status: IdStatus, vectorType: ColumnType.Vector): F[List[Pipe]] =
    for {
      state <- MonadState[F, InterpretationState].get
      res = mkPipes(status, vectorType, state)
      _ <- focus[F]
    } yield res map mapProjection(state.mapper)
}
 */
