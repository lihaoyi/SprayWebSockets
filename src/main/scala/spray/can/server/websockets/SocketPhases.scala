package spray.can.server
package websockets
import model._
import OpCode._
import spray.io._
import java.nio.ByteBuffer



case class FrameEvent(f: Frame) extends Event
case class FrameCommand(f: Frame) extends Command


case class Consolidation() extends PipelineStage{
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      var frags: List[Frame] = Nil
      val commandPipeline = commandPL
      val eventPipeline: EPL = {
        case FrameEvent(f @ Frame(_, _, ConnectionCloseFrame, _, _)) =>
          val newF = f.copy(maskingKey = None)
          commandPL(IOConnection.Send(ByteBuffer.wrap(Frame.write(newF))))

        case FrameEvent(Frame(_, _, PingFrame, _, _)) => ()

        case FrameEvent(Frame(_, _, PongFrame, _, _)) => ()

        case FrameEvent(f @ Frame(false, _, opcode, _, _))
          if opcode == TextFrame || opcode == BinaryFrame => // start frag
          frags = f :: frags

        case FrameEvent(f @ Frame(false, _, ContinuationFrame, _, _)) => // continue frag
          frags = f :: frags

        case FrameEvent(f @ Frame(true, _, opcode, _, _))
          if opcode == TextFrame || opcode == BinaryFrame =>
          val newF = f.copy(maskingKey = None, data = f.stringData.toUpperCase.getBytes("UTF-8"))

          commandPL(IOConnection.Send(ByteBuffer.wrap(Frame.write(newF))))
      }
    }
}
case class FrameParsing() extends PipelineStage {
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      val commandPipeline = commandPL
      val eventPipeline: EPL = {
        case IOBridge.Received(connection, buffer) =>
          val frame = model.Frame.read(buffer)
          eventPL(FrameEvent(frame))
        case _ => ()
      }
    }
}