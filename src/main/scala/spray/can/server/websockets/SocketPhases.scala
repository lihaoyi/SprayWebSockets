package spray.can.server
package websockets
import model._
import OpCode._
import spray.io._
import java.nio.ByteBuffer
import spray.io.IOConnection.Tell
import akka.actor.ActorRef
import spray.io.TickGenerator.Tick

case class FrameEvent(f: Frame) extends Event
case class FrameCommand(frame: Frame) extends Command

case class WebsocketFrontEnd(receiver: ActorRef) extends PipelineStage{
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines{
      val commandPipeline: CPL = {
        case f @ FrameCommand(c) =>
          println("COMMAND")
          commandPL(f)
      }
      val eventPipeline: EPL = {
        case FrameEvent(e) =>
          println("EVENT")
          commandPL(Tell(receiver, e, context.self))
      }
    }
}

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
          val newF = f.copy(maskingKey = None, data = ByteBuffer.wrap(f.stringData.toUpperCase.getBytes("UTF-8")))

          eventPL(FrameEvent(newF))

        case msg => eventPL(msg)
      }
    }
}

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
        case Tick => ()
        case x => eventPL(x)
      }
    }
}