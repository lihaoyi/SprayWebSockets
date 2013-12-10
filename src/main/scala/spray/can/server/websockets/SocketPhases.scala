package spray.can.server
package websockets
import model._
import spray.can.server.websockets.model.Frame.{Invalid, TooLarge, Incomplete, Successful}
import OpCode._
import spray.io._
import spray.io.TickGenerator.Tick

import akka.util.{CompactByteString, ByteString}
import akka.actor.{Props, Actor, ActorRef}

import spray.can.{Http}
import Sockets.Upgraded
import concurrent.duration.{FiniteDuration, Duration, Deadline}
import akka.io.Tcp
import java.nio.charset.{Charset, CharacterCodingException}
import java.io.DataInputStream
import spray.can.Http
import spray.can.parsing.{Parser, Result, HttpResponsePartParser, ParserSettings}
import spray.http._
import scala.annotation.tailrec
import spray.http.HttpHeaders.{`Transfer-Encoding`, `Content-Length`, `Content-Type`}
import spray.can.parsing.Result.IgnoreAllFurtherInput
import scala.Some
import spray.can.server.websockets.SocketPhases.FrameEvent
import spray.can.server.websockets.model.Frame.Successful
import spray.can.server.websockets.SocketPhases.FrameCommand

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
  def close(commandPL : Pipeline[Command], closeCode: CloseCode, message: String) = {
    val closeFrame = Frame(opcode = ConnectionClose, data = closeCode.toByteString)
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
      case f: model.Frame => pcontext.actorContext.self ! FrameCommand(f)
      case Tcp.Close      => pcontext.actorContext.self ! Tcp.Close
    }
  }

  def apply(pcontext: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines{
      val receiveAdapter = pcontext.actorContext.actorOf(Props(new ReceiverProxy(pcontext)))
      val commandPipeline: CPL = commandPL
      val eventPipeline: EPL = {
        case f @ FrameEvent(e)          => commandPL(Pipeline.Tell(handler, e, receiveAdapter))
        case c: Tcp.ConnectionClosed    => commandPL(Pipeline.Tell(handler, c, receiveAdapter))
        case Sockets.Upgraded           => commandPL(Pipeline.Tell(handler, Upgraded, receiveAdapter))
        case rtt: Sockets.RoundTripTime => commandPL(Pipeline.Tell(handler, rtt, receiveAdapter))
        case Http.MessageEvent(resp: spray.http.HttpResponse) => commandPL(Pipeline.Tell(handler, resp, receiveAdapter))
        case x => eventPL(x)
      }
    }
}

/**
 * Replies to incoming pings with pongs, saving you the hassle
 */
case class AutoPong(maskGen: Option[() => Int]) extends PipelineStage{
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {

      val commandPipeline: CPL = commandPL

      val eventPipeline: EPL = {
        case FrameEvent(f @ Frame(true, _, Ping, _, _)) =>
          val newF = f.copy(opcode = Pong, maskingKey = maskGen.map(_()))
          commandPL(Tcp.Write(Frame.write(newF)))

        case x => eventPL(x)
      }
    }
}

/**
 * Default ping body generator
 */
class Counter(var i: Int = 0) extends Function0[ByteString]{
  def apply() = {
    i += 1
    ByteString(i)
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
        case fc @ FrameCommand(f @ Frame(true, _, Ping, _, data)) => sendData(data)
        case x => commandPL(x)
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
      var scratch = new Array[Byte](1024)
      def validateUtf8(data: ByteString) = {
        if (data.length > scratch.length) scratch = new Array[Byte](data.length)
        data.copyToArray(scratch)
        Utf8Checker.validate(data.toArray)
      }

      var stored: Option[Frame] = None

      val commandPipeline: CPL = commandPL

      var lastTick: Deadline = Deadline.now

      val eventPipeline: EPL = {
        case FrameEvent(f @ Frame(_, 0, _, frameMask, _)) if frameMask.getClass == maskGen.getClass =>
          SocketPhases.close(commandPL, CloseCode.ProtocolError, "Improper masking")

        case FrameEvent(f @ Frame(true, 0, Ping | Pong | ConnectionClose, _, data))
          if data.length > 125 =>
          commandPL(Tcp.Close)

        // handle close requests
        case FrameEvent(f @ Frame(true, 0, ConnectionClose, frameMask, data)) =>

          val closeCode = if (data.length < 2) None else Some(data.toByteBuffer.getShort)
          val erroredCode = closeCode.fold(false)(c =>
            !CloseCode.statusCodes.contains(c) && !(c >= 3000 && c < 5000) ||
            c == 1005 || c == 1006 || c == 1015
          )
          if (!validateUtf8(data.drop(2))){
            SocketPhases.close(commandPL, CloseCode.ProtocolError, "Close reason not UTF-8")
          }else if (data.length == 1 || erroredCode) {
            SocketPhases.close(commandPL, CloseCode.ProtocolError, "Received illegal close code")
          }else{
            SocketPhases.close(commandPL, CloseCode.NormalClosure, "Closing connection")
          }
          eventPL(FrameEvent(f))

        // forward pings and pongs directly
        case FrameEvent(f @ Frame(true, 0, Ping | Pong, _, data)) =>
          eventPL(FrameEvent(f))

        // begin fragmented frame
        case FrameEvent(f @ Frame(false, 0, Text | Binary, _, _))
          if stored.isEmpty =>
          stored = Some(f)

        // aggregate fragmented data frames
        case FrameEvent(f @ Frame(false, 0, Continuation, _, _))
          if stored.isDefined =>
          stored = Some(stored.get.copy(data = stored.get.data ++ f.data))

        // combine completed data frames
        case FrameEvent(f @ Frame(true, 0, Continuation | Text | Binary, _, _)) =>
          if (f.opcode == Continuation && stored.isEmpty || f.opcode != Continuation && stored.isDefined){
            commandPL(Tcp.Close)
          }else if (stored.map(_.data.length).getOrElse(0) + f.data.length > maxMessageLength){
            // close connection on oversized packet
            SocketPhases.close(commandPL, CloseCode.MessageTooBig, "Message exceeds maximum size of " + maxMessageLength)
          }else{
            val result = stored.fold(f)(x => x.copy(data = x.data ++ f.data))

            if (result.opcode == OpCode.Text && !validateUtf8(result.data)) {
              commandPL(Tcp.Close)
            }else{
              eventPL(FrameEvent(result.copy(FIN=true, data = result.data.compact)))
            }

            stored = None
          }

        case FrameEvent(f) => commandPL(Tcp.Close)

        case msg => eventPL(msg)
      }
    }

}

/**
 * Manual state-machine based UTF-8 validator, shamelessly stolen from the
 * Netty code base and translated into Scala, because Java doesn't provide any
 * non-broken ways of validating UTF-8 (WTF-1111!!!)
 *
 * I have no idea how this works, but it makes the unit tests pass, so it must
 * be correct!
 */
object Utf8Checker{
  private[this] val Utf8Accept = 0
  private[this] val Utf8Reject = 12
  private[this] val types = Array[Byte](
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 9, 9, 9, 9, 9, 9,
    9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 8, 8, 2, 2, 2, 2, 2, 2,
    2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 10,
    3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 3, 3, 11, 6, 6, 6, 5, 8, 8, 8, 8, 8,
    8, 8, 8, 8, 8, 8
  )
  private[this] val states = Array[Byte](
    0, 12, 24, 36, 60, 96, 84, 12, 12, 12, 48, 72, 12, 12, 12, 12, 12, 12, 12,
    12, 12, 12, 12, 12, 12, 0, 12, 12, 12, 12, 12, 0, 12, 0, 12, 12, 12, 24,
    12, 12, 12, 12, 12, 24, 12, 24, 12, 12, 12, 12, 12, 12, 12, 12, 12, 24, 12,
    12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 12, 12, 24, 12, 12, 12, 12, 12, 12,
    12, 12, 12, 36, 12, 36, 12, 12, 12, 36, 12, 12, 12, 12, 12, 36, 12, 36, 12,
    12, 12, 36, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12
  )
  def validate(bytes: Array[Byte]): Boolean = {
    var codep = 0
    var state = Utf8Accept
    var i = 0
    while(i < bytes.length && state != Utf8Reject){
      val b = bytes(i)
      val t = types(b & 0xff)
        codep = if (state != Utf8Accept) b & 0x3f | codep << 6 else 0xff >> t & b
      state = states(state + t)

      i+= 1
    }
    state == Utf8Accept
  }
}

/**
 * Deserializes IOBridge.Received events into FrameEvents, and serializes
 * FrameCommands into IOConnection.Send commands. Also enforces the limits on
 * how big a message can be, whether a message in a single big frame or a message
 * spread out over multiple frames. Otherwise does not do anything fancy.
 */
case class FrameParsing(maxMessageLength: Int) extends PipelineStage {
  def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
    new Pipelines {
      val streamBuffer = new UberBuffer(512)
      val dataInput = new DataInputStream(streamBuffer.inputStream)
      val commandPipeline: CPL = {
        case f: FrameCommand => commandPL(Tcp.Write(Frame.write(f.frame)))
        case x               => commandPL(x)
      }

      val eventPipeline: EPL = {
        case Tcp.Received(data) => streamBuffer.write(data)

          var success = true
          while(success){
            streamBuffer.inputStream.mark(0)
            model.Frame.read(dataInput, maxMessageLength) match {
              case Successful(frame) =>
                eventPL(FrameEvent(frame))

              case Incomplete =>
                streamBuffer.inputStream.reset()
                success = false

              case TooLarge =>
                SocketPhases.close(commandPL, CloseCode.MessageTooBig, "Message exceeds maximum size of " + maxMessageLength)
                success = false

              case Invalid =>
                commandPL(Tcp.Close)
                success = false
            }
          }

        case x => eventPL(x)
      }
    }
}

object OneShotResponseParsing {
  def apply(settings: ParserSettings): PipelineStage = {
    new PipelineStage {
      var remainingData: Option[ByteString] = None
      var parser: Parser = new HttpResponsePartParser(settings)(){
        override def parseEntity(headers: List[HttpHeader], input: ByteString, bodyStart: Int, clh: Option[`Content-Length`],
                                 cth: Option[`Content-Type`], teh: Option[`Transfer-Encoding`], hostHeaderPresent: Boolean,
                                 closeAfterResponseCompletion: Boolean): Result = {
          remainingData = Some(input.drop(bodyStart))
          active = false

          emit(message(headers, HttpEntity.Empty), closeAfterResponseCompletion) {Result.IgnoreAllFurtherInput}
        }
        setRequestMethodForNextResponse(HttpMethods.GET)
      }

      var active = true
      def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
        new Pipelines {
          @tailrec def handle(result: Result): Unit = result match {
            case Result.NeedMoreData(next) =>
              parser = next
            case Result.Emit(part, closeAfterResponseCompletion, continue) =>
              eventPL(Http.MessageEvent(part))
              eventPL(Tcp.Received(remainingData.get))
              handle(continue())
            case Result.Expect100Continue(continue) =>
              handle(continue())
            case Result.ParsingError(status, info) =>
              commandPL(Http.Close)
            case Result.IgnoreAllFurtherInput =>
          }

          val commandPipeline: CPL = commandPL

          val eventPipeline: EPL = {
            case Tcp.Received(data: CompactByteString) if active => handle(parser(data))
            case ev ⇒
              eventPL(ev)
          }
        }
    }
  }
}
