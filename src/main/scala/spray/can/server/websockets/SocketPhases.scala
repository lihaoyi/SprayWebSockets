package spray.can.server
package websockets
import model._
import model.Frame.{TooLarge, Incomplete, Successful}
import OpCode._
import spray.io._
import java.nio.ByteBuffer
import spray.io.TickGenerator.Tick

import akka.util.ByteString
import akka.actor.{Props, Actor, ActorRef}

import websockets.Sockets.Connected
import concurrent.duration.{FiniteDuration, Duration, Deadline}
import akka.io.Tcp

/**
 * Stores handy socket pipeline related stuff
 */
object SocketPhases{
  /**
   * Cleanly closes the websocket pipeline
   *
   * - Sends a "Close" frame
   * - Sends a message to kill the IOConnection
   */
  def close(commandPL : Pipeline[Command], closeCode: Short, message: String) = {
    val closeFrame = Frame(opcode = ConnectionClose, data = CloseCode.serializeCloseCode(closeCode))
    commandPL(Tcp.Write(ByteString(Frame.write(closeFrame))))
    commandPL(Tcp.Close)
  }
  /**
   * Wraps a frame in an Event going up the pipeline
   */
  case class FrameEvent(f: Frame) extends Event

  /**
   * Wraps a frame in a Command going down the pipeline
   */
  case class FrameCommand(frame: Frame) extends Command
}
import SocketPhases.{FrameCommand,FrameEvent}


/**
 * This pipeline stage simply forwards the events to and receives commands from
 * the given MessageHandler. It is the final stage of the websocket pipeline,
 * and is how the pipeline interacts with user code.
 *
 * @param handler the actor which will receive the incoming Frames
 */
case class WebsocketFrontEnd(handler: ActorRef) extends PipelineStage{

  /**
   * Used to let the frameHandler send back unwrapped Frames, which it
   * will wrap before putting into the pipeline
   */
  class ReceiverProxy(pcontext: PipelineContext) extends Actor{
    def receive = {
      case f: model.Frame => pcontext.actorContext.self ! FrameCommand(f)
      case Tcp.Close => pcontext.actorContext.self ! Tcp.Close
    }
  }

  def apply(pcontext: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines{

      val receiveAdapter = pcontext.actorContext.actorOf(Props(new ReceiverProxy(pcontext)))

      val commandPipeline: CPL = {
        case f => commandPL(f)
      }

      val eventPipeline: EPL = {
        case f @ FrameEvent(e) => commandPL(Pipeline.Tell(handler, e, receiveAdapter))
        case Tcp.Closed => commandPL(Pipeline.Tell(handler, Tcp.Closed, receiveAdapter))
        case Sockets.Connected => commandPL(Pipeline.Tell(handler, Connected, receiveAdapter))
        case rtt: Sockets.RoundTripTime => commandPL(Pipeline.Tell(handler, rtt, receiveAdapter))
        case x => // ignore all other events, e.g. Ticks
      }
    }
}

/**
 * This phase automatically performs the pings and matches up the resultant Pongs,
 */
case class AutoPingPongs(pingInterval: Duration,
                         pingGenerator: () => Array[Byte]) extends PipelineStage{

  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      var ticksInFlight = List[(ByteString, Deadline)]()
      var lastTick: Deadline = Deadline.now

      val commandPipeline: CPL = {
        case fc @ FrameCommand(f @ Frame(true, _, Ping, _, data)) =>
          lastTick = Deadline.now
          ticksInFlight = (data -> lastTick) :: ticksInFlight
          commandPL(fc)
        case x => commandPL(x)
      }

      val eventPipeline: EPL = {
        case Tick =>
          pingInterval match{
            case f: FiniteDuration if (Deadline.now - lastTick) > pingInterval =>

              val byteString = ByteString(pingGenerator())
              lastTick = Deadline.now
              ticksInFlight = (byteString -> lastTick) :: ticksInFlight
              commandPipeline(FrameCommand(Frame(
                opcode = OpCode.Ping,
                data = byteString
              )))
            case _ =>
          }

        case fe @ FrameEvent(f @ Frame(true, _, Pong, _, data)) =>
          val found = ticksInFlight.find(_._1 == data)

          found.map(_._2).foreach{ timestamp =>
            ticksInFlight = ticksInFlight.filter(_._2 > timestamp)
            eventPL(Sockets.RoundTripTime(Deadline.now - timestamp))
          }

          eventPL(fe)

        case x => eventPL(x)
      }
    }
}
/**
 * Does the fancy websocket stuff, handling:
 *
 * - Consolidating fragmented packets
 * - Responding to Pings
 * - Responding to Closed()
 * - Closes the connection if a frames is malformed
 *
 * This only handles the stuff the spec says a server *must* handle. Everything
 * else should go on the phases on top of this.
 */
case class Consolidation(maxMessageLength: Long) extends PipelineStage{
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {

      var stored: Option[Frame] = None
      val commandPipeline: CPL = {
        case x => commandPL(x)
      }
      var lastTick: Deadline = Deadline.now

      val eventPipeline: EPL = {
        case FrameEvent(f @ Frame(_, _, _, None, _)) =>
          // close connection on malformed frame
          SocketPhases.close(commandPL, CloseCode.ProtocolError.statusCode, "Client-Server frames must be masked")

        case FrameEvent(f @ Frame(true, _, ConnectionClose, _, _)) =>
          val newF = f.copy(maskingKey = None)
          commandPL(Tcp.Write(ByteString(Frame.write(newF))))
          commandPL(Tcp.Close)

        case FrameEvent(f @ Frame(true, _, Ping, _, _)) =>
          val newF = f.copy(opcode = Pong, maskingKey = None)
          commandPL(Tcp.Write(ByteString(ByteBuffer.wrap(Frame.write(newF)))))

        case FrameEvent(f @ Frame(false, _, _, _, _)) =>
          stored = Some(stored.fold(f)(x => x.copy(data = x.data ++ f.data)))

        case FrameEvent(f @ Frame(true, _, _, _, _)) =>
          if (stored.map(_.data.length).getOrElse(0) + f.data.length > maxMessageLength){
            // close connection on oversized packet
            SocketPhases.close(commandPL, CloseCode.MessageTooBig.statusCode, "Message exceeds maximum size of " + maxMessageLength)
          }else{
            stored = Some(stored.fold(f)(x => x.copy(data = x.data ++ f.data)))
            eventPL(FrameEvent(stored.get.copy(data = stored.get.data.compact)))
            stored = None
          }

        case msg => eventPL(msg)
      }
    }
}

/**
 * Deserializes IOBridge.Received events into FrameEvents, and serializes
 * FrameCommands into IOConnection.Send commands. Also enforces the limits on
 * how big a message can be, whether a message in a single big frame or a message
 * spread out over multiple frames. Otherwise does not do anything fancy.
 */
case class FrameParsing(maxMessageLength: Long) extends PipelineStage {
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      var streamBuffer: ByteString = ByteString()
      val commandPipeline: CPL = {
        case f: FrameCommand =>
          val bytes = ByteString(Frame.write(f.frame))
          commandPL(Tcp.Write(bytes))
        case x =>
          commandPL(x)
      }

      val eventPipeline: EPL = {
        case Tcp.Received(data) =>
          streamBuffer = streamBuffer ++ data
          val buffer = streamBuffer.asByteBuffer
          while(
            model.Frame.read(buffer, maxMessageLength) match{
              case Successful(frame) =>
                eventPL(FrameEvent(frame))
                true
              case Incomplete =>
                false
              case TooLarge =>
                SocketPhases.close(commandPL, CloseCode.MessageTooBig.statusCode, "Message exceeds maximum size of " + maxMessageLength)
                false
            }
          ){}
          streamBuffer = ByteString(buffer)

        case x => eventPL(x)
      }
    }
}
