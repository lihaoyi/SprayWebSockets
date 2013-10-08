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

import spray.can.server.websockets.Sockets.Upgraded
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
    println("SocketPhases.close " + message)
    val closeFrame = Frame(opcode = ConnectionClose, data = CloseCode.serializeCloseCode(closeCode))
    commandPL(Tcp.Write(Frame.write(closeFrame)))
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
      case f: model.Frame =>
        pcontext.actorContext.self ! FrameCommand(f)
      case Tcp.Close =>
        pcontext.actorContext.self ! Tcp.Close
    }
  }

  def apply(pcontext: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines{

      val receiveAdapter = pcontext.actorContext.actorOf(Props(new ReceiverProxy(pcontext)))

      val commandPipeline: CPL = {
        case f =>
          commandPL(f)
      }

      val eventPipeline: EPL = {
        case f @ FrameEvent(e) =>
           commandPL(Pipeline.Tell(handler, e, receiveAdapter))
        case Tcp.Closed =>
          commandPL(Pipeline.Tell(handler, Tcp.Closed, receiveAdapter))
        case Sockets.Upgraded => commandPL(Pipeline.Tell(handler, Upgraded, receiveAdapter))
        case rtt: Sockets.RoundTripTime => commandPL(Pipeline.Tell(handler, rtt, receiveAdapter))
        case x => // ignore all other events, e.g. Ticks
      }
    }
}

case class AutoPong(maskGen: () => Int) extends PipelineStage{
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {

      val commandPipeline: CPL = {
        case x => commandPL(x)
      }

      val eventPipeline: EPL = {
        case FrameEvent(f @ Frame(true, _, Ping, _, _)) =>
          val newF = f.copy(opcode = Pong, maskingKey = Some(maskGen()))
          commandPL(Tcp.Write(Frame.write(newF)))

        case x => eventPL(x)
      }
    }
}

class Counter(var i: Int = 0) extends Function0[ByteString  ]{
  def apply() = {
    i += 1
    ByteString(i+"")
  }
}
/**
 * This phase automatically performs the pings and matches up the resultant Pongs,
 */
case class AutoPing(interval: Duration = Duration.Inf,
                    bodyGen: () => ByteString = new Counter(),
                    memory: Int = 16)
                    extends PipelineStage{

  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {

      val ticksInFlight = new Array[(ByteString, Deadline)](memory)
      var index = 0
      var lastTick: Deadline = Deadline.now

      def sendData(byteString: ByteString) = {
        lastTick = Deadline.now
        ticksInFlight(index) = (byteString, lastTick)
        index = (index + 1) % ticksInFlight.length
        commandPL(FrameCommand(Frame(
          opcode = OpCode.Ping,
          data = byteString,
          maskingKey = None
        )))
      }

      val commandPipeline: CPL = {
        case fc @ FrameCommand(f @ Frame(true, _, Ping, _, data)) =>
          sendData(data)

        case x =>
          commandPL(x)
      }

      val eventPipeline: EPL = {
        case Tick =>
          interval match{
            case f: FiniteDuration if (Deadline.now - lastTick) > interval =>
              sendData(bodyGen())
            case _ =>
          }

        case fe @ FrameEvent(f @ Frame(true, _, Pong, _, data)) =>
          ticksInFlight
            .find(_._1 == data)
            .foreach{ case (data, time) =>
            eventPL(Sockets.RoundTripTime(Deadline.now - time))
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
case class Consolidation(maxMessageLength: Long, maskGen: Option[() => Int]) extends PipelineStage{
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {

      var stored: Option[Frame] = None
      val commandPipeline: CPL = {
        case x => commandPL(x)
      }
      var lastTick: Deadline = Deadline.now

      val eventPipeline: EPL = {
        // close connection on malformed frame
        case FrameEvent(f @ Frame(_, _, _, frameMask, _)) if frameMask.getClass == maskGen.getClass =>
          SocketPhases.close(commandPL, CloseCode.ProtocolError.statusCode, "Improper masking")

        // handle close requests
        case FrameEvent(f @ Frame(true, _, ConnectionClose, _, _)) =>
          println("Close Received")
          val newF = f.copy(maskingKey = maskGen.map(_()))
          println("Close Sent")
          commandPL(Tcp.Write(Frame.write(newF)))
          eventPL(FrameEvent(f))
          commandPL(Tcp.Close)

        // forward pings and pongs directly
        case FrameEvent(f @ Frame(true, _, Ping | Pong, _, _)) =>
          eventPL(FrameEvent(f))

        // aggregate fragmented data frames
        case FrameEvent(f @ Frame(false, _, _, _, _)) =>
          stored = Some(stored.fold(f)(x => x.copy(data = x.data ++ f.data)))


        // combine completed data frames
        case FrameEvent(f @ Frame(true, _, Text | Binary | Continuation, _, _)) =>
          if (stored.map(_.data.length).getOrElse(0) + f.data.length > maxMessageLength){
            // close connection on oversized packet
            SocketPhases.close(commandPL, CloseCode.MessageTooBig.statusCode, "Message exceeds maximum size of " + maxMessageLength)
          }else{
            stored = Some(stored.fold(f)(x => x.copy(data = x.data ++ f.data)))
            eventPL(FrameEvent(stored.get.copy(FIN=true, data = stored.get.data.compact)))
            stored = None
          }

        case msg =>
          eventPL(msg)
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
  val x = (math.random * 100).toInt
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      var streamBuffer: ByteString = ByteString()
      val commandPipeline: CPL = {
        case f: FrameCommand =>
          commandPL(Tcp.Write(Frame.write(f.frame)))
        case x =>
          commandPL(x)
      }

      val eventPipeline: EPL = {
        case Tcp.Received(data) =>
          println()
          println("Entering Parsing")
          println(streamBuffer)
          println(data)
          streamBuffer = streamBuffer ++ data

          var success = true

          do{
            println("Parsing " + streamBuffer)
            model.Frame.read(streamBuffer, maxMessageLength) match{
              case (Successful(frame), newBuffer) =>
                eventPL(FrameEvent(frame))
                success = true
                streamBuffer = newBuffer
                println("Post Parse " + streamBuffer)
              case (Incomplete, _) =>
                success = false
              case (TooLarge, _) =>
                SocketPhases.close(commandPL, CloseCode.MessageTooBig.statusCode, "Message exceeds maximum size of " + maxMessageLength)
                success = false
            }
          }while(success)

        case x =>
          println("XXX " + x)
          eventPL(x)
      }
    }
}
