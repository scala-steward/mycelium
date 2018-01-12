package mycelium.server

import akka.actor._
import akka.pattern.pipe
import mycelium.core.message._

import scala.concurrent.Future

class NotifiableClient[PublishEvent](actor: ActorRef) {
  private[mycelium] case class Notify(event: PublishEvent)
  def notify(event: PublishEvent): Unit = actor ! Notify(event)
}

private[mycelium] class ConnectedClient[Payload, Event, PublishEvent, Failure, State](
  handler: RequestHandler[Payload, Event, PublishEvent, Failure, State]) extends Actor {
  import ConnectedClient._
  import handler._
  import context.dispatcher

  def connected(outgoing: ActorRef) = {
    val client = new NotifiableClient[PublishEvent](self)
    def sendEvents(events: Seq[Event]) = if (events.nonEmpty) outgoing ! Notification(events.toList)
    def react(events: Future[Seq[Event]], state: Option[Future[State]]) = {
      events.foreach(sendEvents)
      state.foreach(state => context.become(withState(state)))
    }

    def withState(state: Future[State]): Receive = {
      case Ping() => outgoing ! Pong()

      case CallRequest(seqId, path, args: Payload@unchecked) =>
        val response = onRequest(client, state, path, args)

        response.result
          .map(r => CallResponse(seqId, r))
          .pipeTo(outgoing)

        react(response.events, response.state)

      case client.Notify(event) =>
        val reaction = onEvent(client, state, event)
        react(reaction.events, reaction.state)

      case Stop =>
        onClientDisconnect(client, state)
        context.stop(self)
    }

    val initial = onClientConnect(client)
    initial.events.foreach(sendEvents)
    withState(initial.state)
  }

  def receive = {
    case Connect(outgoing) => context.become(connected(outgoing))
    case Stop => context.stop(self)
  }
}
private[mycelium] object ConnectedClient {
  case class Connect(actor: ActorRef)
  case object Stop
}
