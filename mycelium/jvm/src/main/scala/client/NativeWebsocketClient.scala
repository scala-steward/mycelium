package mycelium.client

import mycelium.core._
import mycelium.core.message._
import akka.actor.ActorSystem

trait NativeWebsocketClient {
  def apply[PickleType : AkkaMessageBuilder, Payload, Event, Failure](
    config: ClientConfig,
    handler: IncidentHandler[Event])(implicit
    system: ActorSystem,
    writer: Writer[ClientMessage[Payload], PickleType],
    reader: Reader[ServerMessage[Payload, Event, Failure], PickleType]) =
      WebsocketClientFactory(new AkkaWebsocketConnection, config, handler)
}