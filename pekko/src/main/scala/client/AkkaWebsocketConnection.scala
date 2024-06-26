package mycelium.pekko.client

import mycelium.pekko.core.PekkoMessageBuilder
import mycelium.core.Cancelable
import mycelium.core.client._

import org.apache.pekko.actor._
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.stream.{Materializer, OverflowStrategy, QueueOfferResult, KillSwitches}
import org.apache.pekko.stream.scaladsl._
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.ws._
import org.apache.pekko.http.scaladsl.settings.ClientConnectionSettings
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Success, Failure}

class PekkoWebsocketConnection[PickleType](
    bufferSize: Int,
    overflowStrategy: OverflowStrategy,
)(implicit
    system: ActorSystem,
    materializer: Materializer,
    builder: PekkoMessageBuilder[PickleType],
) extends WebsocketConnection[PickleType] {
  import system.dispatcher

  private var isStarted = false

  private val (outgoing, outgoingMaterialized) = {
    val promise = Promise[SourceQueue[Message]]()
    val source = Source
      .queue[Message](bufferSize, overflowStrategy)
      .mapMaterializedValue { m => promise.success(m); m }
    (source, promise.future)
  }

  private val sendActor =
    system.actorOf(Props(new SendActor(outgoingMaterialized)))

  def rawSend(msg: PickleType): Unit = sendActor ! msg

  def send(msg: WebsocketMessage[PickleType]): Unit = sendActor ! msg

  //TODO return result signaling closed
  def run(
      location: () => Future[String],
      wsConfig: WebsocketClientConfig,
      pingMessage: PickleType,
      listener: WebsocketListener[PickleType],
  ) = if (!isStarted) {
    isStarted = true

    val incoming = Sink.foreach[Message] { message =>
      builder
        .unpack(message)
        .onComplete { //TODO we are breaking the order here, better sequence the future[m] inside the sink? foldasync?
          case Success(value) =>
            value match {
              case Some(value) => listener.onMessage(value)
              case None =>
                scribe.warn(
                  s"Ignoring websocket message. Builder does not support message ($message)",
                )
            }
          case Failure(t) =>
            scribe.warn(
              s"Ignoring websocket message. Builder failed to unpack message ($message): $t",
            )
        }
    }

    @annotation.nowarn("cat=deprecation")
    val wsFlow = RestartFlow.withBackoff(
      minBackoff = wsConfig.minReconnectDelay,
      maxBackoff = wsConfig.maxReconnectDelay,
      randomFactor = wsConfig.delayReconnectFactor - 1,
    ) { () =>
      Flow.futureFlow(location().map { location => Http()
        .webSocketClientFlow(
          WebSocketRequest(location),
          settings = ClientConnectionSettings(system).withConnectingTimeout(
            wsConfig.connectingTimeout,
          ),
        )
      })
        .mapMaterializedValue { upgrade =>
          upgrade.flatten.foreach { upgrade =>
            if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
              outgoingMaterialized.foreach { _ => // TODO: need to wait for materialized queue, as actor depends on it...
                sendActor ! SendActor.Connected
                listener.onConnect()
                sendActor ! SendActor.SendBuffer
              }
            }
          }
          upgrade
        }
        .mapError { case t =>
          scribe.warn(s"Error in websocket connection: $t")
          sendActor ! SendActor.Closed
          listener.onClose()
          t
        }
    }

    val websocketPingMessage = builder.pack(pingMessage)

    val (killSwitch, closed) = outgoing
      .keepAlive(wsConfig.pingInterval, () => websocketPingMessage)
      .viaMat(wsFlow)(Keep.left)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(incoming)(Keep.both)
      .run()

    closed.onComplete { res =>
      scribe.error(s"Websocket connection finally closed: $res")
    }

    Cancelable { () =>
      isStarted = false
      killSwitch.shutdown()
    }
  } else throw new Exception("Websocket already running")
}

//TODO future source is dangerous as it might complete before we receive a Connected message
private[client] class SendActor[PickleType](
    queue: Future[SourceQueue[Message]],
)(implicit ec: ExecutionContext, builder: PekkoMessageBuilder[PickleType])
    extends Actor {
  import SendActor._

  private var isConnected = false
  private val messageSender =
    new WebsocketMessageSender[PickleType, SourceQueue[Message]] {
      override def senderOption =
        if (isConnected) queue.value.flatMap(_.toOption) else None
      override def doSend(
          queue: SourceQueue[Message],
          rawMessage: PickleType,
      ) = {
        val message = builder.pack(rawMessage)
        queue.offer(message).map {
          case QueueOfferResult.Enqueued => true
          case res =>
            scribe.warn(s"Websocket connection could not send message: $res")
            false
        }
      }
    }

  def receive = {
    case Connected =>
      isConnected = true
    case SendBuffer =>
      messageSender.trySendBuffer()
    case Closed =>
      isConnected = false
    case message: WebsocketMessage[PickleType @unchecked] =>
      messageSender.sendOrBuffer(message)
    case rawMessage: PickleType @unchecked =>
      messageSender.senderOption.foreach(messageSender.doSend(_, rawMessage))
  }
}
private[client] object SendActor {
  case object Connected
  case object SendBuffer
  case object Closed
}
