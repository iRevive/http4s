package org.http4s
package rho

import org.http4s.rho.bits._
import org.http4s.server.{ Service, HttpService }

import org.log4s.getLogger
import shapeless.HList
import scalaz.concurrent.Task

trait RhoService extends bits.MethodAliases
                    with bits.ResponseGeneratorInstances {

  final protected val logger = getLogger

  private var __tree = PathTree()

  protected def append[T <: HList](route: RhoRoute[T]): Unit =
    __tree = __tree.appendRoute(route)

  implicit protected val compilerSrvc = new CompileService[Action[_]] {
    override def compile(route: RhoRoute[_ <: HList]): Action[_] = {
      append(route)
      route.action
    }
  }

  private def findRoute(req: Request): Task[Option[Response]] = {
    logger.trace(s"Request: ${req.method}:${req.uri}")
    val routeResult: RouteResult[Task[Response]] = __tree.getResult(req)
    routeResult match {
      case NoMatch              => Task.now(None)
      case ParserSuccess(t)     => t.map(Some(_))
      case ParserFailure(s)     => onBadRequest(s).map(Some(_))
      case ValidationFailure(r) => r.map(Some(_))
    }
  }

  def toService: HttpService = Service.lift(findRoute)

  override def toString(): String = s"RhoService(${__tree.toString()})"

  private def attempt(f: () => Task[Response]): Task[Response] = {
    try f()
    catch { case t: Throwable => onError(t) }
  }

  private def onBadRequest(s: String): Task[Response] =
    genMessage(Status.BadRequest, s)

  def onError(t: Throwable): Task[Response] =
    genMessage(Status.InternalServerError, t.getMessage)

  private def genMessage(status: Status, reason: String): Task[Response] = {
    val w = EntityEncoder.stringEncoder
    w.toEntity(reason).map{ entity =>
      val hs = entity.length match {
        case Some(l) => w.headers.put(headers.`Content-Length`(l))
        case None    => w.headers
      }
      Response(status, body = entity.body, headers = hs)
    }
  }
}
