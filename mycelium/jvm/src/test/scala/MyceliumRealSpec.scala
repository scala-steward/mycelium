package test

import java.nio.ByteBuffer

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.stream.{ActorMaterializer, OverflowStrategy}
import boopickle.Default._
import chameleon.ext.boopickle._
import mycelium.client._
import mycelium.server._
import org.scalatest._
import monix.reactive.Observable
import monix.eval.Task

import scala.concurrent.duration._

class MyceliumRealSpec extends AsyncFreeSpec with MustMatchers with BeforeAndAfterAll {
  override implicit def executionContext = monix.execution.Scheduler.global

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  val port = 9899

  override def afterAll(): Unit = {
    system.terminate()
    ()
  }

  type Payload = Int
  type Failure = Int
  type State = String

  val config = WebsocketServerConfig(bufferSize = 5, overflowStrategy = OverflowStrategy.fail, parallelism = 2)
  val handler = new StatelessRequestHandler[Payload, Failure] {
    def onRequest(client: ClientId, path: List[String], payload: Payload) = path match {
      case "single" :: Nil => Response(Task(EventualResult.Single(payload)))
      case "stream" :: Nil => Response(Task(EventualResult.Stream(Observable(1,2,3,4))))
      case _ => ???
    }
  }
  val server = WebsocketServer.withPayload(config, handler)
  val route = handleWebSocketMessages(server.flow())
  Http().bindAndHandle(route, interface = "0.0.0.0", port = port)

  "client with akka" - {
    val client = WebsocketClient.withPayload[ByteBuffer, Payload, Failure](
      new AkkaWebsocketConnection(bufferSize = 100, overflowStrategy = OverflowStrategy.fail), WebsocketClientConfig())

    client.run(s"ws://localhost:$port")

    "single result" in {
      val res = client.send("single" :: Nil, 1, SendType.WhenConnected, Some(10 seconds))
      res.flatMap(_.right.get.lastL).runAsync.map(_ mustEqual 1)
    }

    "stream result" in {
      val res = client.send("stream" :: Nil, 0, SendType.WhenConnected, Some(11 seconds))
      Observable.fromTask(res).flatMap(_.right.get).toListL.runAsync.map(l => l mustEqual List(1,2,3,4))
    }
  }
}
