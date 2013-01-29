package spray.can.server
package websockets
import model._
import model.Frame.{TooLarge, Incomplete, Successful}
import OpCode._
import spray.io._
import java.nio.ByteBuffer
import spray.io.TickGenerator.Tick
import spray.io.IOConnection.Tell
import akka.util.ByteString
import akka.actor.IO.Closed

case class FrameEvent(f: Frame) extends Event
case class FrameCommand(frame: Frame) extends Command
case class Upgrade(data: Any) extends Command

/**
 * This pipeline stage simply forwards the events to and receives commands from
 * the given MessageHandler. It is the final stage of the websocket pipeline,
 * and is how the pipeline interacts with user code.
 */
case class WebsocketFrontEnd(messageHandler: MessageHandler) extends PipelineStage{
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines{
      def handlerCreator = messageHandler(context)
      val commandPipeline: CPL = {
        case f @ FrameCommand(c) =>
          commandPL(f)
      }

      val eventPipeline: EPL = {
        case f @ FrameEvent(e) =>
          commandPL(Tell(handlerCreator(), e, context.self))
        case c: IOBridge.Closed => commandPL(Tell(handlerCreator(), c, context.self))
      }
    }
}

/**
 * Does the fancy websocket stuff, handling:
 *
 * - Consolidating fragmented packets
 * - Emitting Pings
 * - Responding to Pings
 * - Responding to Closed()
 *
 */
object Cow{
  def close(commandPL : Pipeline[Command], closeCode: Short, message: String) = {
    val closeCodeData = ByteString(
      ByteBuffer.allocate(2)
        .putShort(closeCode)
        .rewind().asInstanceOf[ByteBuffer]
    )
    commandPL(IOConnection.Send(ByteBuffer.wrap(Frame.write(Frame(opcode = ConnectionClose, data = closeCodeData)))))
    commandPL(IOConnection.Close(spray.util.ConnectionCloseReasons.ProtocolError(message)))
  }
}
case class Consolidation(maxMessageLength: Long) extends PipelineStage{
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      var stored: Option[Frame] = None
      val commandPipeline: CPL = {
        case x =>
          commandPL(x)
      }

      val eventPipeline: EPL = {
        case FrameEvent(f @ Frame(_, _, _, None, _)) =>
          Cow.close(commandPL, CloseCode.ProtocolError.statusCode, "Client-Server frames must be masked")

        case FrameEvent(f @ Frame(true, _, ConnectionClose, _, _)) =>
          val newF = f.copy(maskingKey = None)
          commandPL(IOConnection.Send(ByteBuffer.wrap(Frame.write(newF))))
          commandPL(IOConnection.Close(spray.util.ConnectionCloseReasons.CleanClose))

        case FrameEvent(f @ Frame(true, _, Ping, _, _)) =>
          val newF = f.copy(maskingKey = None)
          commandPL(IOConnection.Send(ByteBuffer.wrap(Frame.write(newF))))

        case FrameEvent(f @ Frame(false, _, opcode, _, _)) =>
          stored = Some(stored.fold(f)(x => x.copy(data = x.data ++ f.data)))

        case FrameEvent(f @ Frame(true, _, opcode, _, _)) =>
          if (stored.map(_.data.length).getOrElse(0) + f.data.length > maxMessageLength){
            Cow.close(commandPL, CloseCode.MessageTooBig.statusCode, "Message exceeds maximum size of " + maxMessageLength)
          }else{
            stored = Some(stored.fold(f)(x => x.copy(data = x.data ++ f.data)))
            eventPL(FrameEvent(stored.get.copy(data = stored.get.data.compact)))
            stored = None
          }

        case msg =>
          eventPL(msg)
      }
    }
}

/**
 * Deserializes IOBridge.Received events into FrameEvents, and serializes
 * FrameCommands into IOConnection.Send commands. Otherwise does not do anything
 * fancy.
 */
case class FrameParsing(maxMessageLength: Long) extends PipelineStage {
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      var streamBuffer: ByteString = ByteString()
      val commandPipeline: CPL = {
        case f: FrameCommand =>
          commandPL(IOConnection.Send(ByteBuffer.wrap(Frame.write(f.frame))))
        case x => commandPL(x)
      }

      val eventPipeline: EPL = {
        case IOBridge.Received(connection, data) =>
          streamBuffer = streamBuffer ++ ByteString(data)
          val buffer = streamBuffer.asByteBuffer
          while(

            model.Frame.read(buffer, maxMessageLength) match{
              case Successful(frame) =>
                eventPL(FrameEvent(frame))
                true
              case Incomplete =>
                false
              case TooLarge =>
                Cow.close(commandPL, CloseCode.MessageTooBig.statusCode, "Message exceeds maximum size of " + maxMessageLength)
                false
            }
          ){}
          streamBuffer = ByteString(buffer)

        case Tick => () // ignore Ticks, do not propagate
        case x => eventPL(x)
      }
    }
}

case class Switching(stage1: PipelineStage, stage2: PipelineStage) extends PipelineStage {

  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      val pl1 = stage1(context, commandPL, eventPL)
      val pl2 = stage2(context, commandPL, eventPL)
      var eventPLVar = pl1.eventPipeline
      var commandPLVar = pl1.commandPipeline

      // it is important to introduce the proxy to the var here
      def commandPipeline: CPL = {
        case Response(_, u @ Upgrade(msg)) =>
          eventPLVar = pl2.eventPipeline
          commandPLVar = pl2.commandPipeline
        case c =>
          commandPLVar(c)
      }
      def eventPipeline: EPL = {
        c => eventPLVar(c)
      }
    }
}