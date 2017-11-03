package mycelium.client

import mycelium.core._
import mycelium.core.message._

import scala.concurrent.{ ExecutionContext, Future }

class WebsocketClient[Encoder[_], Decoder[_], PickleType, Payload, Event, Failure](
  ws: WebsocketConnection[PickleType],
  handler: IncidentHandler[Event, Failure],
  requestTimeoutMillis: Int)(implicit
  encoder: Encoder[ClientMessage[Payload]],
  decoder: Decoder[ServerMessage[Payload, Event, Failure]],
  serializer: Serializer[Encoder, Decoder, PickleType]) {

  private val callRequests = new OpenRequests[Either[Failure, Payload]](requestTimeoutMillis)

  def send(path: List[String], payload: Payload)(implicit ec: ExecutionContext): Future[Either[Failure, Payload]] = {
    val (id, promise) = callRequests.open()

    val request = CallRequest(id, path, payload)
    val serialized = serializer.serialize[ClientMessage[Payload]](request)
    ws.send(serialized)

    promise.future
  }

  def run(location: String): Unit = ws.run(location, new WebsocketListener[PickleType] {
    private var wasClosed = false
    def onConnect() = handler.onConnect(wasClosed)
    def onClose() = wasClosed = true
    def onMessage(msg: PickleType): Unit = {
      serializer.deserialize[ServerMessage[Payload, Event, Failure]](msg) match {
        case Right(CallResponse(seqId, result: Either[Failure@unchecked, Payload@unchecked])) =>
          callRequests.get(seqId).foreach(_ trySuccess result)
        case Right(Notification(events: List[Event@unchecked])) =>
          handler.onEvents(events)
        case Right(Pong()) =>
          // do nothing
        case Left(error) =>
          //TODO: log error
      }
    }
  })
}

object WebsocketClient {
  def apply[Encoder[_], Decoder[_], PickleType, Payload, Event, Failure](
    config: ClientConfig,
    handler: IncidentHandler[Event, Failure])(implicit
    encoder: Encoder[ClientMessage[Payload]],
    decoder: Decoder[ServerMessage[Payload, Event, Failure]],
    serializer: Serializer[Encoder, Decoder, PickleType],
    system: NativeWebsocketConnection.System,
    builder: NativeWebsocketConnection.Builder[PickleType]) = {
      import config._

      val nativeConn = NativeWebsocketConnection[PickleType]
      val wrapper: List[WebsocketConnection[PickleType] => Option[WebsocketConnection[PickleType]]] = List(
        conn => pingConfig.map { c =>
          val serializedPing = serializer.serialize[ClientMessage[Payload]](Ping[Payload]())
          new PingingWebsocketConnection(conn, serializedPing, c.timeoutMillis)
        },
        conn => reconnectConfig.map { c =>
          new ReconnectingWebsocketConnection(conn, c.minimumBackoffMillis)
        }
      )

      val conn = wrapper.foldLeft(nativeConn)((conn, wrap) => wrap(conn) getOrElse conn)
      new WebsocketClient(conn, handler, requestConfig.timeoutMillis)
    }
}
