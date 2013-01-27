package spray.can.server
package websockets
import model._
import OpCode._
import spray.io._
import java.nio.ByteBuffer
import spray.io.TickGenerator.Tick
import spray.io.IOConnection.Tell

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
        case f @ FrameCommand(c) => commandPL(f)
      }

      val eventPipeline: EPL = {
        case f @ FrameEvent(e) => commandPL(Tell(handlerCreator(), e, context.self))
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
case class Consolidation() extends PipelineStage{
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      var frags: List[Frame] = Nil
      val commandPipeline = commandPL

      val eventPipeline: EPL = {

        case FrameEvent(f @ Frame(_, _, ConnectionClose, _, _)) =>
          val newF = f.copy(maskingKey = None)
          commandPL(IOConnection.Send(ByteBuffer.wrap(Frame.write(newF))))

        case FrameEvent(f @ Frame(false, _, opcode, _, _))
          if opcode == Text || opcode == Binary => // start frag
          frags = f :: frags

        case FrameEvent(f @ Frame(false, _, Continuation, _, _)) => // continue frag
          frags = f :: frags

        case FrameEvent(f @ Frame(true, _, opcode, _, _))
          if opcode == Text || opcode == Binary =>
          frags = f :: frags

          eventPL(FrameEvent(f.copy(data = frags.map(_.data).reduce(_++_).compact)))
          frags = Nil
        case msg => eventPL(msg)
      }
    }
}

/**
 * Deserializes IOBridge.Received events into FrameEvents, and serializes
 * FrameCommands into IOConnection.Send commands. Otherwise does not do anything
 * fancy.
 */
case class FrameParsing() extends PipelineStage {
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      val commandPipeline: CPL = {
        case f: FrameCommand =>
          commandPL(IOConnection.Send(ByteBuffer.wrap(Frame.write(f.frame))))
        case x => commandPL(x)
      }

      val eventPipeline: EPL = {
        case IOBridge.Received(connection, buffer) =>
          val frame = model.Frame.read(buffer)
          eventPL(FrameEvent(frame))

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