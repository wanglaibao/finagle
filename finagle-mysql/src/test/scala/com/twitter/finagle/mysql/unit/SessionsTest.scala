package com.twitter.finagle.mysql.unit

import com.twitter.conversions.time._
import com.twitter.finagle.mysql._
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.util.{Await, Awaitable, Future, Time}
import org.mockito.Matchers.any
import org.mockito.Mockito.{spy, times, verify}
import org.scalatest.FunSuite
import org.scalatest.mockito.MockitoSugar

class SessionsTest extends FunSuite with MockitoSugar {
  private[this] val sqlQuery = "SELECT * FROM FOO"

  private[this] def await[T](t: Awaitable[T]): T = Await.result(t, 5.seconds)

  test("sessions uses a single service") {
    val service = spy(new MockService())
    val factory = spy(new MockServiceFactory(service))
    val client = Client(factory, NullStatsReceiver, supportUnsigned = false)

    val result = client.session { session =>
      for {
        _ <- session.query(sqlQuery)
        _ <- session.query(sqlQuery)
      } yield "success"
    }

    assert(await(result) == "success")
    assert(
      service.requests ==
        List(
          sqlQuery,
          sqlQuery
        ).map(QueryRequest.apply _)
    )

    verify(factory, times(1)).apply()
    verify(factory, times(0)).close(any[Time])
    verify(service, times(1)).close(any[Time])
  }

  test("sessions with nested transactions uses a single service") {
    val service = spy(new MockService())
    val factory = spy(new MockServiceFactory(service))
    val client = Client(factory, NullStatsReceiver, supportUnsigned = false)

    val result = client.session { session =>
      for {
        _ <- session.query("LOCK TABLES FOO WRITE")
        r <- session.transaction { tx =>
          for {
            _ <- tx.query(sqlQuery)
            _ <- tx.query(sqlQuery)
          } yield "success"
        }
        _ <- session.query("UNLOCK TABLES")
      } yield r
    }

    assert(await(result) == "success")
    assert(
      service.requests ==
        List(
          "LOCK TABLES FOO WRITE",
          "START TRANSACTION",
          sqlQuery,
          sqlQuery,
          "COMMIT",
          "UNLOCK TABLES"
        ).map(QueryRequest(_))
    )

    verify(factory, times(1)).apply()
    verify(factory, times(0)).close(any[Time])
    verify(service, times(1)).close(any[Time])
  }

  test("sessions with nested transaction and discard") {
    val service = spy(new MockService())
    val factory = spy(new MockServiceFactory(service))
    val client = Client(factory, NullStatsReceiver, supportUnsigned = false)

    val result = client.session { session =>
      val inner = for {
        _ <- session.query("LOCK TABLES FOO WRITE")
        r <- session.transaction { tx =>
          for {
            _ <- tx.query(sqlQuery)
            _ <- tx.query(sqlQuery)
            _ <- Future.exception(new Exception())
          } yield "success"
        }
        _ <- session.query("UNLOCK TABLES")
      } yield r

      inner.onFailure(_ => session.discard())
    }

    intercept[Exception] { await(result) }
    assert(
      service.requests ==
        List(
          "LOCK TABLES FOO WRITE",
          "START TRANSACTION",
          sqlQuery,
          sqlQuery,
          "ROLLBACK"
        ).map(QueryRequest(_)) :+
          PoisonConnectionRequest
    )

    verify(factory, times(1)).apply()
    verify(factory, times(0)).close(any[Time])
    verify(service, times(1)).close(any[Time])
  }

  test("discarded sessions poison the connection") {
    val service = spy(new MockService())
    val factory = spy(new MockServiceFactory(service))
    val client = Client(factory, NullStatsReceiver, supportUnsigned = false)

    client.session(_.discard())

    assert(service.requests == List(PoisonConnectionRequest))

    verify(factory, times(1)).apply()
    verify(factory, times(0)).close(any[Time])
    verify(service, times(1)).close()
  }

  test("released sessions are returned to the pool") {
    val service = spy(new MockService())
    val factory = spy(new MockServiceFactory(service))
    val client = Client(factory, NullStatsReceiver, supportUnsigned = false)

    client.session(_ => Future.value("foo"))

    verify(factory, times(1)).apply()
    verify(factory, times(0)).close(any[Time])
    verify(service, times(1)).close(any[Time])
  }
}
