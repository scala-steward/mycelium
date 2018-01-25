package mycelium.server

import mycelium.core._
import mycelium.core.message._
import chameleon._

import akka.NotUsed
import akka.actor._
import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl._

object WebsocketServerFlow {
  type Type = Flow[Message, Message, NotUsed]

  def apply[PickleType, Payload, Event, PublishEvent, Failure, State](
    config: ServerConfig,
    handler: RequestHandler[Payload, Event, PublishEvent, Failure, State])(implicit
    system: ActorSystem,
    serializer: Serializer[ServerMessage[Payload, Event, Failure], PickleType],
    deserializer: Deserializer[ClientMessage[Payload], PickleType],
    builder: AkkaMessageBuilder[PickleType]): Type = {

    val connectedClientActor = system.actorOf(Props(new ConnectedClient(handler)))

    val incoming: Sink[Message, NotUsed] =
      Flow[Message].mapConcat {
        case m: Message =>
          val result = for {
            value <- builder.unpack(m).toRight(s"Builder does not support message: $m").right
            msg <- deserializer.deserialize(value).left.map(t => s"Deserializer failed: ${t.getMessage}").right
          } yield msg

          result match {
            case Right(res) =>
              res :: Nil
            case Left(err) =>
              scribe.warn(s"Ignoring websocket message. $err")
              Nil
          }
      }.to(Sink.actorRef[ClientMessage[Payload]](connectedClientActor, ConnectedClient.Stop))

    val outgoing: Source[Message, NotUsed] =
      Source.actorRef[ServerMessage[Payload, Event, Failure]](config.bufferSize, config.overflowStrategy)
        .mapMaterializedValue { outActor =>
          connectedClientActor ! ConnectedClient.Connect(outActor)
          NotUsed
        }.map { msg =>
          val value = serializer.serialize(msg)
          builder.pack(value)
        }

    Flow.fromSinkAndSource(incoming, outgoing)
  }
}
