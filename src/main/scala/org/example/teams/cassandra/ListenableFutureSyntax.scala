package org.example.teams.cassandra

import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}

import scala.concurrent.{Future, Promise}

/**
 * @author Denis Pakhomov.
 */
object ListenableFutureSyntax {

  implicit class ListenableFutureConverter[A](listenableFuture: ListenableFuture[A]) {
    def toScalaFuture: Future[A] = {

      val promise = Promise[A]()

      Futures.addCallback(listenableFuture, new FutureCallback[A] {
        override def onSuccess(result: A): Unit     = promise.success(result)
        override def onFailure(t: Throwable): Unit  = promise.failure(t)
      })

      promise.future
    }
  }

}
